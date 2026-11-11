package com.ademola.esm.catalogue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CatalogueItemRepository extends JpaRepository<CatalogueItem, UUID> {

    List<CatalogueItem> findAllByOrderByNameAsc();

    List<CatalogueItem> findAllByActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
