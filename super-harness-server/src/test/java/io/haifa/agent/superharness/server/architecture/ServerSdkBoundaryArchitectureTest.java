package io.haifa.agent.superharness.server.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ServerSdkBoundaryArchitectureTest {
    @Test
    void productionCodeUsesOnlyPublicHaifaApis() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.haifa.agent.superharness.server");

        noClasses()
                .that()
                .resideInAPackage("io.haifa.agent.superharness.server..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.haifa.agent.runtime.core..", "io.haifa.agent.sdk.internal..")
                .check(classes);
    }
}
