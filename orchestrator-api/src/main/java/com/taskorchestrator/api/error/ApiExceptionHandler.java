package com.taskorchestrator.api.error;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.taskorchestrator.contracts.InvalidJobTransitionException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Uniform RFC 9457 problem responses: {@code type,title,status,detail,code[,errors]}. Messages are
 * written here, never copied from arbitrary exceptions, so stack traces and internals never leak.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalidParameters(HandlerMethodValidationException e) {
        List<String> errors = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> r.getMethodParameter().getParameterName() + " " + err.getDefaultMessage()))
                .sorted()
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException e) {
        List<String> errors = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted()
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", errors);
    }

    @ExceptionHandler(InvalidPayloadException.class)
    ProblemDetail invalidPayload(InvalidPayloadException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "Job payload is invalid for its type", e.getErrors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof InvalidFormatException ife && ife.getTargetType() != null && ife.getTargetType().isEnum()) {
            String field = ife.getPath().isEmpty() ? "value" : ife.getPath().getLast().getFieldName();
            return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Invalid value for " + field,
                    List.of(field + " must be one of " + Arrays.toString(ife.getTargetType().getEnumConstants())));
        }
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or is not valid JSON for this endpoint", null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid value for parameter '" + e.getName() + "'", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", e.getMessage(), null);
    }

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail notFound(JobNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", e.getMessage(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail noResource(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint", null);
    }

    @ExceptionHandler(InvalidJobTransitionException.class)
    ProblemDetail invalidTransition(InvalidJobTransitionException e) {
        return problem(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "Job is " + e.getFrom() + " and cannot move to " + e.getTo(), null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Job was modified concurrently; re-read and retry", null);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", e.getMessage(), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method " + e.getMethod() + " not supported", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail unsupportedMedia(HttpMediaTypeNotSupportedException e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Use Content-Type: application/json", null);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled API error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", null);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, List<String> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://task-orchestrator.local/problems/" + code.toLowerCase().replace('_', '-')));
        pd.setProperty("code", code);
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        return pd;
    }
}
