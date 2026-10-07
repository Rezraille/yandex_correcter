package com.senla.errorfreetext.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JacksonConfiguration {

  @Bean
  public Jackson2ObjectMapperBuilderCustomizer strictStringValues() {
    return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(String.class)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
  }
}
