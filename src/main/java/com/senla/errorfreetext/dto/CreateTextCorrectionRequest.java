package com.senla.errorfreetext.dto;

import com.senla.errorfreetext.db.enums.Language;
import com.senla.errorfreetext.validation.ValidText;
import jakarta.validation.constraints.NotNull;


public record CreateTextCorrectionRequest(
    @ValidText
    String text,
    @NotNull(message = "must be specified (EN or RU)")
    Language language
) {
}
