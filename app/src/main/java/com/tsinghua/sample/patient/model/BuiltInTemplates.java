package com.tsinghua.sample.patient.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BuiltInTemplates {
    private BuiltInTemplates() {
    }

    public static DepartmentTemplate cardioCerebrovascular() {
        List<FormSection> sections = new ArrayList<>();

        sections.add(new FormSection("basic", "基本信息", Arrays.asList(
                FieldDefinition.of("patientId", "病历号", FieldType.TEXT)
                        .withPlaceholder("请输入病历号")
                        .required(),
                FieldDefinition.of("patientName", "姓名", FieldType.TEXT)
                        .withPlaceholder("请输入姓名")
                        .required(),
                FieldDefinition.of("gender", "性别", FieldType.SEGMENTED)
                        .withOptions("未填写", "男", "女"),
                FieldDefinition.of("age", "年龄", FieldType.NUMBER)
                        .withPlaceholder("请输入年龄")
                        .withUnit("岁"),
                FieldDefinition.of("birthDate", "出生日期", FieldType.DATE),
                FieldDefinition.of("examDate", "检查日期", FieldType.DATE),
                FieldDefinition.of("height", "身高", FieldType.DECIMAL)
                        .withPlaceholder("如: 170")
                        .withUnit("cm"),
                FieldDefinition.of("weight", "体重", FieldType.DECIMAL)
                        .withPlaceholder("如: 65")
                        .withUnit("kg")
        )));

        sections.add(new FormSection("vital_signs", "生命体征", Arrays.asList(
                FieldDefinition.of("bilirubinLevel", "黄疸值", FieldType.DECIMAL)
                        .withPlaceholder("输入黄疸值，如: 1.2")
                        .withUnit("mg/dl"),
                FieldDefinition.of("bloodPressure", "血压", FieldType.BLOOD_PRESSURE)
                        .withUnit("mmHg"),
                FieldDefinition.of("heartRate", "心率", FieldType.DECIMAL)
                        .withPlaceholder("输入心率值，如: 72")
                        .withUnit("bpm"),
                FieldDefinition.of("oxygenSaturation", "血氧饱和度", FieldType.DECIMAL)
                        .withPlaceholder("输入血氧饱和度，如: 98")
                        .withUnit("%"),
                FieldDefinition.of("bloodSugar", "血糖", FieldType.DECIMAL)
                        .withPlaceholder("输入血糖值，如: 5.6")
                        .withUnit("mmol/L")
        )));

        sections.add(new FormSection("diagnosis", "诊断信息", Arrays.asList(
                FieldDefinition.of("diagnoses", "诊断列表", FieldType.ICD10_DIAGNOSIS_LIST)
                        .withPlaceholder("添加 ICD-10 编码或诊断名称"),
                FieldDefinition.of("diagnosisDetails", "诊断补充说明", FieldType.TEXT_AREA)
                        .withPlaceholder("如: 病程、分型、分级、并发症等")
        )));

        sections.add(new FormSection("lab", "化验指标", Arrays.asList(
                FieldDefinition.of("hemoglobin", "血红蛋白", FieldType.DECIMAL)
                        .withPlaceholder("如: 130")
                        .withUnit("g/L"),
                FieldDefinition.of("serumCreatinine", "血肌酐", FieldType.DECIMAL)
                        .withPlaceholder("如: 80")
                        .withUnit("μmol/L"),
                FieldDefinition.of("eGFR", "eGFR", FieldType.DECIMAL)
                        .withPlaceholder("如: 90")
                        .withUnit("ml/min"),
                FieldDefinition.of("ldl", "低密度脂蛋白", FieldType.DECIMAL)
                        .withPlaceholder("如: 2.5")
                        .withUnit("mmol/L"),
                FieldDefinition.of("troponinI", "超敏肌钙蛋白I", FieldType.DECIMAL)
                        .withPlaceholder("如: 0.01")
                        .withUnit("ng/ml"),
                FieldDefinition.of("ntProBNP", "NT-proBNP", FieldType.DECIMAL)
                        .withPlaceholder("如: 125")
                        .withUnit("pg/ml")
        )));

        sections.add(new FormSection("echo", "超声心动图", Arrays.asList(
                FieldDefinition.of("ivs", "IVS", FieldType.DECIMAL)
                        .withPlaceholder("室间隔厚度")
                        .withUnit("mm"),
                FieldDefinition.of("pw", "PW", FieldType.DECIMAL)
                        .withPlaceholder("后壁厚度")
                        .withUnit("mm"),
                FieldDefinition.of("lvedd", "LVEDD", FieldType.DECIMAL)
                        .withPlaceholder("左室舒张末内径")
                        .withUnit("mm"),
                FieldDefinition.of("lvesd", "LVESD", FieldType.DECIMAL)
                        .withPlaceholder("左室收缩末内径")
                        .withUnit("mm"),
                FieldDefinition.of("lvedv", "LVEDV", FieldType.DECIMAL)
                        .withPlaceholder("左室舒张末容积")
                        .withUnit("ml"),
                FieldDefinition.of("lvesv", "LVESV", FieldType.DECIMAL)
                        .withPlaceholder("左室收缩末容积")
                        .withUnit("ml"),
                FieldDefinition.of("avvti", "AVVTI", FieldType.DECIMAL)
                        .withPlaceholder("主动脉瓣速度时间积分")
        )));

        sections.add(new FormSection("notes", "备注信息", Collections.singletonList(
                FieldDefinition.of("doctorNotes", "医生备注", FieldType.TEXT_AREA)
        )));

        return new DepartmentTemplate(
                "cardio_cerebrovascular",
                "心脑血管通用",
                "面向多科室复用的心脑血管采集模板，使用中国医保 ICD-10 诊断编码",
                true,
                sections
        );
    }

    public static List<DepartmentTemplate> all() {
        return Collections.singletonList(cardioCerebrovascular());
    }
}
