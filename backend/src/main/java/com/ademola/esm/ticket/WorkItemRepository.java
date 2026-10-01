package com.ademola.esm.ticket;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Polymorphic: {@code findById} returns the concrete subclass (Incident, ServiceRequest...). */
public interface WorkItemRepository extends JpaRepository<WorkItem, UUID> {}
