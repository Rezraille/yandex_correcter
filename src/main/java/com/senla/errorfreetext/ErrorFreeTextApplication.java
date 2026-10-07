package com.senla.errorfreetext;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ErrorFreeTextApplication {

  public static void main(String[] args) {
    SpringApplication.run(ErrorFreeTextApplication.class, args);
  }
}
