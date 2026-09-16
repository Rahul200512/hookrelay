// Verifying a hookrelay delivery in Java, with nothing but the JDK.
//
// The signature covers `id.timestamp.rawBody`, so hash the body exactly as it arrived.
// In Spring, take the body as a String parameter rather than a mapped object, or the
// bytes you verify will not be the bytes that were signed.

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class VerifySignature {

    private static final long TOLERANCE_SECONDS = 5 * 60;

    public static boolean verify(String secret, String id, String timestamp, String signatureHeader, String rawBody)
            throws Exception {
        if (id == null || timestamp == null || signatureHeader == null) {
            return false;
        }
        // Reject anything too old to be a live delivery. Without this check a signature
        // captured once stays valid forever and can be replayed at any time.
        long age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
        if (age > TOLERANCE_SECONDS) {
            return false;
        }

        byte[] key = Base64.getDecoder().decode(secret.replaceFirst("^whsec_", ""));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] digest = mac.doFinal((id + "." + timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8));
        byte[] expected = ("v1," + Base64.getEncoder().encodeToString(digest)).getBytes(StandardCharsets.UTF_8);

        // A rotation sends several signatures, space delimited; any one matching is enough.
        for (String candidate : signatureHeader.trim().split("\\s+")) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private VerifySignature() {}
}
