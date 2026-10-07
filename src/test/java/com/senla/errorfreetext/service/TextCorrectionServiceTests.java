package com.senla.errorfreetext.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.senla.errorfreetext.db.entity.TextCorrection;
import com.senla.errorfreetext.db.entity.TextCorrectionStatus;
import com.senla.errorfreetext.db.enums.CorrectionStatus;
import com.senla.errorfreetext.db.enums.Language;
import com.senla.errorfreetext.db.repository.TextCorrectionStatusRepository;
import com.senla.errorfreetext.db.repository.TextCorrectionRepository;
import com.senla.errorfreetext.dto.CreateTextCorrectionRequest;
import com.senla.errorfreetext.dto.TextCorrectionResponse;
import com.senla.errorfreetext.exception.app.TextCorrectionNotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TextCorrectionServiceTests {

  @Mock
  private TextCorrectionStatusRepository repository;

  @Mock
  private TextCorrectionRepository textCorrectionRepository;

  @InjectMocks
  private TextCorrectionService service;

  @ParameterizedTest
  @EnumSource(Language.class)
  void savesOriginalTextAndLanguageWithNewStatus(Language language) {
    UUID id = UUID.randomUUID();
    TextCorrection persisted = TextCorrection.builder()
        .id(id).text("Превет").language(language).build();
    when(textCorrectionRepository.save(any(TextCorrection.class))).thenReturn(persisted);

    Instant beforeCreation = Instant.now();
    UUID actualId = service.createTextCorrection(new CreateTextCorrectionRequest("Превет", language));

    ArgumentCaptor<TextCorrection> textCaptor = ArgumentCaptor.forClass(TextCorrection.class);
    ArgumentCaptor<TextCorrectionStatus> statusCaptor = ArgumentCaptor.forClass(TextCorrectionStatus.class);
    InOrder order = inOrder(textCorrectionRepository, repository);
    order.verify(textCorrectionRepository).save(textCaptor.capture());
    order.verify(repository).save(statusCaptor.capture());

    assertEquals(id, actualId);
    TextCorrection text = textCaptor.getValue();
    assertEquals("Превет", text.getText());
    assertEquals(language, text.getLanguage());
    assertFalse(text.getCreatedAt().isBefore(beforeCreation));
    assertFalse(text.getCreatedAt().isAfter(Instant.now()));
    assertNull(text.getCorrectedText());
    TextCorrectionStatus status = statusCaptor.getValue();
    assertSame(persisted, status.getTextCorrection());
    assertEquals(CorrectionStatus.NEW, status.getStatus());
    assertNull(status.getProcessingStartedAt());
    assertNull(status.getErrorMessage());
  }

  @Test
  void readsCompletedCorrectionFromDatabase() {
    TextCorrectionStatus correctionStatus = correctionWithStatus(CorrectionStatus.COMPLETED);
    correctionStatus.getTextCorrection().setCorrectedText("Привет");
    when(repository.findById(correctionStatus.getCorrectionId())).thenReturn(Optional.of(correctionStatus));

    assertEquals(new TextCorrectionResponse(CorrectionStatus.COMPLETED, "Привет", null),
        service.getTextCorrection(correctionStatus.getCorrectionId()));
  }

  @ParameterizedTest
  @EnumSource(value = CorrectionStatus.class, names = {"NEW", "PROCESSING"})
  void readsPendingCorrectionFromDatabase(CorrectionStatus status) {
    TextCorrectionStatus correctionStatus = correctionWithStatus(status);
    when(repository.findById(correctionStatus.getCorrectionId())).thenReturn(Optional.of(correctionStatus));

    assertEquals(new TextCorrectionResponse(status, null, null),
        service.getTextCorrection(correctionStatus.getCorrectionId()));
  }

  @Test
  void readsFailedCorrectionFromDatabase() {
    TextCorrectionStatus correctionStatus = correctionWithStatus(CorrectionStatus.ERROR);
    correctionStatus.setErrorMessage("Yandex unavailable");
    when(repository.findById(correctionStatus.getCorrectionId())).thenReturn(Optional.of(correctionStatus));

    assertEquals(new TextCorrectionResponse(CorrectionStatus.ERROR, null, "Yandex unavailable"),
        service.getTextCorrection(correctionStatus.getCorrectionId()));
  }

  @Test
  void rejectsUnknownId() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    TextCorrectionNotFoundException exception = assertThrows(TextCorrectionNotFoundException.class,
        () -> service.getTextCorrection(id));
    assertEquals("Text correction with id: " + id + " not found", exception.getMessage());
  }

  @ParameterizedTest
  @EnumSource(CorrectionStatus.class)
  void includesOnlyFieldsAllowedForCurrentStatus(CorrectionStatus status) {
    TextCorrectionStatus correctionStatus = correctionWithStatus(status);
    correctionStatus.getTextCorrection().setCorrectedText("Привет");
    correctionStatus.setErrorMessage("Previous error");
    when(repository.findById(correctionStatus.getCorrectionId())).thenReturn(Optional.of(correctionStatus));

    TextCorrectionResponse response = service.getTextCorrection(correctionStatus.getCorrectionId());

    assertEquals(status, response.status());
    assertEquals(status == CorrectionStatus.COMPLETED ? "Привет" : null, response.correctedText());
    assertEquals(status == CorrectionStatus.ERROR ? "Previous error" : null, response.errorMessage());
  }

  private TextCorrectionStatus correctionWithStatus(CorrectionStatus status) {
    UUID id = UUID.randomUUID();
    TextCorrection correction = TextCorrection.builder()
        .id(id).text("Превет").language(Language.RU).build();
    return TextCorrectionStatus.builder()
        .correctionId(id).textCorrection(correction).status(status).build();
  }
}
