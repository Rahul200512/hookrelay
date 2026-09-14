package io.github.rahul200512.hookrelay.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Raw keys are shown once; only their SHA-256 is stored. */
public final class ApiKeys {

    public static final String PREFIX = "hr_live_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {}

    public record Generated(String raw, String hash, String prefix) {}

    public static Generated generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Generated(raw, hash(raw), raw.substring(0, PREFIX.length() + 6));
    }

    public static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
