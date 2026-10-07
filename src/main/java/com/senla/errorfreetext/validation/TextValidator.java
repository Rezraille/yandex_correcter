package com.senla.errorfreetext.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TextValidator implements ConstraintValidator<ValidText, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank() || value.codePointCount(0, value.length()) < 3) {
      return false;
    }

    return value.codePoints().anyMatch(Character::isLetter);
  }
}
