package com.senla.errorfreetext.service;

import com.senla.errorfreetext.correction.TextChunkSplitter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class TextCorrectionProcessor {

  private final TextCorrectionStatusRepository statusRepository;
  private final TextCorrectionRepository textCorrectionRepository;
  private final TextCorrectionChunkRepository chunkRepository;
  private final YandexSpellerClient speller;
  private final TransactionTemplate transactions;

  public TextCorrectionProcessor(TextCorrectionStatusRepository statusRepository,
      TextCorrectionRepository textCorrectionRepository,
      TextCorrectionChunkRepository chunkRepository, YandexSpellerClient speller,
      PlatformTransactionManager transactionManager) {
    this.statusRepository = statusRepository;
    this.textCorrectionRepository = textCorrectionRepository;
    this.chunkRepository = chunkRepository;
    this.speller = speller;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Transactional
  public void createChunks(UUID correctionId) {
    TextCorrectionStatus correctionStatus = statusRepository.findById(correctionId)
        .orElseThrow(() -> new TextCorrectionNotFoundException(correctionId));
    TextCorrection correction = correctionStatus.getTextCorrection();
    String text = correction.getText();
    List<TextCorrectionChunk> chunks = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = TextChunkSplitter.endPosition(text, start);
      chunks.add(TextCorrectionChunk.builder()
          .id(UUID.randomUUID())
          .textCorrection(correction)
          .chunkIndex(chunks.size())
          .startPosition(start)
          .endPosition(end)
          .status(CorrectionStatus.NEW)
          .build());
      start = end;
    }
    chunkRepository.saveAll(chunks);
    correctionStatus.setStatus(CorrectionStatus.PROCESSING);
    correctionStatus.setProcessingStartedAt(Instant.now());
    correctionStatus.setErrorMessage(null);
    statusRepository.save(correctionStatus);
    log.info("Created chunks correctionId={} count={}", correctionId, chunks.size());
  }

  public void processChunk(UUID chunkId) {
    long startedAt = System.nanoTime();
    try {
      ChunkData chunkData = transactions.execute(status -> prepareChunk(chunkId));
      if (chunkData == null) {
        return;
      }
      log.info("Processing chunkId={} correctionId={} textLength={}",
          chunkId, chunkData.correctionId(), chunkData.text().length());
      String correctedText;
      try {
        correctedText = speller.correct(chunkData.text(), chunkData.language());
      } catch (RuntimeException exception) {
        saveChunkError(chunkData, exception);
        log.error("Failed chunkId={} correctionId={}", chunkId, chunkData.correctionId(), exception);
        return;
      }

      saveChunkResult(chunkData, correctedText);
      log.info("Finished chunk processing chunkId={} correctionId={} durationMs={}",
          chunkId, chunkData.correctionId(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    } catch (RuntimeException exception) {
      log.error("Unable to persist chunk processing chunkId={}", chunkId, exception);
    }
  }

  private synchronized void saveChunkResult(ChunkData chunkData, String correctedText) {
    transactions.executeWithoutResult(status -> completeChunk(chunkData, correctedText));
  }

  private synchronized void saveChunkError(ChunkData chunkData, RuntimeException exception) {
    transactions.executeWithoutResult(status -> failChunk(chunkData, exception));
  }

  private ChunkData prepareChunk(UUID chunkId) {
    TextCorrectionChunk chunk = chunkRepository.findById(chunkId).orElse(null);
    if (chunk == null || !isReady(chunk)) {
      return null;
    }
    TextCorrection correction = chunk.getTextCorrection();
    TextCorrectionStatus correctionStatus = statusRepository.findById(correction.getId()).orElse(null);
    if (correctionStatus == null || correctionStatus.getStatus() != CorrectionStatus.PROCESSING) {
      return null;
    }
    chunk.setStatus(CorrectionStatus.PROCESSING);
    chunk.setProcessingStartedAt(Instant.now());
    chunkRepository.save(chunk);
    return new ChunkData(chunkId, correction.getId(),
        correction.getText().substring(chunk.getStartPosition(), chunk.getEndPosition()),
        correction.getLanguage());
  }

  private boolean isReady(TextCorrectionChunk chunk) {
    return chunk.getStatus() == CorrectionStatus.NEW
        || chunk.getStatus() == CorrectionStatus.PROCESSING;
  }

  private void completeChunk(ChunkData chunkData, String correctedText) {
    TextCorrectionStatus correctionStatus = statusRepository.findById(chunkData.correctionId()).orElse(null);
    if (correctionStatus == null || correctionStatus.getStatus() != CorrectionStatus.PROCESSING) {
      return;
    }
    TextCorrectionChunk chunk = chunkRepository.findById(chunkData.chunkId()).orElse(null);
    if (chunk == null) {
      return;
    }
    chunk.setCorrectedText(correctedText);
    chunk.setStatus(CorrectionStatus.COMPLETED);
    chunkRepository.saveAndFlush(chunk);
    if (chunkRepository.existsByTextCorrection_IdAndStatusNot(
        chunkData.correctionId(), CorrectionStatus.COMPLETED)) {
      return;
    }
    List<TextCorrectionChunk> chunks =
        chunkRepository.findByTextCorrection_IdOrderByChunkIndex(chunkData.correctionId());
    String fullText = chunks.stream().map(TextCorrectionChunk::getCorrectedText)
        .collect(Collectors.joining());
    TextCorrection correction = correctionStatus.getTextCorrection();
    correction.setCorrectedText(fullText);
    textCorrectionRepository.save(correction);
    correctionStatus.setStatus(CorrectionStatus.COMPLETED);
    correctionStatus.setErrorMessage(null);
    statusRepository.save(correctionStatus);
    chunkRepository.deleteByTextCorrection_Id(chunkData.correctionId());
  }

  private void failChunk(ChunkData chunkData, RuntimeException exception) {
    TextCorrectionStatus correctionStatus = statusRepository.findById(chunkData.correctionId()).orElse(null);
    if (correctionStatus == null || correctionStatus.getStatus() != CorrectionStatus.PROCESSING) {
      return;
    }
    TextCorrectionChunk chunk = chunkRepository.findById(chunkData.chunkId()).orElse(null);
    if (chunk == null) {
      return;
    }
    chunk.setStatus(CorrectionStatus.ERROR);
    chunkRepository.save(chunk);
    String message = exception instanceof TextCorrectionException && exception.getMessage() != null
        ? exception.getMessage() : "Text correction failed";
    int length = Math.min(256, message.codePointCount(0, message.length()));
    correctionStatus.setStatus(CorrectionStatus.ERROR);
    correctionStatus.setErrorMessage(message.substring(0, message.offsetByCodePoints(0, length)));
    statusRepository.save(correctionStatus);
  }

  private record ChunkData(UUID chunkId, UUID correctionId, String text, Language language) {
  }
}
