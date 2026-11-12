package com.example.i_commerce.domain.order.unit.scheduler;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.i_commerce.domain.order.client.TossPaymentClient;
import com.example.i_commerce.domain.order.entity.Order;
import com.example.i_commerce.domain.order.entity.OrderProduct;
import com.example.i_commerce.domain.order.entity.Payment;
import com.example.i_commerce.domain.order.entity.emuns.OrderStatus;
import com.example.i_commerce.domain.order.entity.emuns.PaymentStatus;
import com.example.i_commerce.domain.order.exception.PaymentErrorCode;
import com.example.i_commerce.domain.order.repository.PaymentRepository;
import com.example.i_commerce.domain.order.scheduler.PaymentRecoveryScheduler;
import com.example.i_commerce.domain.product.event.OrderCancelledEvent;
import com.example.i_commerce.domain.product.event.OrderCompletedEvent;
import com.example.i_commerce.global.exception.AppException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentRecoverySchedulerTest {

    @Mock
    PaymentRepository paymentRepository;

    @Mock
    TossPaymentClient tossPaymentClient;

    @Mock
    ApplicationEventPublisher publisher;

    @InjectMocks
    PaymentRecoveryScheduler scheduler;

    private Payment payment;
    private Order order;

    @BeforeEach
    void setUp() {
        // 테스트용 연관관계 엔티티 세팅
        order = Order.builder().build();
        ReflectionTestUtils.setField(order, "id", 1L);
        OrderProduct orderProduct = OrderProduct.builder()
                .productSkuId(10L)
                .count(2)
                .build();
        orderProduct.assignOrder(order);
        order.getOrderProducts().add(orderProduct);
        order.changeOrderStatus(OrderStatus.UNKNOWN_HOLD);

        payment = Payment.builder()
                .id(1L)
                .amount(10000)
                .cancelableAmount(0)
                .pgTid("toss_key_123")
                .payStatus(PaymentStatus.UNKNOWN_HOLD)
                .order(order)
                .cancelAmount(10000)
                .cancelReason("고객 변심")
                .build();
    }

    @Test
    @DisplayName("결제 중(PROCESSING) 복구 성공 - 토스 장부가 DONE이면 결제완료 및 주문확정 상태로 바뀐다")
    void processStuckProcessingPayment_Done_Success() {
        payment.changePayStatus(PaymentStatus.PROCESSING);
        order.changeOrderStatus(OrderStatus.PENDING);

        given(paymentRepository.findByTossOrderIdWithOrder(payment.getTossOrderId())).willReturn(Optional.of(payment));

        scheduler.processStuckProcessingPayment(payment.getTossOrderId(), "DONE");

        assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getCancelableAmount()).isEqualTo(payment.getAmount());
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(publisher, times(1)).publishEvent(any(OrderCompletedEvent.class));
    }

    @Test
    @DisplayName("결제 중(PROCESSING) 복구 실패 - 토스 장부가 DONE이 아니면 결제실패 및 주문취소 상태로 종결된다")
    void processStuckProcessingPayment_NotDone_Fail() {
        payment.changePayStatus(PaymentStatus.PROCESSING);
        order.changeOrderStatus(OrderStatus.PENDING);

        given(paymentRepository.findByTossOrderIdWithOrder(payment.getTossOrderId())).willReturn(Optional.of(payment));

        scheduler.processStuckProcessingPayment(payment.getTossOrderId(), "ABORTED");

        assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("결제 중(PROCESSING) 복구 보류 - 토스 장부가 아직 IN_PROGRESS면 상태를 바꾸지 않고 다음 주기로 넘긴다")
    void processStuckProcessingPayment_InProgress_Deferred() {
        payment.changePayStatus(PaymentStatus.PROCESSING);
        order.changeOrderStatus(OrderStatus.PENDING);

        given(paymentRepository.findByTossOrderIdWithOrder(payment.getTossOrderId())).willReturn(Optional.of(payment));

        scheduler.processStuckProcessingPayment(payment.getTossOrderId(), "IN_PROGRESS");

        assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("결제 중(PROCESSING) 복구 실패 - 토스에도 결제 흔적이 없으면 결제실패 및 주문취소 상태로 종결되고 재고 복구 이벤트가 발행된다")
    void processStuckProcessingFailure_Success() {
        payment.changePayStatus(PaymentStatus.PROCESSING);
        order.changeOrderStatus(OrderStatus.PENDING);

        given(paymentRepository.findByTossOrderIdWithOrder(payment.getTossOrderId())).willReturn(Optional.of(payment));

        scheduler.processStuckProcessingFailure(payment.getTossOrderId());

        assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(publisher, times(1)).publishEvent(any(OrderCancelledEvent.class));
    }

    @Test
    @DisplayName("승인 홀드 건 복구 성공 - 토스 장부가 DONE이면 결제완료 및 주문확정 상태로 바뀐다")
    void processUnknownHoldPayments_Success() {
        // when
        given(paymentRepository.findByTossOrderIdWithOrder(payment.getTossOrderId())).willReturn(Optional.of(payment));
        scheduler.processUnknownHoldPayments(payment.getTossOrderId(), "DONE");

        // then
        assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getCancelableAmount()).isEqualTo(payment.getAmount());
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(publisher, times(1)).publishEvent(any(OrderCompletedEvent.class));
    }

    @Test
    @DisplayName("승인 홀드 건 복구 실패 - 토스 장부에 내역이 없으면 결제실패 및 주문취소 상태로 종결된다")
    void processUnknownHoldPayments_Fail() {
        // when
        given(paymentRepository.findByTossOrderIdWithOrder(payment.getTossOrderId())).willReturn(Optional.of(payment));
        scheduler.processUnknownHoldPayments(payment.getTossOrderId(), "NONE");

        // then
        assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("취소 홀드 건 복구 성공 - 토스 장부가 CANCELED면 국내 DB도 취소 완료 처리되고 재고가 복구된다")
    void processCancelRequestedPayments_AlreadyCanceled() {
        // given
        given(paymentRepository.findByTossOrderIdWithOrder(payment.getTossOrderId())).willReturn(Optional.of(payment));

        payment.changePayStatus(PaymentStatus.CANCEL_UNKNOWN_HOLD);
        order.changeOrderStatus(OrderStatus.CANCEL_REQUESTED);

        // when
        scheduler.processCancelRequestedPayments(payment.getTossOrderId(), "CANCELED");

        // then
        assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);

        // ✨ 핵심: 재고 복구 퍼사드가 정상적으로 호출되었는지 검증
        verify(publisher, times(1)).publishEvent(any(OrderCancelledEvent.class));
    }

    @Test
    @DisplayName("취소 홀드 건 복구 재시도 - 토스 장부가 DONE(취소누락)이면 강제 환불 API를 호출한 뒤 마감한다")
    void processCancelRequestedPayments_RetryCancelSuccess() {
        // given
        given(paymentRepository.findByTossOrderIdWithOrder(payment.getTossOrderId())).willReturn(Optional.of(payment));
        payment.changePayStatus(PaymentStatus.CANCEL_UNKNOWN_HOLD);
        order.changeOrderStatus(OrderStatus.CANCEL_REQUESTED);

        // 토스 취소 API가 정상 성공할 것이라고 모킹
        given(tossPaymentClient.requestCanceled(any())).willReturn(new HashMap<>());

        // when
        scheduler.processCancelRequestedPayments(payment.getTossOrderId(), "DONE");

        // then
        assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);

        // ✨ 핵심: 토스 취소 API와 재고 복구가 순차적으로 강제 실행되었는지 검증
        verify(tossPaymentClient, times(1)).requestCanceled(any());
        verify(publisher, times(1)).publishEvent(any(OrderCancelledEvent.class));
    }

    @Test
    @DisplayName("취소 홀드 건 복구 재시도 - 토스 장부가 DONE(취소누락)일 때 강제 환불 API가 잘못된 요청으로 실패하면 주문과 결제를 결제된 상태로 변경한 뒤 마감한다")
    void processCancelRequestedPayments_RetryCancelFail() {
        // given
        given(paymentRepository.findByTossOrderIdWithOrder(payment.getTossOrderId())).willReturn(Optional.of(payment));
        payment.changePayStatus(PaymentStatus.CANCEL_UNKNOWN_HOLD);
        order.changeOrderStatus(OrderStatus.CANCEL_REQUESTED);

        // 토스 취소 API가 정상 성공할 것이라고 모킹
        given(tossPaymentClient.requestCanceled(any())).willThrow(new AppException(
                PaymentErrorCode.PAYMENT_CANCEL_FAILED));

        // when
        scheduler.processCancelRequestedPayments(payment.getTossOrderId(), "DONE");

        // then
        assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);

    }
}