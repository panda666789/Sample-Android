package com.tsinghua.sample.patient.icd10;

public class ICD10Diagnosis {
    private String code;
    private String name;
    private String source;

    public ICD10Diagnosis() {
    }

    public ICD10Diagnosis(String code, String name, String source) {
        this.code = code;
        this.name = name;
        this.source = source;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getSource() {
        return source;
    }

    public String displayText() {
        return code + " " + name;
    }

    public String storageValue() {
        return displayText();
    }
}
