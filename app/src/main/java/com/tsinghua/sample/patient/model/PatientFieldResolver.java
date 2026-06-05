package com.tsinghua.sample.patient.model;

import java.util.Map;

public final class PatientFieldResolver {
    public static final String PATIENT_NAME_FIELD_ID = "patientName";

    private PatientFieldResolver() {
    }

    public static void normalizePatientName(DepartmentTemplate template, Map<String, FieldValue> fields) {
        if (template == null || fields == null) {
            return;
        }
        FieldValue existing = fields.get(PATIENT_NAME_FIELD_ID);
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                if (!isPatientNameLabel(field.getLabel())) {
                    continue;
                }
                FieldValue value = fields.get(field.getId());
                if (value != null && !value.isEmpty()) {
                    fields.put(PATIENT_NAME_FIELD_ID, value);
                    return;
                }
            }
        }
    }

    public static String patientName(PatientInfoData data, DepartmentTemplate template, String fallback) {
        String canonical = fieldText(data, PATIENT_NAME_FIELD_ID);
        if (!isBlank(canonical)) {
            return canonical;
        }
        if (template != null && data != null) {
            for (FormSection section : template.getSections()) {
                for (FieldDefinition field : section.getFields()) {
                    if (!isPatientNameLabel(field.getLabel())) {
                        continue;
                    }
                    String text = fieldText(data, field.getId());
                    if (!isBlank(text)) {
                        return text;
                    }
                }
            }
        }
        return fallback == null ? "" : fallback;
    }

    public static boolean isPatientNameLabel(String label) {
        if (label == null) {
            return false;
        }
        String trimmed = label.trim();
        return "姓名".equals(trimmed) || "患者姓名".equals(trimmed);
    }

    private static String fieldText(PatientInfoData data, String fieldId) {
        if (data == null || fieldId == null) {
            return "";
        }
        FieldValue value = data.getFields().get(fieldId);
        String text = value == null ? "" : value.asString();
        return text == null ? "" : text.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
