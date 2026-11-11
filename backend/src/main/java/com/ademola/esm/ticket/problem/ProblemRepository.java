package com.ademola.esm.ticket.problem;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemRepository extends JpaRepository<Problem, UUID> {}
