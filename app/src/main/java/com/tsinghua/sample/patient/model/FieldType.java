package com.tsinghua.sample.patient.model;

import com.google.gson.annotations.SerializedName;

public enum FieldType {
    @SerializedName("text")
    TEXT("text", "文字输入", false),
    @SerializedName("number")
    NUMBER("number", "输入整数", false),
    @SerializedName("decimal")
    DECIMAL("decimal", "输入数值", false),
    @SerializedName("picker")
    PICKER("picker", "下拉选择", true),
    @SerializedName("segmented")
    SEGMENTED("segmented", "按钮选择", true),
    @SerializedName("multiSelect")
    MULTI_SELECT("multiSelect", "多选", true),
    @SerializedName("date")
    DATE("date", "选择日期", false),
    @SerializedName("textArea")
    TEXT_AREA("textArea", "备注输入", false),
    @SerializedName("bloodPressure")
    BLOOD_PRESSURE("bloodPressure", "血压输入", false),
    @SerializedName("icd10Diagnosis")
    ICD10_DIAGNOSIS("icd10Diagnosis", "ICD-10诊断", false),
    @SerializedName("icd10DiagnosisList")
    ICD10_DIAGNOSIS_LIST("icd10DiagnosisList", "ICD-10诊断列表", false),
    @SerializedName("photo")
    PHOTO("photo", "拍照", false);

    private final String wireName;
    private final String displayName;
    private final boolean needsOptions;

    FieldType(String wireName, String displayName, boolean needsOptions) {
        this.wireName = wireName;
        this.displayName = displayName;
        this.needsOptions = needsOptions;
    }

    public String getWireName() {
        return wireName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean needsOptions() {
        return needsOptions;
    }
}
