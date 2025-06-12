package com.tradingbot.backend.repository;

import com.tradingbot.backend.model.AppSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppSettingsRepository extends JpaRepository<AppSettings, Long> {
    
    Optional<AppSettings> findByKey(String key);
    
    void deleteByKey(String key);
} 