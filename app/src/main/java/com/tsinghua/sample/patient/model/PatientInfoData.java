package com.tsinghua.sample.patient.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PatientInfoData {
    private String templateId;
    private String templateName;
    private String patientId;
    private String sessionId;
    private String recordingStartTime;
    private String submitTime;
    private Map<String, FieldValue> fields;
    private String photoFileName;

    public PatientInfoData() {
    }

    public PatientInfoData(String templateId, String templateName, String patientId,
                           String sessionId, String recordingStartTime, String submitTime,
                           Map<String, FieldValue> fields, String photoFileName) {
        this.templateId = templateId;
        this.templateName = templateName;
        this.patientId = patientId;
        this.sessionId = sessionId;
        this.recordingStartTime = recordingStartTime;
        this.submitTime = submitTime;
        this.fields = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
        this.photoFileName = photoFileName;
    }

    public String formatToText(DepartmentTemplate template) {
        StringBuilder content = new StringBuilder();
        content.append("=== 患者信息记录 ===\n");
        content.append("模板: ").append(nullToEmpty(templateName)).append("\n");
        content.append("患者ID: ").append(nullToEmpty(patientId)).append("\n");
        content.append("录制会话ID: ").append(nullToEmpty(sessionId)).append("\n");
        content.append("录制开始时间(UTC+8): ").append(PatientValueFormatter.utcPlus8DateTimeText(recordingStartTime)).append("\n");
        content.append("信息提交时间(UTC+8): ").append(PatientValueFormatter.utcPlus8DateTimeText(submitTime)).append("\n");

        if (template != null) {
            for (FormSection section : template.getSections()) {
                StringBuilder sectionContent = new StringBuilder();
                for (FieldDefinition field : section.getFields()) {
                    FieldValue value = getFields().get(field.getId());
                    if (value != null && !value.isEmpty()) {
                        sectionContent
                                .append(field.getLabel())
                                .append(": ")
                                .append(PatientValueFormatter.displayValue(value))
                                .append(unitSuffix(field))
                                .append("\n");
                    }

                    if (field.getType() == FieldType.BLOOD_PRESSURE) {
                        String systolic = stringValue(field.getId() + "_systolic");
                        String diastolic = stringValue(field.getId() + "_diastolic");
                        if (!isBlank(systolic) || !isBlank(diastolic)) {
                            sectionContent
                                    .append(field.getLabel())
                                    .append(": ")
                                    .append(isBlank(systolic) ? "--" : systolic)
                                    .append("/")
                                    .append(isBlank(diastolic) ? "--" : diastolic)
                                    .append(" ")
                                    .append(field.getUnit() == null ? "mmHg" : field.getUnit())
                                    .append("\n");
                        }
                    }
                }

                if (sectionContent.length() > 0) {
                    content.append("\n=== ").append(section.getTitle()).append(" ===\n");
                    content.append(sectionContent);
                }
            }
        } else {
            content.append("\n=== 字段数据 ===\n");
            for (Map.Entry<String, FieldValue> entry : getFields().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    content.append(entry.getKey()).append(": ")
                            .append(PatientValueFormatter.displayValue(entry.getValue()))
                            .append("\n");
                }
            }
        }

        if (!isBlank(photoFileName)) {
            content.append("\n照片文件: ").append(photoFileName).append("\n");
        }

        content.append("\n--- 记录结束 ---\n");
        return content.toString();
    }

    private String stringValue(String fieldId) {
        FieldValue value = getFields().get(fieldId);
        return value == null ? null : value.asString();
    }

    private static String unitSuffix(FieldDefinition field) {
        if (field.getType() == FieldType.ICD10_DIAGNOSIS || field.getType() == FieldType.ICD10_DIAGNOSIS_LIST) {
            return "";
        }
        return isBlank(field.getUnit()) ? "" : " " + field.getUnit();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public String getTemplateId() {
        return templateId;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRecordingStartTime() {
        return recordingStartTime;
    }

    public String getSubmitTime() {
        return submitTime;
    }

    public Map<String, FieldValue> getFields() {
        if (fields == null) {
            fields = new LinkedHashMap<>();
        }
        return fields;
    }

    public String getPhotoFileName() {
        return photoFileName;
    }
}
