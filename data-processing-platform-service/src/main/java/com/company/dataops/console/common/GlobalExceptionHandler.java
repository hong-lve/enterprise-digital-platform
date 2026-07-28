package com.company.dataops.console.common;

import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> responseStatus(ResponseStatusException exception) {
        return ResponseEntity
            .status(exception.getStatusCode())
            .body(ApiResponse.error(exception.getStatusCode().value(), exception.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> methodArgumentNotValid(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(error -> error.getField() + "：" + error.getDefaultMessage())
            .collect(Collectors.joining("；"));
        return ApiResponse.error(400, message.isBlank() ? "请求参数不正确" : message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> constraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations()
            .stream()
            .map(violation -> violation.getPropertyPath() + "：" + violation.getMessage())
            .collect(Collectors.joining("；"));
        return ApiResponse.error(400, message.isBlank() ? "请求参数不正确" : message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> unexpected(Exception exception) {
        return ApiResponse.error(500, exception.getMessage());
    }
}
