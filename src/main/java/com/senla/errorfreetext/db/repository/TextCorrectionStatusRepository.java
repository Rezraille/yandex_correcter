package com.senla.errorfreetext.db.repository;

import com.senla.errorfreetext.db.entity.TextCorrectionStatus;
import com.senla.errorfreetext.db.enums.CorrectionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TextCorrectionStatusRepository extends JpaRepository<TextCorrectionStatus, UUID> {

  @Query("select correctionStatus.correctionId from TextCorrectionStatus correctionStatus "
      + "where correctionStatus.status = :status "
      + "order by correctionStatus.textCorrection.createdAt, correctionStatus.correctionId")
  List<UUID> findIdsByStatus(@Param("status") CorrectionStatus status, Pageable pageable);

  @Override
  @EntityGraph(attributePaths = "textCorrection")
  Optional<TextCorrectionStatus> findById(UUID id);
}
