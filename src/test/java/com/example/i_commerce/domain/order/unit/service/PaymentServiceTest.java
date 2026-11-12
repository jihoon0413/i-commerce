package com.example.i_commerce.domain.order.unit.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.i_commerce.domain.order.entity.Delivery;
import com.example.i_commerce.domain.order.entity.Order;
import com.example.i_commerce.domain.order.entity.OrderProduct;
import com.example.i_commerce.domain.order.entity.Payment;
import com.example.i_commerce.domain.order.entity.emuns.DeliveryStatus;
import com.example.i_commerce.domain.order.entity.emuns.OrderStatus;
import com.example.i_commerce.domain.order.entity.emuns.PaymentStatus;
import com.example.i_commerce.domain.order.event.dto.PaymentApprovedEvent;
import com.example.i_commerce.domain.order.event.dto.PaymentStatusChangedEvent;
import com.example.i_commerce.domain.order.exception.PaymentErrorCode;
import com.example.i_commerce.domain.order.repository.DeliveryRepository;
import com.example.i_commerce.domain.order.repository.OrderRepository;
import com.example.i_commerce.domain.order.repository.PaymentRepository;
import com.example.i_commerce.domain.order.service.PaymentService;
import com.example.i_commerce.domain.order.service.dto.PaymentCancelPreparedDto;
import com.example.i_commerce.domain.order.service.dto.PaymentCancelRequest;
import com.example.i_commerce.domain.order.service.dto.PaymentConfirmPrepareDto;
import com.example.i_commerce.domain.order.service.dto.PaymentConfirmRequest;
import com.example.i_commerce.domain.order.service.dto.PaymentDetailResponse;
import com.example.i_commerce.global.exception.AppException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class PaymentServiceTest {

    @Mock
    PaymentRepository paymentRepository;

    @Mock
    OrderRepository orderRepository;

    @Mock
    ApplicationEventPublisher publisher;

    @Mock
    DeliveryRepository deliveryRepository;

    @InjectMocks
    PaymentService paymentService;

    private Payment payment;
    private Order order;
    String tossOrderId = "toss_order-id";
    String paymentKey = "toss_payment-key";

    @Nested
    @DisplayName("결제 위젯 진입(getPaymentDetails) 테스트")
    class GetPaymentDetailsTest {

        private Long orderId;

        @BeforeEach
        void setUp() {
            orderId = 1L;
        }

        @Test
        @DisplayName("성공: 진행 중인 결제가 없으면 새 READY 결제를 발급한다")
        void success_CreateNewReadyPayment() {
            order = Order.builder()
                    .userId(1L)
                    .orderStatus(OrderStatus.PENDING)
                    .totalPayAmount(10000)
                    .orderProducts(List.of(OrderProduct.builder().productName("상품A").build()))
                    .build();

            given(orderRepository.findById(orderId)).willReturn(Optional.of(order));
            given(paymentRepository.save(any(Payment.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            PaymentDetailResponse response = paymentService.getPaymentDetails(1L, orderId);

            assertThat(response).isNotNull();
            assertThat(order.getPayments().size()).isEqualTo(1);
            assertThat(order.getPayments().getFirst().getPayStatus()).isEqualTo(PaymentStatus.READY);
        }

        @Test
        @DisplayName("성공: READY 결제가 이미 있으면 재사용하고, 더 오래된 READY 결제는 FAILED로 정리한다")
        void success_ReuseReadyPayment_AndFailStaleOnes() {
            Payment staleReady = Payment.builder().id(1L).payStatus(PaymentStatus.READY).build();
            Payment latestReady = Payment.builder().id(2L).payStatus(PaymentStatus.READY).build();

            order = Order.builder()
                    .userId(1L)
                    .orderStatus(OrderStatus.PENDING)
                    .totalPayAmount(10000)
                    .orderProducts(List.of(OrderProduct.builder().productName("상품A").build()))
                    .payments(List.of(staleReady, latestReady))
                    .build();

            given(orderRepository.findById(orderId)).willReturn(Optional.of(order));

            PaymentDetailResponse response = paymentService.getPaymentDetails(1L, orderId);

            assertThat(response).isNotNull();
            assertThat(latestReady.getPayStatus()).isEqualTo(PaymentStatus.READY);
            assertThat(staleReady.getPayStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("실패: 가장 최근 결제가 이미 선점(PROCESSING)되어 있으면 새 결제를 만들지 않고 PAYMENT_ALREADY_PROCESSING 예외가 발생한다")
        void fail_AlreadyProcessing_DoesNotIssueNewPayment() {
            Payment processing = Payment.builder().id(1L).payStatus(PaymentStatus.PROCESSING).build();

            order = Order.builder()
                    .userId(1L)
                    .orderStatus(OrderStatus.PENDING)
                    .totalPayAmount(10000)
                    .orderProducts(List.of(OrderProduct.builder().productName("상품A").build()))
                    .payments(List.of(processing))
                    .build();

            given(orderRepository.findById(orderId)).willReturn(Optional.of(order));

            assertThatThrownBy(() -> paymentService.getPaymentDetails(1L, orderId))
                    .isInstanceOf(AppException.class)
                    .extracting("errorCode")
                    .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSING);

            // 새 결제가 추가로 발급되지 않아야 한다 (이중 결제 시도 방지)
            assertThat(order.getPayments().size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("결제 승인 사전 검증 및 준비 테스트")
    class ValidateAndPrepareConfirmTest {

        private PaymentConfirmRequest requestDto;


        @BeforeEach
        void setUp() {
            order = Order.builder()
                    .userId(1L)
                    .orderProducts(List.of(mock(OrderProduct.class), mock(OrderProduct.class)))
                    .orderStatus(OrderStatus.PENDING)
                    .build();
            payment = Payment.builder()
                    .id(1L)
                    .amount(10000)
                    .pgTid(paymentKey)
                    .tossOrderId(tossOrderId)
                    .payStatus(PaymentStatus.READY)
                    .order(order)
                    .build();
            requestDto = new PaymentConfirmRequest(paymentKey,tossOrderId,10000);
        }

        @Test
        @DisplayName("성공: 결제가 READY 상태이고 금액이 일치하면 선점(PROCESSING)에 성공하고 준비 DTO를 반환한다")
        void success() {
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));
            given(paymentRepository.markProcessingIfReady(
                    payment.getId(), paymentKey, PaymentStatus.READY, PaymentStatus.PROCESSING))
                    .willReturn(1);

            PaymentConfirmPrepareDto result = paymentService.validateAndPrepareConfirm(requestDto);

            assertThat(result).isNotNull();
            assertThat(result.commands().size()).isEqualTo(2);
        }

        @Test
        @DisplayName("실패: 동시에 들어온 다른 요청이 이미 선점(PROCESSING)에 성공했다면 PAYMENT_ALREADY_PROCESSING 예외가 발생한다")
        void fail_AlreadyProcessing_ConcurrentClaim() {
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));
            // 조건부 업데이트의 영향받은 행이 0건 -> 다른 요청이 먼저 READY->PROCESSING 전이에 성공한 상황
            given(paymentRepository.markProcessingIfReady(
                    payment.getId(), paymentKey, PaymentStatus.READY, PaymentStatus.PROCESSING))
                    .willReturn(0);

            assertThatThrownBy(() -> paymentService.validateAndPrepareConfirm(requestDto))
                    .isInstanceOf(AppException.class)
                    .extracting("errorCode")
                    .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSING);
        }

        @Test
        @DisplayName("실패: 결제 데이터가 존재하지 않으면 PAYMENT_NOT_FOUND 예외가 발생한다")
        void fail_NotFound() {
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.validateAndPrepareConfirm(requestDto))
                    .isInstanceOf(AppException.class)
                    .hasMessage(PaymentErrorCode.PAYMENT_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("실패: 결제 상태가 READY가 아니면 INVALID_PAYMENT_STATUS 예외가 발생한다")
        void fail_InvalidStatus() {
            ReflectionTestUtils.setField(payment, "payStatus", PaymentStatus.PAID);
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));

            assertThatThrownBy(() -> paymentService.validateAndPrepareConfirm(requestDto))
                    .isInstanceOf(AppException.class)
                    .hasMessage(PaymentErrorCode.INVALID_PAYMENT_STATUS.getMessage());
        }

        @Test
        @DisplayName("실패: 요청 금액과 DB의 결제 금액이 다르면 INVALID_PAYMENT_AMOUNT 예외가 발생한다")
        void fail_InvalidAmount() {
            ReflectionTestUtils.setField(payment, "amount", 5000);
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));

            assertThatThrownBy(() -> paymentService.validateAndPrepareConfirm(requestDto))
                    .isInstanceOf(AppException.class)
                    .extracting("errorCode")
                    .isEqualTo(PaymentErrorCode.INVALID_PAYMENT_AMOUNT);
        }
    }
    //------------------------

    //------------------------

    @Nested
    @DisplayName("결제 취소 사전 검증 및 준비 테스트")
    class ValidateAndPrepareCancelTest {

        private PaymentCancelRequest cancelRequestDto;

        @BeforeEach
        void setUp() {
            order = Order.builder()
                    .userId(1L)
                    .orderProducts(List.of(mock(OrderProduct.class), mock(OrderProduct.class)))
                    .orderStatus(OrderStatus.CONFIRMED)
                    .build();
            payment = Payment.builder()
                    .id(1L)
                    .amount(10000)
                    .pgTid(paymentKey)
                    .tossOrderId(tossOrderId)
                    .cancelableAmount(10000)
                    .payStatus(PaymentStatus.PAID)
                    .order(order)
                    .build();
            cancelRequestDto = new PaymentCancelRequest(tossOrderId, 5000, paymentKey, "고객변심");
        }

        @Test
        @DisplayName("성공: 모든 취소 조건(키 일치, 상태, 금액, 배송상태)을 만족하면 준비 DTO를 반환하고 이벤트를 발행한다")
        void success() {
            // given
            Delivery delivery1 = mock(Delivery.class);
            Delivery delivery2 = mock(Delivery.class);
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));
            given(deliveryRepository.findAllByOrderId(order.getId())).willReturn(List.of(delivery1, delivery2));
            given(delivery1.getDeliveryStatus()).willReturn(DeliveryStatus.PREPARING);
            given(delivery2.getDeliveryStatus()).willReturn(DeliveryStatus.PREPARING);

            // when
            PaymentCancelPreparedDto result = paymentService.validateAndPrepareCancel(cancelRequestDto);

            // then
            assertThat(result).isNotNull();
            assertThat(result.tossOrderId()).isEqualTo(tossOrderId);
        }

        @Test
        @DisplayName("실패: 토스 pgTid 키가 일치하지 않으면 INVALID_PAYMENT_KEY 예외가 발생한다")
        void fail_InvalidKey() {
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));

            PaymentCancelRequest wrongRequest = new PaymentCancelRequest(tossOrderId, 1000, "wrong_payment_key", "고객변심");

            assertThatThrownBy(() -> paymentService.validateAndPrepareCancel(wrongRequest))
                    .isInstanceOf(AppException.class)
                    .extracting("errorCode")
                    .isEqualTo(PaymentErrorCode.INVALID_PAYMENT_KEY);
        }

        @Test
        @DisplayName("실패: 배송 상태가 하나라도 PREPARING이 아니면 ALREADY_SHIPPED 예외가 발생한다")
        void fail_AlreadyShipped() {
            Delivery delivery1 = mock(Delivery.class);
            Delivery delivery2 = mock(Delivery.class);
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));
            given(deliveryRepository.findAllByOrderId(order.getId())).willReturn(List.of(delivery1, delivery2));

            given(delivery1.getDeliveryStatus()).willReturn(DeliveryStatus.PREPARING);
            given(delivery2.getDeliveryStatus()).willReturn(DeliveryStatus.SHIPPING);


            // when & then
            assertThatThrownBy(() -> paymentService.validateAndPrepareCancel(cancelRequestDto))
                    .isInstanceOf(AppException.class)
                    .extracting("errorCode")
                    .isEqualTo(PaymentErrorCode.ALREADY_SHIPPED);
        }
    }
    //------------------------

    //------------------------

    @Nested
    @DisplayName("상태 변경 및 확정 비즈니스 로직 테스트")
    class CompleteAndStatusChangeTest {

        @BeforeEach
        void setUp() {
            order = Order.builder()
                    .userId(1L)
                    .orderProducts(List.of(mock(OrderProduct.class), mock(OrderProduct.class)))
                    .orderStatus(OrderStatus.CONFIRMED)
                    .build();
            payment = Payment.builder()
                    .id(1L)
                    .amount(10000)
//                    .pgTid(paymentKey)
                    .tossOrderId(tossOrderId)
                    .cancelableAmount(10000)
                    .payStatus(PaymentStatus.PAID)
                    .order(order)
                    .build();
        }

        @Test
        @DisplayName("결제 승인 완료 성공 시 엔티티 상태를 변경하고 승인 및 상태 변경 이벤트를 발행한다")
        void completePaymentSuccess() {
            // given
            // validateAndPrepareConfirm()의 선점(claim)을 거쳐 온 결제이므로 직전 상태는 PROCESSING이다.
            ReflectionTestUtils.setField(payment, "payStatus", PaymentStatus.PROCESSING);
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));

            // when
            paymentService.completePaymentSuccess(tossOrderId, paymentKey, PaymentStatus.PROCESSING, "{}");

            // then
            verify(publisher).publishEvent(any(PaymentApprovedEvent.class));
            verify(publisher).publishEvent(any(PaymentStatusChangedEvent.class));

            assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
           }

        @Test
        @DisplayName("결제 취소 성공 시 엔티티의 환불 금액을 차감하고 주문 상태를 CANCELLED로 변경한다")
        void completeCancelSuccess() {
            // given
            PaymentCancelRequest cancelDto = new PaymentCancelRequest(tossOrderId, 10000, paymentKey, "변심");
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));

            // when
            paymentService.completeCancelSuccess(cancelDto, paymentKey, "{}");

            // then
            verify(publisher).publishEvent(any(PaymentStatusChangedEvent.class));

            assertThat(payment.getCancelableAmount()).isEqualTo(0);
            assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("외부 API 결과에 따른 상태 반영 및 예외 핸들링 테스트")
    class ExternalResultHandlingTest {

        @BeforeEach
        void setUp() {
            order = Order.builder()
                    .userId(1L)
                    .orderProducts(List.of(mock(OrderProduct.class), mock(OrderProduct.class)))
                    .build();
            payment = Payment.builder()
                    .id(1L)
                    .amount(10000)
                    .pgTid(paymentKey)
                    .tossOrderId(tossOrderId)
                    .cancelableAmount(10000)
                    .order(order)
                    .build();
        }

        @Test
        @DisplayName("completePaymentCancel: 재고 부족으로 인한 취소 발생 시 실패 이벤트를 정상 발행한다")
        void completePaymentCancel_Success() {
            Long paymentId = 1L;

            given(paymentRepository.findById(paymentId)).willReturn(Optional.of(payment));

            paymentService.completePaymentCancel(payment.getId(), PaymentStatus.READY, paymentKey, "{}");

            verify(publisher).publishEvent(any(PaymentStatusChangedEvent.class));
        }

        @Test
        @DisplayName("changeStatusToFailed: 결제 실패 확정 시 엔티티의 결제 상태를 FAILED로 변경한다")
        void changeStatusToFailed_Success() {
            Long paymentId = 1L;
            given(paymentRepository.findById(paymentId)).willReturn(Optional.of(payment));

            paymentService.changeStatusToFailed(paymentId);

            assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("handleTimeoutSuccess: 승인 타임아웃 대피소 성공 시 결제 및 주문 상태를 UNKNOWN_HOLD로 변경한다")
        void handleTimeoutSuccess_Success() {
            // given
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));

            // when
            paymentService.handleTimeoutSuccess(tossOrderId);

            // then
            assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.UNKNOWN_HOLD);
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.UNKNOWN_HOLD);
        }

        @Test
        @DisplayName("handleTimeoutFailed: 승인 타임아웃 대피소 실패 시 결제는 FAILED, 주문은 CANCELLED로 변경한다")
        void handleTimeoutFailed_Success() {
            // given
            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));

            // when
            paymentService.handleTimeoutFailed(tossOrderId);

            // then
            assertThat(payment.getPayStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("handleCancelTimeout: 취소 타임아웃 발생 시 결제 취소 준비를 수행하고 주문은 CANCEL_REQUESTED, 배송은 DELIVERY_HOLD로 변경한다")
        void handleCancelTimeout_Success() {
            Delivery delivery = Delivery.builder()
                    .deliveryStatus(DeliveryStatus.PREPARING)
                    .build();
            int cancelAmount = 5000;
            ReflectionTestUtils.setField(order, "deliveries", List.of(delivery));
            String cancelReason = "고객 변심 타임아웃";

            given(paymentRepository.findByTossOrderIdWithOrder(tossOrderId)).willReturn(Optional.of(payment));

            paymentService.handleCancelTimeout(tossOrderId, cancelAmount, cancelReason);

            assertThat(payment.getCancelAmount()).isEqualTo(cancelAmount);
            assertThat(payment.getCancelReason()).isEqualTo(cancelReason);
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERY_HOLD);
        }
    }
}
