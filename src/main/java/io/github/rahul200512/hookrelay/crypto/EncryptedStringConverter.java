package io.github.rahul200512.hookrelay.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Applied to the signing-secret columns, so nothing in the domain has to remember to encrypt. */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final SecretCrypto crypto;

    public EncryptedStringConverter(SecretCrypto crypto) {
        this.crypto = crypto;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : crypto.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : crypto.decrypt(dbData);
    }
}
