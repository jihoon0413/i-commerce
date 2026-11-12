package com.example.i_commerce.domain.order.exception;

import com.example.i_commerce.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    INVALID_PAYMENT_AMOUNT(HttpStatus.BAD_REQUEST, "PAY-40001", "결제 금액이 일치하지 않습니다."),
    PAYMENT_CONFIRM_FAILED(HttpStatus.BAD_REQUEST, "PAY-40002", "결제 승인 처리에 실패했습니다."),
    INVALID_PAYMENT_STATUS(HttpStatus.BAD_REQUEST, "PAY-40003", "결제 가능한 상태가 아닙니다."),
    PAYMENT_CANCEL_FAILED(HttpStatus.BAD_REQUEST, "PAY-40004","결제 취소 처리에 실패했습니다."),
    INVALID_PAYMENT_KEY(HttpStatus.BAD_REQUEST, "PAY-40005", "paymentKey가 일치하지 않습니다."),
    INVALID_CANCEL_AMOUNT(HttpStatus.BAD_REQUEST, "PAY-40006", "취소 금액이 일치하지 않습니다."),
    PAYMENT_ALREADY_CANCELLED(HttpStatus.BAD_REQUEST, "PAY-40007", "이미 취소된 결제입니다."),
    PAYMENT_CANCEL_IMPOSSIBLE_ALREADY_SHIPPED(HttpStatus.BAD_REQUEST, "PAY-40008", "이미 상품이 출고(배송)되어 결제 취소가 불가능합니다."),
    INVALID_PAYMENT_REQUEST(HttpStatus.BAD_REQUEST, "PAY-40009", "유효하지 않은 결제 요청입니다."),
    ALREADY_SHIPPED(HttpStatus.BAD_REQUEST, "PAY-40010", "이미 배송이 시작되어 즉시 취소가 불가능합니다. 반품 절차를 이용해 주세요."),
    PAYMENT_ALREADY_PROCESSING(HttpStatus.CONFLICT, "PAY-40011", "이미 처리 중인 결제 요청입니다."),

    ACCESS_DENIED(HttpStatus.FORBIDDEN, "PAY-40301", "접근 권한이 없습니다."),

    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY-40401", "결제 정보를 찾을 수 없습니다."),

    PAYMENT_UNKNOWN_HOLD(HttpStatus.INTERNAL_SERVER_ERROR, "PAY-5001", "결제 상태 확인이 지연되고 있습니다. 고객센터 확인이 필요합니다."),

    PAYMENT_NETWORK_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "PAY-50401", "결제 처리 중 네트워크 타임아웃이 발생했습니다. 결제 결과를 확인 중입니다.")
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
