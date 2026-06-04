package com.tsinghua.sample.patient.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FieldDefinition {
    private String id;
    private String label;
    private FieldType type;
    private String placeholder;
    private boolean required;
    private List<String> options;
    private String unit;
    private FieldCondition visibleWhen;

    public FieldDefinition() {
    }

    public FieldDefinition(String id, String label, FieldType type, String placeholder,
                           boolean required, List<String> options, String unit,
                           FieldCondition visibleWhen) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.placeholder = placeholder;
        this.required = required;
        this.options = options == null ? null : new ArrayList<>(options);
        this.unit = unit;
        this.visibleWhen = visibleWhen;
    }

    public static FieldDefinition of(String id, String label, FieldType type) {
        return new FieldDefinition(id, label, type, null, false, null, null, null);
    }

    public FieldDefinition withPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        return this;
    }

    public FieldDefinition required() {
        this.required = true;
        return this;
    }

    public FieldDefinition withOptions(String... values) {
        this.options = new ArrayList<>();
        Collections.addAll(this.options, values);
        return this;
    }

    public FieldDefinition withUnit(String unit) {
        this.unit = unit;
        return this;
    }

    public FieldDefinition visibleWhen(String fieldId, String equals) {
        this.visibleWhen = new FieldCondition(fieldId, equals);
        return this;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public FieldType getType() {
        return type;
    }

    public void setType(FieldType type) {
        this.type = type;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public List<String> getOptions() {
        return options == null ? null : new ArrayList<>(options);
    }

    public void setOptions(List<String> options) {
        this.options = options == null ? null : new ArrayList<>(options);
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public FieldCondition getVisibleWhen() {
        return visibleWhen;
    }

    public void setVisibleWhen(FieldCondition visibleWhen) {
        this.visibleWhen = visibleWhen;
    }
}
