package com.example.jobqueue.worker;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.limits.LimitRepository;
import com.example.jobqueue.repo.JobRepository;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hands the next job to a worker. Without concurrency limits this is the plain SKIP LOCKED claim. When any limit
 * exists, claims are serialised across all workers by a short advisory lock and skip job types that are already at
 * their limit, which makes the limit exact: two workers can never both start the last free slot.
 */
@Service
public class ClaimService {

    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final JobRepository jobs;
    private final LimitRepository limits;
    private final TransactionTemplate tx;

    private volatile boolean checked;
    private volatile boolean anyLimits;
    private volatile long checkedAtNanos;

    public ClaimService(JobRepository jobs, LimitRepository limits, TransactionTemplate tx) {
        this.jobs = jobs;
        this.limits = limits;
        this.tx = tx;
    }

    public Optional<Job> claimNext(String workerId) {
        if (!limitsConfigured()) {
            return jobs.claimNext(workerId);
        }
        Optional<Job> claimed = tx.execute(status -> {
            jobs.lockClaims();
            return jobs.claimNextWithLimits(workerId);
        });
        return claimed == null ? Optional.empty() : claimed;
    }

    /** Forget what we know about the limits table (it is re-read on the next claim). */
    public void invalidateCache() {
        checked = false;
    }

    // Whether any limit exists is cached for a couple of seconds, so unrestricted workloads pay nothing extra.
    // Once limits exist the claim query itself reads the table, so the limits it applies are always current.
    private boolean limitsConfigured() {
        long now = System.nanoTime();
        if (!checked || now - checkedAtNanos > CACHE_NANOS) {
            anyLimits = limits.count() > 0;
            checkedAtNanos = now;
            checked = true;
        }
        return anyLimits;
    }
}
