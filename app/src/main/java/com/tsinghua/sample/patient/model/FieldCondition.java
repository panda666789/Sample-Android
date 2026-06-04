package com.tsinghua.sample.patient.model;

import java.util.Objects;

public class FieldCondition {
    private String fieldId;
    private String equals;

    public FieldCondition() {
    }

    public FieldCondition(String fieldId, String equals) {
        this.fieldId = fieldId;
        this.equals = equals;
    }

    public String getFieldId() {
        return fieldId;
    }

    public String getEquals() {
        return equals;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FieldCondition)) return false;
        FieldCondition that = (FieldCondition) obj;
        return Objects.equals(fieldId, that.fieldId) && Objects.equals(equals, that.equals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldId, equals);
    }
}
