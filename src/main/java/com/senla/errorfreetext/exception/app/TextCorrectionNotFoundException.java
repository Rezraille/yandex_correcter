package com.senla.errorfreetext.exception.app;

import java.util.UUID;

public class TextCorrectionNotFoundException extends RuntimeException {

  public TextCorrectionNotFoundException(UUID id) {
    super("Text correction with id: " + id + " not found");
  }
}
