package com.senla.errorfreetext.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.senla.errorfreetext.db.enums.Language;
import com.senla.errorfreetext.dto.ErrorResponse;
import com.senla.errorfreetext.exception.app.TextCorrectionNotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception, HttpHeaders headers,
      HttpStatusCode status, WebRequest request) {
    String message = exception.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + ": " + error.getDefaultMessage())
        .distinct().sorted().collect(Collectors.joining("; "));
    return handleExceptionInternal(exception, message, headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException exception, HttpHeaders headers,
      HttpStatusCode status, WebRequest request) {
    String message = "Invalid or missing JSON request body";
    for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof InvalidFormatException invalidFormat
          && invalidFormat.getTargetType() == Language.class) {
        message = "language: must be EN or RU";
        break;
      }
    }
    return handleExceptionInternal(exception, message, headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException exception, HttpHeaders headers,
      HttpStatusCode status, WebRequest request) {
    String message = exception.getRequiredType() == UUID.class
        ? "id: must be a valid UUID" : "Invalid request parameter";
    return handleExceptionInternal(exception, message, headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception, Object body, HttpHeaders headers,
      HttpStatusCode status, WebRequest request) {
    HttpServletResponse response = ((ServletWebRequest) request).getResponse();
    if (response != null && response.isCommitted()) {
      return null;
    }
    HttpStatus httpStatus = HttpStatus.resolve(status.value());
    String message = httpStatus == null ? "Request failed" : httpStatus.getReasonPhrase();
    if (body instanceof String text) {
      message = text;
    }
    if (status.is5xxServerError()) {
      message = "Internal server error";
      log.error("Request failed with status={}", status.value(), exception);
    } else {
      log.warn("Request rejected status={} reason={}", status.value(), message);
    }
    return new ResponseEntity<>(error(message, status.value() * 100 + 1, request), headers, status);
  }

  @ExceptionHandler(TextCorrectionNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(TextCorrectionNotFoundException exception, WebRequest request) {
    log.warn("{}", exception.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(error(exception.getMessage(), 40401, request));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, WebRequest request) {
    log.error("Unexpected request error", exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(error("Internal server error", 50001, request));
  }

  private ErrorResponse error(String message, int code, WebRequest request) {
    String path = ((ServletWebRequest) request).getRequest().getRequestURI();
    return new ErrorResponse(message, code, Instant.now(), path);
  }
}
