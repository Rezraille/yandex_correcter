package com.senla.errorfreetext.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class TextCorrectionConfiguration {

  @Bean
  public RestClient yandexRestClient(RestClient.Builder builder,
      @Value("${app.yandex-url}") String url) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(10));
    return builder.baseUrl(url).requestFactory(factory).build();
  }
}
