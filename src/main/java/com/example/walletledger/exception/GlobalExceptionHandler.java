package com.example.walletledger.exception;

import com.example.walletledger.controller.dto.response.ApiResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;

/**
 * 전역 예외 처리기.
 *
 * 모든 예외를 공통 API 응답 포맷으로 변환해
 * 컨트롤러별 예외 응답 형식이 달라지는 문제를 방지한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 예외를 처리한다.
     *
     * 서비스 계층에서 발생한 도메인 예외를 상태 코드와 함께
     * 일관된 실패 응답 구조로 변환한다.
     */
    @ExceptionHandler(WalletBusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(WalletBusinessException ex) {
        return ResponseEntity
            .status(ex.getErrorCode().getHttpStatus())
            .body(ApiResponse.failure(ex.getErrorCode().name(), ex.getMessage()));
    }

    /**
     * 요청 본문(@Valid) 검증 실패를 처리한다.
     *
     * 필드별 오류를 "필드: 사유" 형태로 묶어 반환해
     * 클라이언트가 어떤 입력이 잘못되었는지 즉시 확인할 수 있게 한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(this::formatFieldError)
            .collect(Collectors.joining(", "));

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.failure("VALIDATION_ERROR", "요청 값 검증에 실패했습니다.", detail));
    }

    /**
     * 경로 변수/헤더 등 메서드 파라미터 검증 실패를 처리한다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations()
            .stream()
            .map(this::formatConstraintViolation)
            .collect(Collectors.joining(", "));

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.failure("VALIDATION_ERROR", "요청 파라미터 검증에 실패했습니다.", detail));
    }

    /**
     * 필수 헤더 누락을 처리한다.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestHeaderException(MissingRequestHeaderException ex) {
        String detail = "누락 헤더: " + ex.getHeaderName();
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.failure("MISSING_HEADER", "필수 요청 헤더가 누락되었습니다.", detail));
    }

    /**
     * 파라미터 타입 불일치 오류를 처리한다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        String detail = ex.getName() + " 파라미터 타입이 올바르지 않습니다.";
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.failure("TYPE_MISMATCH", "요청 파라미터 타입이 올바르지 않습니다.", detail));
    }

    /**
     * 처리되지 않은 예외를 처리한다.
     *
     * 내부 구현 정보 노출을 막기 위해 상세 스택 정보는 응답에 포함하지 않는다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknownException(Exception ex) {
        return ResponseEntity
            .status(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
            .body(ApiResponse.failure(
                ErrorCode.INTERNAL_SERVER_ERROR.name(),
                ErrorCode.INTERNAL_SERVER_ERROR.getDefaultMessage()
            ));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private String formatConstraintViolation(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}
