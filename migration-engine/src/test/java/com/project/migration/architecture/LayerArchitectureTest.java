package com.project.migration.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Clean Architecture rules from CLAUDE.md: domain and application are framework-free.
 */
@AnalyzeClasses(packages = "com.project.migration", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnlyOnJdk = classes()
            .that().resideInAPackage("com.project.migration.domain..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.project.migration.domain..")
            .because("the domain layer must not depend on external libraries or frameworks");

    @ArchTest
    static final ArchRule applicationDependsOnlyOnDomainAndJdk = classes()
            .that().resideInAPackage("com.project.migration.application..")
            .should().onlyDependOnClassesThat()
            // javax.xml / org.w3c.dom / org.xml.sax: the JDK's own java.xml module (XmlPageSourceCleaner)
            .resideInAnyPackage("java..", "javax.xml..", "org.w3c.dom..", "org.xml.sax..",
                    "com.project.migration.domain..", "com.project.migration.application..")
            .because("external integrations are reached through domain ports only")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule portsAreInterfaces = classes()
            .that().resideInAPackage("com.project.migration.domain.port..")
            .should().beInterfaces();

    @ArchTest
    static final ArchRule portsAreNamedPort = classes()
            .that().resideInAPackage("com.project.migration.domain.port..")
            .should().haveSimpleNameEndingWith("Port");

    @ArchTest
    static final ArchRule nothingDependsOnInfrastructure = noClasses()
            .that().resideOutsideOfPackage("com.project.migration.infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("com.project.migration.infrastructure..");
}
