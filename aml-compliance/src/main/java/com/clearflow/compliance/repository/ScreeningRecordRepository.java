package com.clearflow.compliance.repository;

import com.clearflow.compliance.domain.ScreeningRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScreeningRecordRepository extends JpaRepository<ScreeningRecord, String> {
}
