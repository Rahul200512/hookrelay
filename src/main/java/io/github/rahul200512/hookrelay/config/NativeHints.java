package io.github.rahul200512.hookrelay.config;

import java.util.UUID;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * What a native image cannot work out for itself.
 *
 * <p>Ahead-of-time compilation keeps only the code it can prove is reachable, and
 * anything reached by reflection or by scanning the classpath at runtime is invisible to
 * that proof. Both of the following failed the first native build and neither shows up on
 * the JVM, where the classpath is still a real thing you can look through at runtime.
 *
 * <ul>
 *   <li><b>Flyway's migrations.</b> It finds them by scanning {@code db/migration}, which
 *       in a native image answers "unsupported protocol: resource". Registering the
 *       pattern puts the SQL files in the binary where the scanner can still see them.
 *   <li><b>Array types Hibernate instantiates reflectively.</b> {@code UUID[]} for the
 *       identifiers and {@code String[]} for an endpoint's {@code event_types} column.
 *       Without these the entity manager cannot be built at all.
 * </ul>
 */
public class NativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("db/migration/*.sql");

        for (Class<?> arrayType : new Class<?>[] {UUID[].class, String[].class, Object[].class}) {
            hints.reflection().registerType(TypeReference.of(arrayType), MemberCategory.values());
        }
    }
}
