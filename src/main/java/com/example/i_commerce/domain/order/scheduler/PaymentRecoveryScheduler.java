package com.example.i_commerce.domain.order.scheduler;

import com.example.i_commerce.domain.order.client.PaymentClient;
import com.example.i_commerce.domain.order.entity.Order;
import com.example.i_commerce.domain.order.entity.OrderProduct;
import com.example.i_commerce.domain.order.entity.Payment;
import com.example.i_commerce.domain.order.entity.emuns.DeliveryStatus;
import com.example.i_commerce.domain.order.entity.emuns.OrderStatus;
import com.example.i_commerce.domain.order.entity.emuns.PaymentStatus;
import com.example.i_commerce.domain.order.exception.PaymentErrorCode;
import com.example.i_commerce.domain.order.repository.PaymentRepository;
import com.example.i_commerce.domain.order.service.dto.PaymentCancelRequest;
import com.example.i_commerce.domain.product.application.dto.StockDeductCommand;
import com.example.i_commerce.domain.product.event.OrderCancelledEvent;
import com.example.i_commerce.domain.product.event.OrderCompletedEvent;
import com.example.i_commerce.global.exception.AppException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Profile("!k6")
@Component
@RequiredArgsConstructor
public class PaymentRecoveryScheduler {

    private static final long PROCESSING_STUCK_THRESHOLD_MINUTES = 2;

    private final PaymentRepository paymentRepository;
    private final PaymentClient tossPaymentClient;
    private final ApplicationEventPublisher publisher;
    private final ObjectProvider<PaymentRecoveryScheduler> selfProvider;

    @Scheduled(fixedDelay = 300000)
    public void recoverUnresolvedPayments() {
        recoverStuckProcessingPayments();
        recoverUnknownHoldPayments();
        recoverCancelRequestedPayments();
    }

    // 결제 선점(PROCESSING) 후 일정 시간이 지나도 끝나지 않은 결제를 복구한다.
    // 서버가 PG 승인 호출 중/직후에 죽었을 수 있으므로, 시간이 지났다는 이유만으로
    // READY로 되돌리지 않고 PG(토스) 측 실제 상태를 조회해 확정한다.
    public void recoverStuckProcessingPayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(PROCESSING_STUCK_THRESHOLD_MINUTES);
        List<Payment> paymentList = paymentRepository.findAllByPayStatusAndUpdatedAtBefore(
            PaymentStatus.PROCESSING, threshold);

