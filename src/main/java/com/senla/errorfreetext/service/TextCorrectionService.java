package com.senla.errorfreetext.service;

import com.senla.errorfreetext.dto.CreateTextCorrectionRequest;
import com.senla.errorfreetext.dto.TextCorrectionResponse;
import com.senla.errorfreetext.exception.app.TextCorrectionNotFoundException;
import com.senla.errorfreetext.db.entity.TextCorrection;
import com.senla.errorfreetext.db.entity.TextCorrectionStatus;
import com.senla.errorfreetext.db.enums.CorrectionStatus;
import com.senla.errorfreetext.db.repository.TextCorrectionStatusRepository;
import com.senla.errorfreetext.db.repository.TextCorrectionRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TextCorrectionService {

  private final TextCorrectionStatusRepository statusRepository;
  private final TextCorrectionRepository textCorrectionRepository;

  @Transactional
  public UUID createTextCorrection(CreateTextCorrectionRequest request) {
    TextCorrection newTextCorrection = textCorrectionRepository.save(TextCorrection.builder()
        .text(request.text())
        .language(request.language())
        .createdAt(Instant.now())
        .build());
    statusRepository.save(TextCorrectionStatus.builder()
        .textCorrection(newTextCorrection)
        .status(CorrectionStatus.NEW)
        .build());
    log.info("Created text correction with id={}", newTextCorrection.getId());
    return newTextCorrection.getId();
  }

  @Transactional(readOnly = true)
  public TextCorrectionResponse getTextCorrection(UUID id) {
    TextCorrectionStatus status = statusRepository.findById(id)
        .orElseThrow(() -> new TextCorrectionNotFoundException(id));
    log.debug("Read text correction id={} status={}", id, status.getStatus());
    return switch (status.getStatus()) {
      case NEW, PROCESSING -> new TextCorrectionResponse(status.getStatus(), null, null);
      case COMPLETED -> new TextCorrectionResponse(status.getStatus(),
          status.getTextCorrection().getCorrectedText(), null);
      case ERROR -> new TextCorrectionResponse(status.getStatus(), null, status.getErrorMessage());
    };
  }
}
