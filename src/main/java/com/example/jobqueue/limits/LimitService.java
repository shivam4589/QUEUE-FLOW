package com.example.jobqueue.limits;

import com.example.jobqueue.worker.ClaimService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LimitService {

    private final LimitRepository repository;
    private final ClaimService claims;

    public LimitService(LimitRepository repository, ClaimService claims) {
        this.repository = repository;
        this.claims = claims;
    }

    public TypeLimit set(String type, int maxConcurrent) {
        String trimmed = type.trim();
        repository.upsert(trimmed, maxConcurrent);
        claims.invalidateCache(); // other worker processes pick the change up within a couple of seconds
        return repository.find(trimmed).orElseThrow();
    }

    public List<TypeLimit> list() {
        return repository.list();
    }

    public TypeLimit remove(String type) {
        TypeLimit existing = repository.find(type).orElseThrow(() -> new LimitNotFoundException(type));
        repository.delete(type);
        claims.invalidateCache();
        return existing;
    }
}
