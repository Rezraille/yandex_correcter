package com.senla.errorfreetext.service;

import com.senla.errorfreetext.db.enums.CorrectionStatus;
import com.senla.errorfreetext.db.repository.TextCorrectionChunkRepository;
import com.senla.errorfreetext.db.repository.TextCorrectionStatusRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TextCorrectionScheduler {

  private final TextCorrectionStatusRepository statusRepository;
  private final TextCorrectionChunkRepository chunkRepository;
  private final TextCorrectionProcessor processor;
  private final ThreadPoolTaskExecutor executor;

  private final Set<UUID> submittedChunkIds = ConcurrentHashMap.newKeySet();

  public TextCorrectionScheduler(TextCorrectionStatusRepository statusRepository,
      TextCorrectionChunkRepository chunkRepository, TextCorrectionProcessor processor,
      @Qualifier("correctionExecutor") ThreadPoolTaskExecutor executor) {
    this.statusRepository = statusRepository;
    this.chunkRepository = chunkRepository;
    this.processor = processor;
    this.executor = executor;
  }

  @Scheduled(fixedDelayString = "${app.scheduler-delay-ms}")
  public void processCorrections() {
    createChunks();

    int freeQueueSlots = executor.getThreadPoolExecutor().getQueue().remainingCapacity();
    if (freeQueueSlots == 0) {
      return;
    }

    List<UUID> chunkIds = chunkRepository.findIdsForProcessing(
        CorrectionStatus.NEW,
        CorrectionStatus.PROCESSING,
        PageRequest.of(0, freeQueueSlots + submittedChunkIds.size())
    );

    for (UUID chunkId : chunkIds) {
      if (executor.getThreadPoolExecutor().getQueue().remainingCapacity() == 0) {
        break;
      }
      if (!submittedChunkIds.add(chunkId)) {
        continue;
      }

      try {
        executor.execute(() -> {
          try {
            processor.processChunk(chunkId);
          } finally {
            submittedChunkIds.remove(chunkId);
          }
        });
      } catch (TaskRejectedException exception) {
        submittedChunkIds.remove(chunkId);
        log.debug("Chunk submission rejected id={}; will retry on the next scheduler run", chunkId);
        break;
      }
    }
  }

  private void createChunks() {
    List<UUID> correctionIds = statusRepository.findIdsByStatus(
        CorrectionStatus.NEW,
        PageRequest.of(0, executor.getMaxPoolSize())
    );

    for (UUID correctionId : correctionIds) {
      try {
        processor.createChunks(correctionId);
      } catch (RuntimeException exception) {
        log.error("Unable to create chunks correctionId={}", correctionId, exception);
      }
    }
  }

}
