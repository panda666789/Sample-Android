package com.tsinghua.sample.patient.model;

public final class PatientValueFormatter {
    private PatientValueFormatter() {
    }

    public static String dateInputText(FieldValue value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return dateInputText(value.asString());
    }

    public static String dateInputText(String storedValue) {
        if (storedValue == null) {
            return "";
        }
        String trimmed = storedValue.trim();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return trimmed.substring(0, 10);
        }
        return trimmed;
    }

    public static FieldValue dateFieldValueFromInput(String input) {
        if (input == null) {
            return FieldValue.date("");
        }
        String trimmed = input.trim();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return FieldValue.date(trimmed + "T00:00:00Z");
        }
        if (isIsoInstant(trimmed)) {
            return FieldValue.date(trimmed);
        }
        return FieldValue.date("");
    }

    public static boolean isValidDateInput(String input) {
        if (input == null || input.trim().isEmpty()) {
            return true;
        }
        String trimmed = input.trim();
        return trimmed.matches("\\d{4}-\\d{2}-\\d{2}") || isIsoInstant(trimmed);
    }

    public static String displayValue(FieldValue value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (FieldValue.TYPE_DATE.equals(value.getType())) {
            return dateInputText(value);
        }
        if (FieldValue.TYPE_STRING_ARRAY.equals(value.getType())) {
            return join(value.asStringArray(), "、");
        }
        if (FieldValue.TYPE_BOOL.equals(value.getType())) {
            return "true".equalsIgnoreCase(value.asString()) ? "是" : "否";
        }
        return nullToEmpty(value.asString());
    }

    private static String join(java.util.List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(separator);
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isIsoInstant(String value) {
        return value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
    }
}
