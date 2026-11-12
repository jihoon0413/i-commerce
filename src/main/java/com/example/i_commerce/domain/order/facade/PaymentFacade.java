package com.example.i_commerce.domain.order.facade;

import com.example.i_commerce.domain.order.entity.emuns.PaymentStatus;
import com.example.i_commerce.domain.order.exception.PaymentErrorCode;
import com.example.i_commerce.domain.order.service.AutoPaymentCancelService;
import com.example.i_commerce.domain.order.service.PaymentService;
import com.example.i_commerce.domain.order.service.dto.PaymentCancelPreparedDto;
import com.example.i_commerce.domain.order.service.dto.PaymentConfirmPrepareDto;
import com.example.i_commerce.domain.order.service.dto.PaymentCancelRequest;
import com.example.i_commerce.domain.order.service.dto.PaymentConfirmRequest;
import com.example.i_commerce.domain.product.event.OrderCancelledEvent;
import com.example.i_commerce.domain.product.event.OrderCompletedEvent;
import com.example.i_commerce.domain.product.exception.ProductErrorCode;
import com.example.i_commerce.global.exception.AppException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentService paymentService;
    private final AutoPaymentCancelService autoPaymentCancelService;
    private final ApplicationEventPublisher publisher;

    public void confirmPayment(PaymentConfirmRequest dto) {

        PaymentConfirmPrepareDto target = paymentService.validateAndPrepareConfirm(dto);
        // validateAndPrepareConfirm()에서 이미 READY -> PROCESSING으로 선점했으므로,
        // 이후 상태 변화 기록의 직전 상태는 PROCESSING이다.
        PaymentStatus previousStatus = PaymentStatus.PROCESSING;

        try {
            Map<String, Object> response = paymentService.requestConfirm(dto);
            String pgTid = (String) response.get("paymentKey");

            try {
                publisher.publishEvent(new OrderCompletedEvent(target.commands()));
                paymentService.completePaymentSuccess(target.tossOrderId(), pgTid, previousStatus, response.toString());
            } catch (AppException e) {
                autoPaymentCancelService.autoCancelPayment(new PaymentCancelRequest(target.tossOrderId(), dto.amount(), pgTid, "재고 부족으로 인한 자동 취소"));
                paymentService.completePaymentCancel(target.paymentId(), previousStatus, pgTid, response.toString());
                throw e;
            }

        } catch (AppException e) {
            if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NOT_FOUND ||
                    e.getErrorCode() == PaymentErrorCode.PAYMENT_CONFIRM_FAILED) {
                paymentService.changeStatusToFailed(target.paymentId());
                throw new AppException(PaymentErrorCode.PAYMENT_CONFIRM_FAILED);
            }

            if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NETWORK_TIMEOUT) {
                paymentService.handleTimeoutSuccess(target.tossOrderId());
                throw new AppException(PaymentErrorCode.PAYMENT_UNKNOWN_HOLD);
            }

            if(e.getErrorCode() == ProductErrorCode.INSUFFICIENT_STOCK) {
                throw e;
            }
            throw new AppException(PaymentErrorCode.PAYMENT_UNKNOWN_HOLD);
        }

    }

    public void cancelPayment(PaymentCancelRequest dto) {

        PaymentCancelPreparedDto target = paymentService.validateAndPrepareCancel(dto);

        try{
            Map<String, Object> response = paymentService.requestCanceled(dto);
            String pgTid = (String) response.get("paymentKey");

            paymentService.completeCancelSuccess(dto, pgTid, response.toString());

            //TODO: 재고 원복 실패했을 때 처리 방법 고민해보기
            publisher.publishEvent(new OrderCancelledEvent(target.orderId()));
        } catch (AppException e) {
            if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NETWORK_TIMEOUT) {
                log.error("[취소 비상] 결제 취소 중 타임아웃 발생. 토스 장부 확인 불가.");
                paymentService.handleCancelTimeout(target.tossOrderId(), dto.cancelAmount(), dto.cancelReason());
                throw new AppException(PaymentErrorCode.PAYMENT_UNKNOWN_HOLD);
            }

            paymentService.failCancel(target.tossOrderId());
            throw e;
        }
    }
}
