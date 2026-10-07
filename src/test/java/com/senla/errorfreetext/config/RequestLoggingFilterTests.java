package com.senla.errorfreetext.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTests {

  private final RequestLoggingFilter filter = new RequestLoggingFilter();

  @Test
  void assignsSeparateRequestIdsAndClearsContext() throws Exception {
    MockHttpServletResponse first = performRequest();
    MockHttpServletResponse second = performRequest();

    UUID.fromString(first.getHeader("X-Request-ID"));
    UUID.fromString(second.getHeader("X-Request-ID"));
    assertNotEquals(first.getHeader("X-Request-ID"), second.getHeader("X-Request-ID"));
    assertNull(MDC.get("requestId"));
  }

  @Test
  void restoresContextEvenWhenRequestFails() {
    MDC.put("requestId", "previous-request");
    MockHttpServletResponse response = new MockHttpServletResponse();
    try {
      assertThrows(ServletException.class, () -> filter.doFilter(
          new MockHttpServletRequest("GET", "/tasks"), response,
          (request, servletResponse) -> {
            assertEquals(response.getHeader("X-Request-ID"), MDC.get("requestId"));
            throw new ServletException("Request failed");
          }));
      assertEquals("previous-request", MDC.get("requestId"));
    } finally {
      MDC.remove("requestId");
    }
  }

  @Test
  void logsResponseMetadataWithoutPayloadQueryOrHeaders() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/tasks");
      request.setQueryString("token=private-query");
      request.addHeader("Authorization", "private-header");
      request.setContent("private-request-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, (incoming, outgoing) -> {
        response.setStatus(201);
        response.getWriter().write("private-response-body");
      });

      assertEquals(1, appender.list.size());
      String message = appender.list.get(0).getFormattedMessage();
      assertTrue(message.matches("HTTP method=POST path=/tasks status=201 durationMs=\\d+"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  private MockHttpServletResponse performRequest() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/tasks"), response,
        (request, outgoing) -> assertEquals(response.getHeader("X-Request-ID"), MDC.get("requestId")));
    return response;
  }
}
