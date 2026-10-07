package com.senla.errorfreetext.db.entity;

import com.senla.errorfreetext.db.enums.Language;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "text_correction")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TextCorrection {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "language", nullable = false, length = 10)
  Language language;

  @Column(name = "text", nullable = false)
  String text;

  @Column(name = "created_at", nullable = false, updatable = false)
  Instant createdAt;

  @Column(name = "corrected_text")
  String correctedText;
}
