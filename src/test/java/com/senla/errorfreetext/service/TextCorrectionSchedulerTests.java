package com.senla.errorfreetext.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.senla.errorfreetext.db.enums.CorrectionStatus;
import com.senla.errorfreetext.db.repository.TextCorrectionStatusRepository;
import com.senla.errorfreetext.db.repository.TextCorrectionChunkRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class TextCorrectionSchedulerTests {

  private final TextCorrectionStatusRepository repository = mock(TextCorrectionStatusRepository.class);
  private final TextCorrectionChunkRepository chunkRepository = mock(TextCorrectionChunkRepository.class);
  private final TextCorrectionProcessor processor = mock(TextCorrectionProcessor.class);
  private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
  private final CountDownLatch releaseWorker = new CountDownLatch(1);
  private TextCorrectionScheduler scheduler;

  @BeforeEach
  void setUp() {
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(2);
    executor.initialize();
    scheduler = new TextCorrectionScheduler(repository, chunkRepository, processor, executor);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    releaseWorker.countDown();
    executor.getThreadPoolExecutor().shutdown();
    assertTrue(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS));
    executor.shutdown();
  }

  @Test
  void dispatchesIdsAndDoesNotResubmitQueuedChunks() throws InterruptedException {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    CountDownLatch started = new CountDownLatch(1);
    doAnswer(invocation -> {
      started.countDown();
      assertTrue(releaseWorker.await(5, TimeUnit.SECONDS));
      return null;
    }).when(processor).processChunk(first);
    when(chunkRepository.findIdsForProcessing(eq(CorrectionStatus.NEW),
        eq(CorrectionStatus.PROCESSING), any(Pageable.class)))
        .thenReturn(List.of(first, second)).thenReturn(List.of(first, second, third));

    scheduler.processCorrections();
    assertTrue(started.await(5, TimeUnit.SECONDS));
    assertEquals(1, executor.getQueueSize());
    verify(processor, never()).processChunk(second);

    scheduler.processCorrections();

    assertEquals(2, executor.getQueueSize());
    releaseWorker.countDown();
    executor.getThreadPoolExecutor().shutdown();
    assertTrue(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS));
    verify(processor, times(1)).processChunk(first);
    verify(processor, times(1)).processChunk(second);
    verify(processor, times(1)).processChunk(third);
  }

  @Test
  void doesNotQueryChunksWhenQueueIsFull() throws InterruptedException {
    CountDownLatch started = new CountDownLatch(1);
    executor.execute(() -> {
      started.countDown();
      try {
        assertTrue(releaseWorker.await(5, TimeUnit.SECONDS));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(exception);
      }
    });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    executor.execute(() -> {});
    executor.execute(() -> {});

    scheduler.processCorrections();

    verifyNoInteractions(chunkRepository, processor);
  }

  @Test
  void stopsSubmittingWhenQueueFillsDuringDispatch() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(2);
    ThreadPoolExecutor pool = mock(ThreadPoolExecutor.class);
    ThreadPoolTaskExecutor limitedExecutor = mock(ThreadPoolTaskExecutor.class);
    when(limitedExecutor.getMaxPoolSize()).thenReturn(1);
    when(limitedExecutor.getThreadPoolExecutor()).thenReturn(pool);
    when(pool.getQueue()).thenReturn(queue);
    when(chunkRepository.findIdsForProcessing(CorrectionStatus.NEW,
        CorrectionStatus.PROCESSING, org.springframework.data.domain.PageRequest.of(0, 2)))
        .thenReturn(List.of(first, second));
    doAnswer(invocation -> {
      queue.add(invocation.getArgument(0, Runnable.class));
      queue.add(() -> {});
      return null;
    }).when(limitedExecutor).execute(any(Runnable.class));
    scheduler = new TextCorrectionScheduler(repository, chunkRepository, processor, limitedExecutor);

    scheduler.processCorrections();

    verify(limitedExecutor, times(1)).execute(any(Runnable.class));
    verify(limitedExecutor, never()).getActiveCount();
    verifyNoInteractions(processor);
  }

  @Test
  void rejectedChunkCanBeSubmittedOnNextRun() {
    UUID correctionId = UUID.randomUUID();
    ThreadPoolTaskExecutor rejectingExecutor = mock(ThreadPoolTaskExecutor.class);
    when(rejectingExecutor.getMaxPoolSize()).thenReturn(1);
    when(rejectingExecutor.getThreadPoolExecutor()).thenReturn(executor.getThreadPoolExecutor());
    doThrow(new TaskRejectedException("Pool is full"))
        .doAnswer(invocation -> {
          invocation.getArgument(0, Runnable.class).run();
          return null;
        }).when(rejectingExecutor).execute(any(Runnable.class));
    when(chunkRepository.findIdsForProcessing(eq(CorrectionStatus.NEW),
        eq(CorrectionStatus.PROCESSING), any(Pageable.class)))
        .thenReturn(List.of(correctionId));
    scheduler = new TextCorrectionScheduler(repository, chunkRepository, processor, rejectingExecutor);

    scheduler.processCorrections();
    verifyNoInteractions(processor);
    scheduler.processCorrections();

    verify(processor).processChunk(correctionId);
  }

  @Test
  void releasesChunkReservationEvenWhenProcessorThrows() {
    UUID correctionId = UUID.randomUUID();
    ThreadPoolTaskExecutor capturingExecutor = mock(ThreadPoolTaskExecutor.class);
    when(capturingExecutor.getMaxPoolSize()).thenReturn(1);
    when(capturingExecutor.getThreadPoolExecutor()).thenReturn(executor.getThreadPoolExecutor());
    AtomicReference<Runnable> submitted = new AtomicReference<>();
    doAnswer(invocation -> {
      submitted.set(invocation.getArgument(0, Runnable.class));
      return null;
    }).when(capturingExecutor).execute(any(Runnable.class));
    when(chunkRepository.findIdsForProcessing(eq(CorrectionStatus.NEW),
        eq(CorrectionStatus.PROCESSING), any(Pageable.class)))
        .thenReturn(List.of(correctionId));
    doThrow(new IllegalStateException("Unexpected failure")).when(processor).processChunk(correctionId);
    scheduler = new TextCorrectionScheduler(repository, chunkRepository, processor, capturingExecutor);

    scheduler.processCorrections();
    assertThrows(IllegalStateException.class, () -> submitted.get().run());
    scheduler.processCorrections();

    verify(capturingExecutor, times(2)).execute(any(Runnable.class));
  }

  @Test
  void createsChunksForNewCorrectionsAndContinuesAfterFailure() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(repository.findIdsByStatus(eq(CorrectionStatus.NEW), any(Pageable.class)))
        .thenReturn(List.of(first, second));
    doThrow(new IllegalStateException("Database failure")).when(processor).createChunks(first);

    scheduler.processCorrections();

    verify(processor).createChunks(first);
    verify(processor).createChunks(second);
    verify(chunkRepository).findIdsForProcessing(eq(CorrectionStatus.NEW),
        eq(CorrectionStatus.PROCESSING), any(Pageable.class));
  }

  @Test
  void doesNothingWhenNoReadyChunksExist() {
    when(chunkRepository.findIdsForProcessing(eq(CorrectionStatus.NEW),
        eq(CorrectionStatus.PROCESSING), any(Pageable.class)))
        .thenReturn(List.of());

    scheduler.processCorrections();

    verifyNoInteractions(processor);
    assertEquals(0, executor.getQueueSize());
  }
}
