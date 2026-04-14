package com.example.fc2_live_automation.repository;

import com.example.fc2_live_automation.model.Fc2Preset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface Fc2PresetRepository extends JpaRepository<Fc2Preset, Long> {
}