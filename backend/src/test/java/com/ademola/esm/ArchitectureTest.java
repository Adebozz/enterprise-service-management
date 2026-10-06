package com.ademola.esm;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module boundaries of the modular monolith (ADR-001), enforced rather than claimed. Runs on the
 * production classes only.
 */
@AnalyzeClasses(packages = "com.ademola.esm", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Feature modules may depend on each other in one direction only (events break cycles). */
    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            slices().matching("com.ademola.esm.(*)..").should().beFreeOfCycles();

    /** The same inside the largest module: ticket's sub-packages form a one-way graph. */
    @ArchTest
    static final ArchRule ticketPackagesAreFreeOfCycles =
            slices().matching("com.ademola.esm.ticket.(*)..").should().beFreeOfCycles();

    /** Shared infrastructure must not know about any feature. */
    @ArchTest
    static final ArchRule commonDependsOnNoFeature = noClasses()
            .that()
            .resideInAPackage("com.ademola.esm.common..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.ademola.esm.auth..",
                    "com.ademola.esm.user..",
                    "com.ademola.esm.team..",
                    "com.ademola.esm.audit..",
                    "com.ademola.esm.ticket..",
                    "com.ademola.esm.demo..");

    /** Controllers translate HTTP; data access and rules belong to services. */
    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .areAssignableTo(Repository.class);
}
