package com.certacota.engine.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataConverterTest {

    private final MetadataConverter converter = new MetadataConverter();

    @Test
    void convertToDatabaseColumn_null_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToDatabaseColumn_map_returnsValidJson() throws Exception {
        Map<String, Object> input = Map.of("k", "v");
        String json = converter.convertToDatabaseColumn(input);
        assertThat(json).isNotNull();
        assertThat(json).contains("\"k\"");
        assertThat(json).contains("\"v\"");
        Map<String, Object> roundTripped = converter.convertToEntityAttribute(json);
        assertThat(roundTripped).isEqualTo(input);
    }

    @Test
    void convertToEntityAttribute_null_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_blank_returnsNull() {
        assertThat(converter.convertToEntityAttribute("")).isNull();
        assertThat(converter.convertToEntityAttribute("   ")).isNull();
    }

    @Test
    void convertToEntityAttribute_validJson_returnsMap() {
        String json = "{\"key\":\"value\"}";
        Map<String, Object> result = converter.convertToEntityAttribute(json);
        assertThat(result).isNotNull();
        assertThat(result.get("key")).isEqualTo("value");
    }

    @Test
    void roundTrip_preservesNestedStructure() {
        Map<String, Object> inner = Map.of("inner", 1);
        Map<String, Object> list = Map.of("items", List.of("a", "b"));
        Map<String, Object> input = Map.of("nested", inner, "withList", list);
        String json = converter.convertToDatabaseColumn(input);
        Map<String, Object> result = converter.convertToEntityAttribute(json);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("nested")).isTrue();
        assertThat(result.containsKey("withList")).isTrue();
    }

    @Test
    void convertToEntityAttribute_invalidJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("not-valid-json"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cannot deserialize metadata from JSON string");
    }
}
