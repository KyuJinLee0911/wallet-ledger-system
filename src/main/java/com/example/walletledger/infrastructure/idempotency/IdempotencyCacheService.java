package com.example.walletledger.infrastructure.idempotency;

import java.time.Duration;
import java.util.Optional;

public interface IdempotencyCacheService {
    void save(String key, String value, Duration ttl);

    Optional<String> get(String key);
}

