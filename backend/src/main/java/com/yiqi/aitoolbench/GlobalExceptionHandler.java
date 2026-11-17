package com.yiqi.aitoolbench;

import com.yiqi.aitoolbench.sub2api.Sub2ApiException;

import com.yiqi.aitoolbench.ai.ProviderGatewayException;
import com.yiqi.aitoolbench.security.ForbiddenException;
import com.yiqi.aitoolbench.security.UnauthorizedException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Sub2ApiException.class)
    public ResponseEntity<Map<String, Object>> handleSub2Api(Sub2ApiException exception) {
        int status = exception.statusCode() >= 400 && exception.statusCode() <= 599
                ? exception.statusCode() : 502;
        return ResponseEntity.status(status).body(Map.of(
                "code", "SUB2API_ERROR",
                "message", exception.getMessage()));
    }
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(ProviderGatewayException.class)
    public ResponseEntity<Map<String, Object>> providerGateway(ProviderGatewayException ex) {
        if (ex.upstreamBody() != null && !ex.upstreamBody().isBlank()) {
            int status = ex.upstreamStatus() != null && ex.upstreamStatus() >= 400 && ex.upstreamStatus() <= 599
                    ? ex.upstreamStatus() : HttpStatus.BAD_GATEWAY.value();
            return ResponseEntity.status(status).body(error("AI_PROVIDER_ERROR", ex.upstreamBody()));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(error("AI_PROVIDER_ERROR", "AI 供应商调用失败，请稍后重试"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("VALIDATION_ERROR", "请求参数不完整"));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> unauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("UNAUTHORIZED", ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> dataConflict(DataIntegrityViolationException ex) {
        log.warn("Database integrity conflict", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error("DATA_CONFLICT", "数据已存在或正在被使用，请刷新后重试"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> uploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("FILE_TOO_LARGE", "文件不能超过 5MB"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> fallback(Exception ex) {
        log.error("Unhandled request failure", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("INTERNAL_ERROR", "服务处理失败，请稍后重试"));
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("code", code, "message", message, "timestamp", Instant.now());
    }
}
