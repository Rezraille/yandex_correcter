package com.senla.errorfreetext.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String previousRequestId = MDC.get("requestId");
    String requestId = UUID.randomUUID().toString();
    MDC.put("requestId", requestId);
    response.setHeader("X-Request-ID", requestId);
    long startedAt = System.nanoTime();
    boolean failed = false;
    try {
      filterChain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException exception) {
      failed = true;
      log.error("Unhandled HTTP request error", exception);
      throw exception;
    } finally {
      int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
      // Bodies, query parameters and headers may contain user data.
      String path = request.getRequestURI().replace('\r', '_').replace('\n', '_');
      log.info("HTTP method={} path={} status={} durationMs={}",
          request.getMethod(), path, status, durationMs);
      if (previousRequestId == null) {
        MDC.remove("requestId");
      } else {
        MDC.put("requestId", previousRequestId);
      }
    }
  }
}