        for (Payment payment : paymentList) {
            try {
                Map<String, Object> response = tossPaymentClient.checkPaymentStatus(payment.getPgTid());
                String status = (String) response.get("status");

                PaymentRecoveryScheduler self = selfProvider.getObject();
                self.processStuckProcessingPayment(payment.getTossOrderId(), status);
            } catch (AppException e) {
                if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NOT_FOUND) {
                    // PG에도 결제 시도 흔적이 없다면 서버가 PG 호출 전에 죽은 것이므로 실패 처리한다.
                    PaymentRecoveryScheduler self = selfProvider.getObject();
                    self.processStuckProcessingFailure(payment.getTossOrderId());
                    continue;
                }
                log.error("[결제 중(PROCESSING) 복구 실패] 주문 ID: {} 처리 중 외부 API 오류 발생. 다음 주기에 재시도합니다.",
                    payment.getOrder().getId(), e);
            }
        }
    }

    public void recoverUnknownHoldPayments() {
        List<Payment> paymentList = paymentRepository.findAllByPayStatus(PaymentStatus.UNKNOWN_HOLD);

        for(Payment payment : paymentList) {
            try{
                Map<String, Object> response = tossPaymentClient.checkPaymentStatus(payment.getPgTid());
                String status = (String) response.get("status");

                PaymentRecoveryScheduler self = selfProvider.getObject();
                self.processUnknownHoldPayments(payment.getTossOrderId(), status);
            } catch (AppException e) {
                if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NOT_FOUND) {
                    PaymentRecoveryScheduler self = selfProvider.getObject();
                    self.processUnknownHoldFailure(payment.getTossOrderId());
                    continue;
                }
                log.error("[취소 복구 실패] 주문 ID: {} 처리 중 외부 API 오류 발생. 다음 주기에 재시도합니다.", payment.getOrder().getId(), e);
            }

        }
    }

    public void recoverCancelRequestedPayments() {
        List<Payment> paymentList = paymentRepository.findAllByPayStatus(PaymentStatus.CANCEL_UNKNOWN_HOLD);

        for(Payment payment : paymentList) {
            try{
                Map<String, Object> response = tossPaymentClient.checkPaymentStatus(payment.getPgTid());
                String status = (String) response.get("status");
                PaymentRecoveryScheduler self = selfProvider.getObject();
                self.processCancelRequestedPayments(payment.getTossOrderId(), status);
            } catch (Exception e) {
                log.error("[취소 복구 실패] 주문 ID: {} 처리 중 외부 API 오류 발생. 다음 주기에 재시도합니다.", payment.getOrder().getId(), e);
            }

        }
    }

    @Transactional
    public void processStuckProcessingPayment(String tossOrderId, String status) {
        Payment payment = paymentRepository.findByTossOrderIdWithOrder(tossOrderId).orElseThrow(() ->
                new AppException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        Order order = payment.getOrder();

        if ("DONE".equals(status)) {
            publisher.publishEvent(new OrderCompletedEvent(toStockDeductCommands(order)));
            payment.completePayment(payment.getPgTid());
            order.changeOrderStatus(OrderStatus.CONFIRMED);
        } else if ("IN_PROGRESS".equals(status) || "WAITING_FOR_DEPOSIT".equals(status)) {
            // 아직 PG 쪽에서도 결론이 나지 않은 상태라면 다음 주기에 다시 확인한다.
            log.warn("[결제 중(PROCESSING) 복구 보류] 토스 주문 ID: {} 상태가 {} 입니다. 다음 주기에 재확인합니다.",
                tossOrderId, status);
        } else {
            payment.changePayStatus(PaymentStatus.FAILED);
            order.changeOrderStatus(OrderStatus.CANCELLED);
        }
    }

    @Transactional
    public void processStuckProcessingFailure(String tossOrderId) {
        Payment payment = paymentRepository.findByTossOrderIdWithOrder(tossOrderId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        Order order = payment.getOrder();

        log.error("[결제 중(PROCESSING) 복구 실패] 토스 주문 ID: {} 결제 정보가 토스에 존재하지 않습니다. 주문 상태를 실패로 변경합니다.",
            tossOrderId);
        payment.changePayStatus(PaymentStatus.FAILED);
        order.changeOrderStatus(OrderStatus.CANCELLED);
        publisher.publishEvent(new OrderCancelledEvent(order.getId()));
    }

    @Transactional
    public void processUnknownHoldPayments(String tossOrderId, String status) {

        Payment payment = paymentRepository.findByTossOrderIdWithOrder(tossOrderId).orElseThrow(() ->
                new AppException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        Order order = payment.getOrder();

        if("DONE".equals(status)) {
            publisher.publishEvent(new OrderCompletedEvent(toStockDeductCommands(order)));
            payment.completePayment(payment.getPgTid());
            order.changeOrderStatus(OrderStatus.CONFIRMED);
        } else {
            payment.changePayStatus(PaymentStatus.FAILED);
            order.changeOrderStatus(OrderStatus.CANCELLED);
        }
    }

    @Transactional
    public void processCancelRequestedPayments(String tossOrderId, String status) {
        Payment payment = paymentRepository.findByTossOrderIdWithOrder(tossOrderId).orElseThrow(() ->
                new AppException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        Order order = payment.getOrder();

        if("CANCELED".equals(status)) {
            payment.changePayStatus(PaymentStatus.CANCELLED);
            order.changeOrderStatus(OrderStatus.CANCELLED);
            publisher.publishEvent(new OrderCancelledEvent(order.getId()));
        } else if("DONE".equals(status)){
            PaymentCancelRequest dto = new PaymentCancelRequest(
                    payment.getTossOrderId(),
                    payment.getCancelAmount(),
                    payment.getPgTid(),
                    payment.getCancelReason()
            );
            try {
                // TODO: 트랜잭션 분리
                tossPaymentClient.requestCanceled(dto);
                payment.changePayStatus(PaymentStatus.CANCELLED);
                order.changeOrderStatus(OrderStatus.CANCELLED);
                publisher.publishEvent(new OrderCancelledEvent(order.getId()));
            } catch (AppException e) {
                if (e.getErrorCode() == PaymentErrorCode.PAYMENT_CANCEL_FAILED) {
                    log.error("[취소 재시도 거절] 토스에서 취소 요청을 거절했습니다. 원래 주문 상태로 원복합니다. 주문 ID: {}", order.getId());

                    payment.changePayStatus(PaymentStatus.PAID);
                    order.changeOrderStatus(OrderStatus.CONFIRMED);
                    order.getDeliveries().forEach(d -> d.changeDeliveryStatus(DeliveryStatus.PREPARING));
                }

                if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NETWORK_TIMEOUT) {
                    log.error("[취소 재시도 타임아웃] 토스 API 서버가 여전히 먹통입니다. 다음 주기를 기약합니다. 주문 ID: {}", order.getId());
                }


            }
        }
    }

    @Transactional // 💡 새 트랜잭션을 열어 지연 로딩 에러를 원천 차단하고 더티 체킹을 보장합니다.
    public void processUnknownHoldFailure(String tossOrderId) {
        Payment payment = paymentRepository.findByTossOrderIdWithOrder(tossOrderId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        Order order = payment.getOrder();

        log.error("[결제 복구 실패] 토스 주문 ID: {} 결제 정보가 토스에 존재하지 않습니다. 주문 상태를 실패로 변경합니다.", tossOrderId);
        payment.changePayStatus(PaymentStatus.FAILED);
        order.changeOrderStatus(OrderStatus.CANCELLED);
        publisher.publishEvent(new OrderCancelledEvent(order.getId()));
    }

    private List<StockDeductCommand> toStockDeductCommands(Order order) {
        return order.getOrderProducts().stream()
                .map(orderProduct -> new StockDeductCommand(
                        orderProduct.getProductSkuId(),
                        orderProduct.getCount(),
                        order.getId()))
                .toList();
    }
}
