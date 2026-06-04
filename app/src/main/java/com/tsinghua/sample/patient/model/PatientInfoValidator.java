package com.tsinghua.sample.patient.model;

import java.util.Map;

public final class PatientInfoValidator {
    private PatientInfoValidator() {
    }

    public static Result validate(DepartmentTemplate template, Map<String, FieldValue> fields) {
        if (template == null) {
            return Result.invalid("模板为空");
        }
        FieldValue patientId = fields == null ? null : fields.get("patientId");
        if (patientId == null || patientId.isEmpty()) {
            FieldDefinition idField = template.fieldById("patientId");
            String label = idField == null ? "病历号" : idField.getLabel();
            return Result.invalid("请输入" + label);
        }

        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                if (!field.isRequired() || "patientId".equals(field.getId())) {
                    continue;
                }
                if (!isVisible(field, fields)) {
                    continue;
                }
                FieldValue value = fields == null ? null : fields.get(field.getId());
                if (value == null || value.isEmpty()) {
                    return Result.invalid("请填写必填项: " + field.getLabel());
                }
            }
        }
        return Result.valid();
    }

    public static boolean isVisible(FieldDefinition field, Map<String, FieldValue> fields) {
        FieldCondition condition = field.getVisibleWhen();
        if (condition == null) {
            return true;
        }
        FieldValue current = fields == null ? null : fields.get(condition.getFieldId());
        return current != null && condition.getEquals().equals(current.asString());
    }

    public static class Result {
        private final boolean valid;
        private final String message;

        private Result(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static Result valid() {
            return new Result(true, "");
        }

        public static Result invalid(String message) {
            return new Result(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }
    }
}
