package com.senla.errorfreetext.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.senla.errorfreetext.correction.YandexSpellerClient;
import com.senla.errorfreetext.db.entity.TextCorrection;
import com.senla.errorfreetext.db.entity.TextCorrectionChunk;
import com.senla.errorfreetext.db.entity.TextCorrectionStatus;
import com.senla.errorfreetext.db.enums.CorrectionStatus;
import com.senla.errorfreetext.db.enums.Language;
import com.senla.errorfreetext.db.repository.TextCorrectionChunkRepository;
import com.senla.errorfreetext.db.repository.TextCorrectionRepository;
import com.senla.errorfreetext.db.repository.TextCorrectionStatusRepository;
import com.senla.errorfreetext.exception.app.TextCorrectionException;
import com.senla.errorfreetext.exception.app.TextCorrectionNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class TextCorrectionProcessorTests {

  @Mock private TextCorrectionStatusRepository statusRepository;
  @Mock private TextCorrectionRepository correctionRepository;
  @Mock private TextCorrectionChunkRepository chunkRepository;
  @Mock private YandexSpellerClient speller;
  @Mock private PlatformTransactionManager transactionManager;
  private TextCorrectionProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new TextCorrectionProcessor(statusRepository, correctionRepository,
        chunkRepository, speller, transactionManager);
  }

  @Test
  void createsContiguousChunksWithoutSplittingWords() {
    String first = "word ".repeat(1999);
    TextCorrectionStatus status = newStatus(first + "helllo", CorrectionStatus.NEW);
    when(statusRepository.findById(status.getCorrectionId())).thenReturn(Optional.of(status));

    processor.createChunks(status.getCorrectionId());

    ArgumentCaptor<List<TextCorrectionChunk>> captor = ArgumentCaptor.captor();
    verify(chunkRepository).saveAll(captor.capture());
    List<TextCorrectionChunk> chunks = captor.getValue();
    assertEquals(2, chunks.size());
    assertEquals(0, chunks.get(0).getStartPosition());
    assertEquals(first.length(), chunks.get(0).getEndPosition());
    assertEquals(first.length(), chunks.get(1).getStartPosition());
    assertEquals(first.length() + 6, chunks.get(1).getEndPosition());
    assertEquals(1, chunks.get(1).getChunkIndex());
    assertEquals(CorrectionStatus.NEW, chunks.get(1).getStatus());
    assertEquals(CorrectionStatus.PROCESSING, status.getStatus());
    assertNotNull(status.getProcessingStartedAt());
  }

  @Test
  void preservesUnicodePairAtChunkBoundary() {
    TextCorrectionStatus status = newStatus("a".repeat(9999) + "😀hello", CorrectionStatus.NEW);
    when(statusRepository.findById(status.getCorrectionId())).thenReturn(Optional.of(status));

    processor.createChunks(status.getCorrectionId());

    ArgumentCaptor<List<TextCorrectionChunk>> captor = ArgumentCaptor.captor();
    verify(chunkRepository).saveAll(captor.capture());
    assertEquals(9999, captor.getValue().get(0).getEndPosition());
    assertEquals(9999, captor.getValue().get(1).getStartPosition());
  }

  @Test
  void doesNotCreateChunksWhenCorrectionIsMissing() {
    UUID correctionId = UUID.randomUUID();
    when(statusRepository.findById(correctionId)).thenReturn(Optional.empty());

    assertThrows(TextCorrectionNotFoundException.class, () -> processor.createChunks(correctionId));

    verify(chunkRepository, never()).saveAll(any());
  }

  @Test
  void completesLastChunkAndAssemblesInOrder() {
    TextCorrectionStatus status = newStatus("helllo", CorrectionStatus.PROCESSING);
    TextCorrectionChunk chunk = readyChunk(status);
    mockProcessing(status, chunk);
    when(speller.correct("helllo", Language.EN)).thenReturn("hello");
    when(chunkRepository.findByTextCorrection_IdOrderByChunkIndex(status.getCorrectionId()))
        .thenReturn(List.of(chunk));

    processor.processChunk(chunk.getId());

    assertEquals("hello", status.getTextCorrection().getCorrectedText());
    assertEquals(CorrectionStatus.COMPLETED, status.getStatus());
    verify(chunkRepository).deleteByTextCorrection_Id(status.getCorrectionId());
  }

  @Test
  void assemblesSeveralChunksWhenFirstChunkFinishesLast() {
    TextCorrectionStatus status = newStatus("helllo world!\n", CorrectionStatus.PROCESSING);
    TextCorrectionChunk first = readyChunk(status);
    first.setEndPosition(7);
    TextCorrectionChunk second = readyChunk(status);
    second.setChunkIndex(1);
    second.setStartPosition(7);
    second.setStatus(CorrectionStatus.COMPLETED);
    second.setCorrectedText("world!\n");
    mockProcessing(status, first);
    when(speller.correct("helllo ", Language.EN)).thenReturn("hello ");
    when(chunkRepository.findByTextCorrection_IdOrderByChunkIndex(status.getCorrectionId()))
        .thenReturn(List.of(first, second));

    processor.processChunk(first.getId());

    assertEquals("hello world!\n", status.getTextCorrection().getCorrectedText());
    assertEquals(CorrectionStatus.COMPLETED, status.getStatus());
    verify(correctionRepository).save(status.getTextCorrection());
    verify(chunkRepository).deleteByTextCorrection_Id(status.getCorrectionId());
  }

  @Test
  void discardsSuccessfulResponseIfAnotherChunkHasFailed() {
    TextCorrectionStatus status = newStatus("helllo", CorrectionStatus.PROCESSING);
    TextCorrectionChunk chunk = readyChunk(status);
    mockProcessing(status, chunk);
    when(speller.correct("helllo", Language.EN)).thenAnswer(invocation -> {
      status.setStatus(CorrectionStatus.ERROR);
      status.setErrorMessage("Another chunk failed");
      return "hello";
    });

    processor.processChunk(chunk.getId());

    assertEquals(CorrectionStatus.ERROR, status.getStatus());
    assertEquals("Another chunk failed", status.getErrorMessage());
    assertNull(status.getTextCorrection().getCorrectedText());
    assertNull(chunk.getCorrectedText());
    verifyNoInteractions(correctionRepository);
    verify(chunkRepository, never()).saveAndFlush(any());
    verify(chunkRepository, never()).deleteByTextCorrection_Id(any());
  }

  @ParameterizedTest
  @EnumSource(value = CorrectionStatus.class, names = {"NEW", "COMPLETED", "ERROR"})
  void skipsChunkWhenParentIsNotProcessing(CorrectionStatus parentStatus) {
    TextCorrectionStatus status = newStatus("hello", parentStatus);
    TextCorrectionChunk chunk = readyChunk(status);
    mockProcessing(status, chunk);

    processor.processChunk(chunk.getId());

    assertEquals(CorrectionStatus.NEW, chunk.getStatus());
    assertEquals(parentStatus, status.getStatus());
    verifyNoInteractions(speller, correctionRepository);
    verify(chunkRepository, never()).save(any());
    verify(chunkRepository, never()).deleteByTextCorrection_Id(any());
  }

  @Test
  void waitsForRemainingChunksAndSendsOnlyCurrentChunk() {
    TextCorrectionStatus status = newStatus("hello 123 https://example.com", CorrectionStatus.PROCESSING);
    TextCorrectionChunk chunk = readyChunk(status);
    chunk.setEndPosition(6);
    mockProcessing(status, chunk);
    when(speller.correct("hello ", Language.EN)).thenReturn("hello ");
    when(chunkRepository.existsByTextCorrection_IdAndStatusNot(status.getCorrectionId(), CorrectionStatus.COMPLETED))
        .thenReturn(true);

    processor.processChunk(chunk.getId());

    assertEquals(CorrectionStatus.COMPLETED, chunk.getStatus());
    assertEquals(CorrectionStatus.PROCESSING, status.getStatus());
    verify(chunkRepository, never()).deleteByTextCorrection_Id(any());
  }

  @Test
  void marksChunkAndCorrectionAsErrorAndPreservesUnicodeMessageLimit() {
    TextCorrectionStatus status = newStatus("hello", CorrectionStatus.PROCESSING);
    TextCorrectionChunk chunk = readyChunk(status);
    mockProcessing(status, chunk);
    String message = "a".repeat(255) + "😀";
    when(speller.correct("hello", Language.EN))
        .thenThrow(new TextCorrectionException(message + "details"));

    processor.processChunk(chunk.getId());

    assertEquals(CorrectionStatus.ERROR, chunk.getStatus());
    assertEquals(CorrectionStatus.ERROR, status.getStatus());
    assertEquals(message, status.getErrorMessage());
    verify(chunkRepository, never()).deleteByTextCorrection_Id(any());
  }

  @ParameterizedTest
  @EnumSource(value = CorrectionStatus.class, names = {"COMPLETED", "ERROR"})
  void skipsFinishedChunk(CorrectionStatus chunkStatus) {
    TextCorrectionStatus status = newStatus("hello", CorrectionStatus.PROCESSING);
    TextCorrectionChunk chunk = readyChunk(status);
    chunk.setStatus(chunkStatus);
    chunk.setProcessingStartedAt(Instant.now());
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    when(chunkRepository.findById(chunk.getId())).thenReturn(Optional.of(chunk));

    processor.processChunk(chunk.getId());

    verify(speller, never()).correct(any(), any());
  }

  @Test
  void resumesProcessingChunkImmediatelyAfterPreviousWorkerStopped() {
    TextCorrectionStatus status = newStatus("hello", CorrectionStatus.PROCESSING);
    TextCorrectionChunk chunk = readyChunk(status);
    chunk.setStatus(CorrectionStatus.PROCESSING);
    chunk.setProcessingStartedAt(Instant.now());
    mockProcessing(status, chunk);
    when(speller.correct("hello", Language.EN)).thenReturn("hello");
    when(chunkRepository.findByTextCorrection_IdOrderByChunkIndex(status.getCorrectionId()))
        .thenReturn(List.of(chunk));

    processor.processChunk(chunk.getId());

    assertEquals("hello", status.getTextCorrection().getCorrectedText());
    assertEquals(CorrectionStatus.COMPLETED, status.getStatus());
    verify(chunkRepository).deleteByTextCorrection_Id(status.getCorrectionId());
  }

  @Test
  void handlesDatabaseReadFailureWithoutCallingYandex() {
    UUID chunkId = UUID.randomUUID();
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    when(chunkRepository.findById(chunkId)).thenThrow(new IllegalStateException("Database failure"));

    assertDoesNotThrow(() -> processor.processChunk(chunkId));

    verifyNoInteractions(speller, correctionRepository);
  }

  private void mockProcessing(TextCorrectionStatus status, TextCorrectionChunk chunk) {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    when(chunkRepository.findById(chunk.getId())).thenReturn(Optional.of(chunk));
    when(statusRepository.findById(status.getCorrectionId())).thenReturn(Optional.of(status));
  }

  private TextCorrectionStatus newStatus(String text, CorrectionStatus state) {
    UUID id = UUID.randomUUID();
    TextCorrection correction = TextCorrection.builder().id(id).text(text).language(Language.EN).build();
    return TextCorrectionStatus.builder().correctionId(id).textCorrection(correction).status(state).build();
  }

  private TextCorrectionChunk readyChunk(TextCorrectionStatus status) {
    return TextCorrectionChunk.builder().id(UUID.randomUUID())
        .textCorrection(status.getTextCorrection()).chunkIndex(0).startPosition(0)
        .endPosition(status.getTextCorrection().getText().length()).status(CorrectionStatus.NEW).build();
  }
}
