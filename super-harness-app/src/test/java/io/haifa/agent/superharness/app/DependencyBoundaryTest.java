package io.haifa.agent.superharness.app;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class DependencyBoundaryTest {
    @Test
    void productionCodeDoesNotImportHaifaInternalOrImplementationPackages() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.haifa.agent.superharness");

        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.haifa.agent.sdk.internal..",
                        "io.haifa.agent.runtime.core..",
                        "io.haifa.agent.tool.core..",
                        "io.haifa.agent.policy.core..",
                        "io.haifa.agent.credential.core..",
                        "io.haifa.agent.project.core..",
                        "io.haifa.agent.execution.core..")
                .because("Super Harness must consume public SDK, API, and capability boundaries only")
                .check(productionClasses);
    }
}
