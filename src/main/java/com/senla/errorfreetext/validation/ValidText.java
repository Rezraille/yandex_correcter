package com.senla.errorfreetext.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TextValidator.class)
public @interface ValidText {

  String message() default "must contain at least 3 characters and at least one letter";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
