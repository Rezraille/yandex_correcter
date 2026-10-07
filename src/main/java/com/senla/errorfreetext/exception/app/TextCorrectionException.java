package com.senla.errorfreetext.exception.app;

public class TextCorrectionException extends RuntimeException {

  public TextCorrectionException(String message) {
    super(message);
  }

  public TextCorrectionException(String message, Throwable cause) {
    super(message, cause);
  }
}
