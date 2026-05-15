package com.certacota.engine.core.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jspecify.annotations.Nullable;

import java.util.Map;

@Converter
public class MetadataConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public @Nullable String convertToDatabaseColumn(@Nullable Map<String, Object> attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize metadata to JSON", e);
        }
    }

    @Override
    public @Nullable Map<String, Object> convertToEntityAttribute(@Nullable String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot deserialize metadata from JSON string", e);
        }
    }
}
