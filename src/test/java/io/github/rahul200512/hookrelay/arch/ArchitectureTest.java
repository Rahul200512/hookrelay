package io.github.rahul200512.hookrelay.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/** The layering that the package names promise, enforced. */
@AnalyzeClasses(
        packages = "io.github.rahul200512.hookrelay",
        importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.DoNotIncludeGeneratedCode.class})
class ArchitectureTest {

    /**
     * Skips the classes Spring's ahead-of-time processing writes.
     *
     * <p>Running the native profile leaves generated bean registrations in {@code target}
     * that wire every layer to every other, because that is what a generated bean factory
     * does. They are not the code these rules are about, and a build that passes or fails
     * depending on whether someone ran the native profile earlier is worse than no rule.
     */
    static final class DoNotIncludeGeneratedCode implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("__") && !location.contains("spring-aot");
        }
    }

    private static final String ROOT = "io.github.rahul200512.hookrelay.";

    @ArchTest
    static final ArchRule domain_depends_on_nothing_above_it = noClasses()
            .that().resideInAPackage(ROOT + "domain..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "api..", ROOT + "delivery..", ROOT + "events..",
                    ROOT + "sink..", ROOT + "tenancy..", ROOT + "security..", ROOT + "config..");

    /**
     * Encryption sits below the domain rather than beside it. The entities have to name a
     * converter to have their secrets encrypted at rest, and that must not be an excuse
     * for the domain to start reaching into the web layer's package.
     */
    @ArchTest
    static final ArchRule crypto_is_the_bottom_layer = noClasses()
            .that().resideInAPackage(ROOT + "crypto..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "api..", ROOT + "delivery..", ROOT + "domain..",
                    ROOT + "events..", ROOT + "sink..", ROOT + "tenancy..", ROOT + "security..", ROOT + "config..");

    @ArchTest
    static final ArchRule nothing_reaches_into_the_api_layer = noClasses()
            .that().resideOutsideOfPackage(ROOT + "api..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + "api..");

    @ArchTest
    static final ArchRule the_delivery_engine_does_not_know_about_http_controllers = noClasses()
            .that().resideInAPackage(ROOT + "delivery..")
            .should().beAnnotatedWith(RestController.class);

    @ArchTest
    static final ArchRule controllers_live_in_api_or_sink = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAnyPackage(ROOT + "api..", ROOT + "sink..");
}
