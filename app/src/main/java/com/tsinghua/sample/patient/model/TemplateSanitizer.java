package com.tsinghua.sample.patient.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TemplateSanitizer {
    private TemplateSanitizer() {
    }

    public static void sanitizeForSave(DepartmentTemplate template) {
        if (template == null) return;
        if (isBlank(template.getName())) {
            template.setName("未命名模板");
        }
        ensureRequiredPatientId(template);
        for (FormSection section : template.getSections()) {
            List<FieldDefinition> fields = section.getFields();
            for (int i = 0; i < fields.size(); i++) {
                fields.set(i, sanitizeField(fields.get(i)));
            }
        }
        ensureUniqueFieldIds(template);
        removeConditionCycles(template);
    }

    public static FieldDefinition sanitizeField(FieldDefinition field) {
        if (field == null) {
            return FieldDefinition.of("field", "采集项", FieldType.TEXT);
        }
        if ("patientId".equals(field.getId())) {
            field.setLabel(isBlank(field.getLabel()) ? "病历号" : field.getLabel());
            field.setType(FieldType.TEXT);
            field.setPlaceholder(isBlank(field.getPlaceholder()) ? "请输入病历号" : field.getPlaceholder());
            field.setRequired(true);
            field.setOptions(null);
            field.setUnit(null);
            field.setVisibleWhen(null);
            return field;
        }

        FieldType type = field.getType() == null ? FieldType.TEXT : field.getType();
        field.setType(type);
        if (isBlank(field.getId())) {
            field.setId("field_" + System.currentTimeMillis());
        }
        if (isBlank(field.getLabel())) {
            field.setLabel("采集项");
        }

        if (type.needsOptions()) {
            List<String> options = cleanOptions(field.getOptions());
            if (options.isEmpty()) {
                options.add("未填写");
            }
            field.setOptions(options);
            if (!hasRealOption(options)) {
                field.setRequired(false);
            }
        } else {
            field.setOptions(null);
        }

        if ((type == FieldType.PHOTO || type == FieldType.BLOOD_PRESSURE) && field.isRequired()) {
            field.setRequired(false);
        }
        return field;
    }

    public static void ensureRequiredPatientId(DepartmentTemplate template) {
        if (template == null) return;
        FieldDefinition patientId = template.fieldById("patientId");
        if (patientId != null) {
            sanitizeField(patientId);
            return;
        }

        FormSection targetSection;
        if (template.getSections().isEmpty()) {
            targetSection = new FormSection("basic", "基本信息", new ArrayList<>());
            template.getSections().add(targetSection);
        } else {
            targetSection = template.getSections().get(0);
        }
        targetSection.getFields().add(0, FieldDefinition.of("patientId", "病历号", FieldType.TEXT)
                .withPlaceholder("请输入病历号")
                .required());
    }

    private static List<String> cleanOptions(List<String> options) {
        List<String> result = new ArrayList<>();
        if (options == null) return result;
        for (String option : options) {
            if (option == null) continue;
            String trimmed = option.trim();
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static boolean hasRealOption(List<String> options) {
        if (options == null) return false;
        for (String option : options) {
            if (!"未填写".equals(option)) {
                return true;
            }
        }
        return false;
    }

    private static void ensureUniqueFieldIds(DepartmentTemplate template) {
        Set<String> used = new HashSet<>();
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                String id = field.getId();
                if (isBlank(id)) {
                    id = "field_" + System.currentTimeMillis();
                }
                if ("patientId".equals(id) && !used.contains(id)) {
                    used.add(id);
                    continue;
                }
                String uniqueId = id;
                int suffix = 2;
                while (used.contains(uniqueId)) {
                    uniqueId = id + "_" + suffix;
                    suffix++;
                }
                if (!uniqueId.equals(field.getId())) {
                    field.setId(uniqueId);
                }
                used.add(uniqueId);
            }
        }
    }

    private static void removeConditionCycles(DepartmentTemplate template) {
        Set<String> cycleFieldIds = new HashSet<>();
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                if (hasConditionCycle(template, field.getId(), new HashSet<>())) {
                    cycleFieldIds.add(field.getId());
                }
            }
        }
        if (cycleFieldIds.isEmpty()) {
            return;
        }
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                if (cycleFieldIds.contains(field.getId())) {
                    field.setVisibleWhen(null);
                }
            }
        }
    }

    private static boolean hasConditionCycle(DepartmentTemplate template, String fieldId, Set<String> seen) {
        if (isBlank(fieldId)) {
            return false;
        }
        if (!seen.add(fieldId)) {
            return true;
        }
        FieldDefinition field = template.fieldById(fieldId);
        if (field == null || field.getVisibleWhen() == null) {
            return false;
        }
        return hasConditionCycle(template, field.getVisibleWhen().getFieldId(), seen);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
