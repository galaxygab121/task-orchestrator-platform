package com.taskorchestrator.core.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            select * from outbox_events
            where published_at is null and available_at <= :now
            order by id asc
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockPublishable(@Param("now") Instant now, @Param("limit") int limit);

    long countByPublishedAtIsNull();

    List<OutboxEvent> findByAggregateIdOrderByIdAsc(UUID aggregateId);
}
