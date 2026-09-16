package io.github.rahul200512.hookrelay.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM for the signing secrets, so a leaked database dump does not let someone forge
 * deliveries that every receiver would accept as genuine.
 *
 * <p>Stored form is {@code v1:base64(iv || ciphertext || tag)}. The version prefix is
 * what makes a future key rotation or algorithm change possible: an unprefixed value can
 * only ever be guessed at.
 *
 * <p>The key comes from the environment and is never written anywhere. There is no
 * fallback to plaintext: a service that silently stops encrypting when a variable is
 * missing is worse than one that refuses to start.
 */
public final class SecretCrypto {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public SecretCrypto(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("hookrelay.security.encryption-key must be base64. "
                    + "Generate one with: openssl rand -base64 32", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("hookrelay.security.encryption-key must decode to 32 bytes, got "
                    + raw.length + ". Generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt secret", e);
        }
    }

    public String decrypt(String stored) {
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("stored secret has no known version prefix");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("could not decrypt secret; wrong encryption key?", e);
        }
    }
}
