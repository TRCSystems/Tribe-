package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Column values marked with this are stored encrypted (see {@link TokenCipher}) and decrypted when loaded. */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String plain) {
        return TokenCipher.encrypt(plain);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return TokenCipher.decrypt(stored);
    }
}
