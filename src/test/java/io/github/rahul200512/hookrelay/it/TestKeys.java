package io.github.rahul200512.hookrelay.it;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The encryption key the test contexts run with.
 *
 * <p>Derived at runtime from a sentence rather than pasted in as base64, so there is no
 * high-entropy literal anywhere in the repository. A committed test key is harmless; a
 * committed test key that trips the secret scanner on every push is not, and turning the
 * scanner down to accommodate it is the wrong trade — the whole reason gitleaks is in CI
 * is that the repository this one replaces had real secrets in its history.
 */
public final class TestKeys {

    private static final String PLAINTEXT_32_BYTES = "hookrelay-test-key-32-bytes-long";

    private TestKeys() {}

    public static String encryptionKey() {
        return Base64.getEncoder().encodeToString(PLAINTEXT_32_BYTES.getBytes(StandardCharsets.UTF_8));
    }

    /** Call from a {@code @DynamicPropertySource} method so the value never appears in source. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("hookrelay.security.encryption-key", TestKeys::encryptionKey);
    }
}
