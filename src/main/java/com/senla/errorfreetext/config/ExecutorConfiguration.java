package com.senla.errorfreetext.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ExecutorConfiguration {

  @Bean
  public ThreadPoolTaskExecutor correctionExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(5);
    executor.setQueueCapacity(30);
    executor.setThreadNamePrefix("text-correction-");
    return executor;
  }
}
