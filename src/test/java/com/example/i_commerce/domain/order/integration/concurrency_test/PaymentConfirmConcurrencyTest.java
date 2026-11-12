package com.example.i_commerce.domain.order.integration.concurrency_test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.i_commerce.common.IntegrationTestSupport;
import com.example.i_commerce.domain.order.client.PaymentClient;
import com.example.i_commerce.domain.order.entity.Order;
import com.example.i_commerce.domain.order.entity.OrderProduct;
import com.example.i_commerce.domain.order.entity.Payment;
import com.example.i_commerce.domain.order.entity.emuns.OrderStatus;
import com.example.i_commerce.domain.order.entity.emuns.PaymentStatus;
import com.example.i_commerce.domain.order.event.listener.DeliveryListener;
import com.example.i_commerce.domain.order.exception.PaymentErrorCode;
import com.example.i_commerce.domain.order.facade.PaymentFacade;
import com.example.i_commerce.domain.order.repository.OrderRepository;
import com.example.i_commerce.domain.order.repository.PaymentRepository;
import com.example.i_commerce.domain.order.service.dto.PaymentConfirmRequest;
import com.example.i_commerce.domain.product.event.listener.StockEventListener;
import com.example.i_commerce.global.exception.AppException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * [결제 승인(confirm) 동시성 테스트]
 * 같은 결제(tossOrderId)에 대해 confirm 요청이 동시에 두 번 들어왔을 때
 * (더블클릭, 네트워크 재시도 등으로 인한 중복 요청 시나리오)
 * READY -> PROCESSING 조건부(원자적) 업데이트가 한 쪽만 선점에 성공시키는지 검증한다.
 */
@SpringBootTest
class PaymentConfirmConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PaymentClient tossPaymentClient;

    @MockitoBean
    private StockEventListener stockEventListener;

    @MockitoBean
    private DeliveryListener deliveryListener;

    private String testTossOrderId;
    private final String testPaymentKey = "toss-payment-key-" + UUID.randomUUID();
    private final int amount = 10000;

    @BeforeEach
    void setUp() {
        Order order = Order.builder()
                .userId(1L)
                .totalProductAmount(amount)
                .totalPayAmount(amount)
                .orderStatus(OrderStatus.PENDING)
                .build();

        Order savedOrder = orderRepository.save(order);

        OrderProduct orderProduct = OrderProduct.builder()
                .order(savedOrder)
                .productSkuId(1L)
                .productName("테스트상품")
                .orderPrice(amount)
                .count(1)
                .build();
        savedOrder.getOrderProducts().add(orderProduct);
        orderRepository.save(savedOrder);

        testTossOrderId = "toss-" + UUID.randomUUID();
        Payment payment = Payment.builder()
                .order(savedOrder)
                .tossOrderId(testTossOrderId)
                .amount(amount)
                .cancelableAmount(0)
                .payStatus(PaymentStatus.READY)
                .build();
        paymentRepository.save(payment);
        paymentRepository.flush();

        given(tossPaymentClient.requestConfirm(any())).willReturn(Map.of("paymentKey", testPaymentKey));
    }

    @Test
    @DisplayName("동일한 결제를 두 요청이 동시에 승인(confirm) 시도하면 한 쪽만 선점에 성공하고, " +
            "다른 쪽은 PG 호출 없이 즉시 PAYMENT_ALREADY_PROCESSING으로 거절된다")
    void concurrentConfirmPayment_OnlyOneWins() throws InterruptedException {
        int numberOfThreads = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Exception> exceptions = new ConcurrentLinkedQueue<>();

        PaymentConfirmRequest request = new PaymentConfirmRequest(testPaymentKey, testTossOrderId, amount);

        for (int i = 0; i < numberOfThreads; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    paymentFacade.confirmPayment(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        // 둘 중 정확히 하나만 성공해야 한다
        Assertions.assertThat(successCount.get()).isEqualTo(1);
        Assertions.assertThat(exceptions).hasSize(1);

        Exception rejected = exceptions.iterator().next();
        Assertions.assertThat(rejected).isInstanceOf(AppException.class);
        Assertions.assertThat(((AppException) rejected).getErrorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSING);

        // 결과적으로 결제는 정상적으로 완료 상태여야 한다
        Payment finalPayment = paymentRepository.findByTossOrderIdWithOrder(testTossOrderId).orElseThrow();
        Assertions.assertThat(finalPayment.getPayStatus()).isEqualTo(PaymentStatus.PAID);

        // 선점에 실패한 쪽은 PG 승인 요청 자체를 호출하지 않아야 한다 (불필요한 외부 호출/중복 승인 방지)
        verify(tossPaymentClient, times(1)).requestConfirm(any());
    }
}
