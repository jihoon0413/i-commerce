package com.example.i_commerce.domain.order.unit.facade;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.example.i_commerce.domain.order.entity.Payment;
import com.example.i_commerce.domain.order.entity.emuns.PaymentStatus;
import com.example.i_commerce.domain.order.exception.PaymentErrorCode;
import com.example.i_commerce.domain.order.facade.PaymentFacade;
import com.example.i_commerce.domain.order.repository.PaymentRepository;
import com.example.i_commerce.domain.order.service.AutoPaymentCancelService;
import com.example.i_commerce.domain.order.service.PaymentService;
import com.example.i_commerce.domain.order.service.dto.PaymentCancelPreparedDto;
import com.example.i_commerce.domain.order.service.dto.PaymentCancelRequest;
import com.example.i_commerce.domain.order.service.dto.PaymentConfirmPrepareDto;
import com.example.i_commerce.domain.order.service.dto.PaymentConfirmRequest;
import com.example.i_commerce.domain.product.event.OrderCancelledEvent;
import com.example.i_commerce.domain.product.event.OrderCompletedEvent;
import com.example.i_commerce.domain.product.exception.ProductErrorCode;
import com.example.i_commerce.global.exception.AppException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PaymentFacadeTest {

    @Mock
    PaymentService paymentService;

    @Mock
    PaymentRepository paymentRepository;

    @Mock
    AutoPaymentCancelService autoPaymentCancelService;

    @Mock
    ApplicationEventPublisher publisher;

    @InjectMocks
    PaymentFacade paymentFacade;

    String tossOrderId = "toss_order-id";
    String paymentKey = "toss_payment-key";
    private PaymentConfirmRequest confirmRequestDto;
    private PaymentConfirmPrepareDto confirmPrepareDto;
    private PaymentCancelRequest cancelRequestDto;
    private PaymentCancelPreparedDto cancelPreparedDto;

    @BeforeEach
    void setUp() {
        Long paymentId = 1L;
        Long orderId = 1L;
        confirmRequestDto = new PaymentConfirmRequest(paymentKey, tossOrderId, 1000);
        confirmPrepareDto = new PaymentConfirmPrepareDto(paymentId, orderId, tossOrderId, null);
        cancelRequestDto = new PaymentCancelRequest(tossOrderId, 1000, paymentKey, "단순 변심");
        cancelPreparedDto = new PaymentCancelPreparedDto(tossOrderId, orderId);
    }

    @Test
    @DisplayName("결제 승인 성공: 외부 API가 정상 응답하고 재고가 차감되면 최종 결제가 확정된다")
    void confirmPayment_Success() {

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("paymentKey", paymentKey);

        given(paymentService.validateAndPrepareConfirm(confirmRequestDto)).willReturn(confirmPrepareDto);
        given(paymentService.requestConfirm(confirmRequestDto)).willReturn(responseBody);

        paymentFacade.confirmPayment(confirmRequestDto);

        // validateAndPrepareConfirm()에서 이미 READY -> PROCESSING 선점이 끝난 상태이므로
        // 완료 처리 시점의 직전 상태는 PROCESSING이다.
        verify(paymentService).completePaymentSuccess(tossOrderId, paymentKey, PaymentStatus.PROCESSING, responseBody.toString());
        verify(publisher).publishEvent(any(OrderCompletedEvent.class));
    }

    @Test
    @DisplayName("결제 승인 실패: 토스 API 성공 후 재고 부족 시 자동 취소 API를 호출하고 결제 실패 상태를 기록한다")
    void confirmPayment_Fail_OutOfStock() {
        Map<String, Object> responseBody = new HashMap<>();
        Payment mockPayment = Payment.builder().build();
        responseBody.put("paymentKey", tossOrderId);

        given(paymentService.validateAndPrepareConfirm(confirmRequestDto)).willReturn(confirmPrepareDto);
        given(paymentService.requestConfirm(confirmRequestDto)).willReturn(responseBody);
        doThrow(new AppException(ProductErrorCode.INSUFFICIENT_STOCK)).when(publisher).publishEvent(any(OrderCompletedEvent.class));

        assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequestDto))
                .isInstanceOf(AppException.class)
                .hasMessage(ProductErrorCode.INSUFFICIENT_STOCK.getMessage());


        verify(autoPaymentCancelService).autoCancelPayment(any());
    }


    @Test
    @DisplayName("타임아웃 대피소 작동: 토스 API 호출 중 타임아웃 발생 시 재고 차감을 시도하고 결제 보류(UNKNOWN_HOLD)로 진입한다")
    void confirmPayment_Timeout_SafeRoute_Success() {
        given(paymentService.validateAndPrepareConfirm(confirmRequestDto)).willReturn(confirmPrepareDto);
        doThrow(new AppException(PaymentErrorCode.PAYMENT_NETWORK_TIMEOUT)).when(paymentService).requestConfirm(confirmRequestDto);

        assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequestDto))
                .isInstanceOf(AppException.class)
                .hasMessage(PaymentErrorCode.PAYMENT_UNKNOWN_HOLD.getMessage());

        verify(publisher).publishEvent(any(OrderCompletedEvent.class));
        verify(paymentService).handleTimeoutSuccess(confirmRequestDto.tossOrderId());
    }

    @Test
    @DisplayName("타임아웃 대피소 비상: 타임아웃 대피 중 재고까지 부족하면 토스 장부 망취소를 호출하고 결제 실패를 기록한다")
    void confirmPayment_Timeout_SafeRoute_Fail_OutOfStock() {
        given(paymentService.validateAndPrepareConfirm(confirmRequestDto)).willReturn(confirmPrepareDto);
        doThrow(new AppException(PaymentErrorCode.PAYMENT_NETWORK_TIMEOUT)).when(paymentService).requestConfirm(confirmRequestDto);
        doThrow(new AppException(ProductErrorCode.INSUFFICIENT_STOCK)).when(publisher).publishEvent(any(OrderCompletedEvent.class));

        assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequestDto))
                .isInstanceOf(AppException.class)
                .hasMessage(ProductErrorCode.INSUFFICIENT_STOCK.getMessage());

        verify(paymentService).requestCanceled(any(PaymentCancelRequest.class));
        verify(paymentService).handleTimeoutFailed(confirmRequestDto.tossOrderId());
    }

    //----------------------------------------
    // 결제 취소 테스트
    //----------------------------------------
    @Test
    @DisplayName("결제 취소 성공: 상태가 CANCEL로 변경된다")
    void cancelPayment_Success() {
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("paymentKey", paymentKey);

        given(paymentService.validateAndPrepareCancel(cancelRequestDto)).willReturn(cancelPreparedDto);
        given(paymentService.requestCanceled(cancelRequestDto)).willReturn(responseBody);

        paymentFacade.cancelPayment(cancelRequestDto);

        verify(paymentService).completeCancelSuccess(cancelRequestDto, paymentKey, responseBody.toString());
        verify(publisher).publishEvent(any(OrderCancelledEvent.class));

    }

    @Test
    @DisplayName("타임아웃 대피소 작동: 토스 API 호출 중 타임아웃 발생 시 결제 취소 보류(CANCEL_UNKNOWN_HOLD)로 진입한다")
    void cancelPayment_Fail_InvalidDeliveryStatus() {
        given(paymentService.validateAndPrepareCancel(cancelRequestDto)).willReturn(cancelPreparedDto);
        doThrow(new AppException(PaymentErrorCode.PAYMENT_NETWORK_TIMEOUT)).when(paymentService).requestCanceled(cancelRequestDto);

        assertThatThrownBy(() -> paymentFacade.cancelPayment(cancelRequestDto))
                .isInstanceOf(AppException.class)
                .hasMessage(PaymentErrorCode.PAYMENT_UNKNOWN_HOLD.getMessage());

        verify(paymentService).handleCancelTimeout(cancelRequestDto.tossOrderId(), cancelRequestDto.cancelAmount(), cancelRequestDto.cancelReason());

    }
//
//    @Test
//    @DisplayName("결제 취소 실패: 외부 API에러로 인해 실패가 발생한다.")
//    void cancelPayment_Fail_FiledTossPayments() {}
}