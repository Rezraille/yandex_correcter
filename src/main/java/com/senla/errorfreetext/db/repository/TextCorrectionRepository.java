package com.senla.errorfreetext.db.repository;

import com.senla.errorfreetext.db.entity.TextCorrection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TextCorrectionRepository extends JpaRepository<TextCorrection, UUID> {
}
