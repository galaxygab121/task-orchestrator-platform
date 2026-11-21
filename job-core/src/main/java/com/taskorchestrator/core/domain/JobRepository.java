package com.taskorchestrator.core.domain;

import com.taskorchestrator.contracts.JobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    long countByStatusIn(Collection<JobStatus> statuses);

    /**
     * DB-backed priority queue: highest priority first, then oldest. SKIP LOCKED lets several scheduler
     * instances run concurrently without blocking on (or double-dispatching) the same rows.
     */
    @Query(value = """
            select * from jobs
            where status = 'SUBMITTED'
            order by priority desc, created_at asc, id asc
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Job> lockNextSubmitted(@Param("limit") int limit);

    @Query(value = """
            select * from jobs
            where status = 'RUNNING' and lease_expires_at < :now
            order by lease_expires_at asc
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Job> lockExpiredLeases(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Heartbeat. Deliberately does not bump {@code version}: it only extends the lease of the current
     * owner, and returns 0 if the job was reclaimed or finished meanwhile (the worker then stops caring).
     */
    @Modifying
    @Query(value = """
            update jobs set lease_expires_at = :leaseExpiresAt
            where id = :id and status = 'RUNNING' and lease_owner = :owner and attempt = :attempt
            """, nativeQuery = true)
    int renewLease(@Param("id") UUID id, @Param("owner") String owner, @Param("attempt") int attempt,
            @Param("leaseExpiresAt") Instant leaseExpiresAt);

    /** Transaction-scoped Postgres advisory lock; released automatically at commit/rollback. */
    @Query(value = "select pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryAdvisoryXactLock(@Param("key") long key);
}
