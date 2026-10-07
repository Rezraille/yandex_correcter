package com.senla.errorfreetext.db.entity;

import com.senla.errorfreetext.db.enums.CorrectionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;


@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
@Entity
@Table(name = "text_status")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TextCorrectionStatus {

  @Id
  @Column(name = "correction_id", nullable = false)
  UUID correctionId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId
  @JoinColumn(name = "correction_id", nullable = false)
  TextCorrection textCorrection;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  CorrectionStatus status;

  @Column(name = "error_message", length = 256)
  String errorMessage;

  @Column(name = "processing_started_at")
  Instant processingStartedAt;
}
