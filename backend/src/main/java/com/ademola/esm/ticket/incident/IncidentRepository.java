package com.ademola.esm.ticket.incident;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /** The incidents a problem explains, oldest first (the order they were reported). */
    List<Incident> findByProblemIdOrderByCreatedAtAsc(UUID problemId);
}
