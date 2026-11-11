package com.ademola.esm.sla;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SlaClockRepository extends JpaRepository<SlaClock, UUID> {

    Optional<SlaClock> findByWorkItemIdAndMetric(UUID workItemId, SlaMetric metric);
}
