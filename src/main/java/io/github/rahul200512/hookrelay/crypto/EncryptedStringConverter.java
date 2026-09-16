package io.github.rahul200512.hookrelay.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Applied to the signing-secret columns, so nothing in the domain has to remember to
 * encrypt.
 *
 * <p>The cipher is held statically rather than injected, which deserves an explanation.
 * A JPA converter is not always built by Spring: Hibernate constructs it through its bean
 * container at runtime, and Spring's ahead-of-time processor constructs it reflectively
 * through the no-argument constructor that every converter is required to have. The
 * injected version worked perfectly on the JVM and failed the moment a native image was
 * attempted, with {@code NoSuchMethodException: <init>()} — a dependency that only
 * resolves under one of the two ways this class gets instantiated is a latent bug, not a
 * native-image quirk.
 *
 * <p>So the cipher is installed once at startup by {@link CryptoConfig} and every
 * instance shares it, whoever built it.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static volatile SecretCrypto crypto;

    /** Required by JPA, and used by Hibernate and by ahead-of-time processing. */
    public EncryptedStringConverter() {}

    static void install(SecretCrypto instance) {
        crypto = instance;
    }

    private static SecretCrypto crypto() {
        SecretCrypto instance = crypto;
        if (instance == null) {
            throw new IllegalStateException("secret encryption is not initialised; "
                    + "no SecretCrypto has been installed on EncryptedStringConverter");
        }
        return instance;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : crypto().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : crypto().decrypt(dbData);
    }
}
