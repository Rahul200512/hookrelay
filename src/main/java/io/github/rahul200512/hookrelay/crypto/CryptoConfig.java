package io.github.rahul200512.hookrelay.crypto;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** Hands the cipher to the JPA converter, which cannot be given one by injection. */
@Component
public class CryptoConfig implements InitializingBean {

    private final SecretCrypto crypto;

    public CryptoConfig(SecretCrypto crypto) {
        this.crypto = crypto;
    }

    @Override
    public void afterPropertiesSet() {
        EncryptedStringConverter.install(crypto);
    }
}
