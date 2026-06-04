package com.tsinghua.sample.patient.model;

import java.util.ArrayList;
import java.util.List;

public class DepartmentTemplate {
    private String id;
    private String name;
    private String description;
    private boolean isBuiltIn;
    private List<FormSection> sections;

    public DepartmentTemplate() {
    }

    public DepartmentTemplate(String id, String name, String description, boolean isBuiltIn,
                              List<FormSection> sections) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.isBuiltIn = isBuiltIn;
        this.sections = sections == null ? new ArrayList<>() : new ArrayList<>(sections);
    }

    public DepartmentTemplate copyWith(String id, String name, String description, boolean builtIn,
                                       List<FormSection> sections) {
        return new DepartmentTemplate(id, name, description, builtIn, sections);
    }

    public FieldDefinition fieldById(String fieldId) {
        if (fieldId == null) return null;
        for (FormSection section : getSections()) {
            for (FieldDefinition field : section.getFields()) {
                if (fieldId.equals(field.getId())) {
                    return field;
                }
            }
        }
        return null;
    }

    public List<String> allFieldIds() {
        List<String> ids = new ArrayList<>();
        for (FormSection section : getSections()) {
            for (FieldDefinition field : section.getFields()) {
                ids.add(field.getId());
            }
        }
        return ids;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isBuiltIn() {
        return isBuiltIn;
    }

    public void setBuiltIn(boolean builtIn) {
        isBuiltIn = builtIn;
    }

    public List<FormSection> getSections() {
        if (sections == null) {
            sections = new ArrayList<>();
        }
        return sections;
    }

    public void setSections(List<FormSection> sections) {
        this.sections = sections == null ? new ArrayList<>() : new ArrayList<>(sections);
    }
}
