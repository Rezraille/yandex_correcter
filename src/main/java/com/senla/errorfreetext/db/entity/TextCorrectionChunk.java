package com.senla.errorfreetext.db.entity;

import com.senla.errorfreetext.db.enums.CorrectionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
@Entity
@Table(name = "text_chunk")
public class TextCorrectionChunk {

  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "correction_id", nullable = false)
  private TextCorrection textCorrection;

  @Column(name = "chunk_index", nullable = false)
  private Integer chunkIndex;

  @Column(name = "start_position", nullable = false)
  private Integer startPosition;

  @Column(name = "end_position", nullable = false)
  private Integer endPosition;

  @Column(name = "corrected_text")
  private String correctedText;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private CorrectionStatus status;

  @Column(name = "processing_started_at")
  private Instant processingStartedAt;
}
