package com.ravan.flashsale.common;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
  @ExceptionHandler(ApiException.class)
  ProblemDetail api(ApiException e) {
    return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
  }
}
