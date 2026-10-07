package com.senla.errorfreetext.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.senla.errorfreetext.db.enums.CorrectionStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TextCorrectionResponse(CorrectionStatus status, String correctedText, String errorMessage) {
}
