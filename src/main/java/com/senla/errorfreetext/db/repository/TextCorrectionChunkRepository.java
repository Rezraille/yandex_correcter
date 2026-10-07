package com.senla.errorfreetext.db.repository;

import com.senla.errorfreetext.db.entity.TextCorrectionChunk;
import com.senla.errorfreetext.db.enums.CorrectionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TextCorrectionChunkRepository extends JpaRepository<TextCorrectionChunk, UUID> {

  @Query("select chunk.id from TextCorrectionChunk chunk "
      + "where (chunk.status = :newStatus or chunk.status = :processingStatus) "
      + "and exists (select correctionStatus.correctionId from TextCorrectionStatus correctionStatus "
      + "where correctionStatus.correctionId = chunk.textCorrection.id "
      + "and correctionStatus.status = :processingStatus) "
      + "order by chunk.textCorrection.createdAt, chunk.textCorrection.id, chunk.chunkIndex")
  List<UUID> findIdsForProcessing(@Param("newStatus") CorrectionStatus newStatus,
      @Param("processingStatus") CorrectionStatus processingStatus, Pageable pageable);

  boolean existsByTextCorrection_IdAndStatusNot(UUID correctionId, CorrectionStatus status);

  List<TextCorrectionChunk> findByTextCorrection_IdOrderByChunkIndex(UUID correctionId);

  void deleteByTextCorrection_Id(UUID correctionId);

  @Override
  @EntityGraph(attributePaths = "textCorrection")
  Optional<TextCorrectionChunk> findById(UUID id);
}
