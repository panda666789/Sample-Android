package com.tsinghua.sample.patient.model;

import java.util.ArrayList;
import java.util.List;

public class FormSection {
    private String id;
    private String title;
    private List<FieldDefinition> fields;

    public FormSection() {
    }

    public FormSection(String id, String title, List<FieldDefinition> fields) {
        this.id = id;
        this.title = title;
        this.fields = fields == null ? new ArrayList<>() : new ArrayList<>(fields);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<FieldDefinition> getFields() {
        if (fields == null) {
            fields = new ArrayList<>();
        }
        return fields;
    }

    public void setFields(List<FieldDefinition> fields) {
        this.fields = fields == null ? new ArrayList<>() : new ArrayList<>(fields);
    }
}
