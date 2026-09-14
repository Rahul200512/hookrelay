package io.github.rahul200512.hookrelay.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Standard Webhooks (standardwebhooks.com) symmetric signing.
 *
 * <pre>
 *   webhook-id:        the delivery id; identical across retries so receivers can dedupe
 *   webhook-timestamp: unix seconds
 *   webhook-signature: "v1,&lt;base64(HMAC-SHA256(secretBytes, id + "." + timestamp + "." + body))&gt;"
 * </pre>
 *
 * Secrets are {@code whsec_} + base64 of 32 random bytes; the HMAC key is the decoded bytes.
 * Any receiver library that speaks the spec can verify what this sends.
 */
public final class WebhookSigner {

    public static final String SECRET_PREFIX = "whsec_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSigner() {}

    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getEncoder().encodeToString(bytes);
    }

    public static String signedContent(String webhookId, long timestampSeconds, String body) {
        return webhookId + "." + timestampSeconds + "." + body;
    }

    /** @return the header value, e.g. {@code v1,K5oZ...=} */
    public static String sign(String secret, String webhookId, long timestampSeconds, String body) {
        byte[] key = decodeSecret(secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sig = mac.doFinal(signedContent(webhookId, timestampSeconds, body).getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Constant-time check of one {@code v1,...} signature against a header that may carry several. */
    public static boolean verify(String secret, String webhookId, long timestampSeconds, String body, String signatureHeader) {
        String expected = sign(secret, webhookId, timestampSeconds, body);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        for (String candidate : signatureHeader.trim().split("\\s+")) {
            byte[] candidateBytes = candidate.getBytes(StandardCharsets.UTF_8);
            if (java.security.MessageDigest.isEqual(expectedBytes, candidateBytes)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] decodeSecret(String secret) {
        String raw = secret.startsWith(SECRET_PREFIX) ? secret.substring(SECRET_PREFIX.length()) : secret;
        return Base64.getDecoder().decode(raw);
    }
}
