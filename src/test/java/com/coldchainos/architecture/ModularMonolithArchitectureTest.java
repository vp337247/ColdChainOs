package com.coldchainos.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Automated Architectural Fitness Functions (ArchUnit).
 *
 * Enforces Modular Monolith isolation and Hexagonal / Clean Architecture boundaries:
 * 1. Pure Domain Isolation: Domain models cannot depend on frameworks (Spring, JPA, Jackson).
 * 2. Cycle-Free Modules: No circular dependencies between high-level bounded contexts.
 */
@AnalyzeClasses(
    packages = "com.coldchainos",
    importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class ModularMonolithArchitectureTest {

    @ArchTest
    public static final ArchRule domainModelsMustNotDependOnFrameworks =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "org.hibernate..",
                "com.fasterxml.jackson.."
            )
            .because("Domain models must remain pure Java business logic with zero framework or persistence coupling.");

    @ArchTest
    public static final ArchRule modulesMustBeFreeOfCyclicDependencies =
        slices()
            .matching("com.coldchainos.(*)..")
            .should().beFreeOfCycles()
            .because("Circular dependencies between architectural modules lead to an unmaintainable Big Ball of Mud.");
}
