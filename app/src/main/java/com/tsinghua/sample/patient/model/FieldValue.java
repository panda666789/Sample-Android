package com.tsinghua.sample.patient.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FieldValue {
    public static final String TYPE_STRING = "string";
    public static final String TYPE_BOOL = "bool";
    public static final String TYPE_STRING_ARRAY = "stringArray";
    public static final String TYPE_DATE = "date";

    private String type;
    private Object value;

    public FieldValue() {
    }

    private FieldValue(String type, Object value) {
        this.type = type;
        this.value = value;
    }

    public static FieldValue string(String value) {
        return new FieldValue(TYPE_STRING, value);
    }

    public static FieldValue bool(boolean value) {
        return new FieldValue(TYPE_BOOL, value);
    }

    public static FieldValue stringArray(List<String> value) {
        return new FieldValue(TYPE_STRING_ARRAY, value == null ? new ArrayList<>() : new ArrayList<>(value));
    }

    public static FieldValue date(String isoDate) {
        return new FieldValue(TYPE_DATE, isoDate);
    }

    public String getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    public String asString() {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    public List<String> asStringArray() {
        if (value instanceof List<?>) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return Collections.emptyList();
    }

    public boolean isEmpty() {
        if (TYPE_STRING_ARRAY.equals(type)) {
            return asStringArray().isEmpty();
        }
        if (TYPE_BOOL.equals(type)) {
            return false;
        }
        String text = asString();
        return text == null || text.trim().isEmpty();
    }
}
