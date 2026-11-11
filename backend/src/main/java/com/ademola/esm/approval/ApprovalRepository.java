package com.ademola.esm.approval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    Optional<Approval> findByWorkItemId(UUID workItemId);

    List<Approval> findByStatusOrderByCreatedAtAsc(ApprovalStatus status);
}
