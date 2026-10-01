package com.ademola.esm.ticket.category;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    boolean existsByCode(String code);

    /** Reference data: small enough (tens of rows) to load whole and assemble into a tree in memory. */
    List<Category> findAllByOrderByNameAsc();
}
