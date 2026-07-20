package com.tang.plugin.controller;

import com.tang.common.core.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class PluginExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<Map<String, Object>> handleCustom(CustomException e) {
        int status = e.getHttpStatus() > 0 ? e.getHttpStatus() : HttpStatus.BAD_REQUEST.value();
        if (status >= 500) {
            log.error("Business error: {}", e.getMessage(), e);
        } else {
            log.warn("Business error status={} code={}: {}", status, e.getCode(), e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ERROR");
        body.put("message", e.getMessage());
        if (e.getCode() != null) {
            body.put("code", e.getCode());
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("Unhandled error: {}", e.getMessage(), e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ERROR");
        body.put("message", e.getClass().getSimpleName() + ": " + e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
