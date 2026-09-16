package io.github.rahul200512.hookrelay.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCryptoTest {

    private static final String KEY = Base64.getEncoder().encodeToString("hookrelay-test-key-32-bytes-long".getBytes());
    private final SecretCrypto crypto = new SecretCrypto(KEY);

    @Test
    void roundTrips() {
        // Generated, not pasted: a real-looking secret literal in source is what the
        // secret scanner exists to catch, and it would be right to catch it.
        String secret = "whsec_" + Base64.getEncoder().encodeToString("thirty-two-bytes-of-fake-secret!".getBytes());
        String stored = crypto.encrypt(secret);
        assertThat(stored).startsWith("v1:").doesNotContain(secret);
        assertThat(crypto.decrypt(stored)).isEqualTo(secret);
    }

    @Test
    void theSameInputEncryptsDifferentlyEveryTime() {
        // A fresh IV per write, so identical secrets are not identifiable as identical in a dump.
        assertThat(crypto.encrypt("same")).isNotEqualTo(crypto.encrypt("same"));
    }

    @Test
    void tamperingIsDetectedRatherThanDecryptedIntoGarbage() {
        String stored = crypto.encrypt("whsec_abc");
        char[] chars = stored.toCharArray();
        chars[stored.length() - 2] = chars[stored.length() - 2] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> crypto.decrypt(new String(chars)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not decrypt");
    }

    @Test
    void anotherKeyCannotRead() {
        String stored = crypto.encrypt("whsec_abc");
        var other = new SecretCrypto(Base64.getEncoder().encodeToString("a-completely-different-32-byte!!".getBytes()));
        assertThatThrownBy(() -> other.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnversionedValueIsRefusedRatherThanGuessedAt() {
        assertThatThrownBy(() -> crypto.decrypt("whsec_plaintext_from_before_encryption"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version prefix");
    }

    @Test
    void theKeyMustBe32BytesOfBase64() {
        assertThatThrownBy(() -> new SecretCrypto("not base64 at all !!"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("openssl rand");
        assertThatThrownBy(() -> new SecretCrypto(Base64.getEncoder().encodeToString("too short".getBytes())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}
