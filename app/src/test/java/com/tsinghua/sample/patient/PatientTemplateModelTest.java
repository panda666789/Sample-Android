package com.tsinghua.sample.patient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.tsinghua.sample.patient.icd10.ICD10Catalog;
import com.tsinghua.sample.patient.icd10.ICD10Diagnosis;
import com.tsinghua.sample.patient.model.BuiltInTemplates;
import com.tsinghua.sample.patient.model.DepartmentTemplate;
import com.tsinghua.sample.patient.model.FieldDefinition;
import com.tsinghua.sample.patient.model.FieldType;
import com.tsinghua.sample.patient.model.FieldValue;
import com.tsinghua.sample.patient.model.FormSection;
import com.tsinghua.sample.patient.model.PatientInfoData;
import com.tsinghua.sample.patient.model.PatientInfoValidator;
import com.tsinghua.sample.patient.model.PatientJson;
import com.tsinghua.sample.patient.model.PatientValueFormatter;
import com.tsinghua.sample.patient.model.TemplateSanitizer;
import com.tsinghua.sample.patient.storage.PatientRecord;
import com.tsinghua.sample.patient.storage.PatientRecordRepository;
import com.tsinghua.sample.patient.storage.TemplateRepository;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PatientTemplateModelTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void builtInTemplatesExposeOnlyCardioCerebrovascularTemplate() {
        List<DepartmentTemplate> templates = BuiltInTemplates.all();

        assertEquals(1, templates.size());
        DepartmentTemplate template = templates.get(0);
        assertEquals("cardio_cerebrovascular", template.getId());
        assertEquals("心脑血管通用", template.getName());
        assertTrue(template.isBuiltIn());
        assertEquals(6, template.getSections().size());

        FieldDefinition diagnoses = template.fieldById("diagnoses");
        assertNotNull(diagnoses);
        assertEquals(FieldType.ICD10_DIAGNOSIS_LIST, diagnoses.getType());
        assertEquals("诊断列表", diagnoses.getLabel());

        FieldDefinition gender = template.fieldById("gender");
        assertNotNull(gender);
        assertEquals(Arrays.asList("未填写", "男", "女"), gender.getOptions());

        FormSection vitalSigns = template.getSections().get(1);
        assertEquals("生命体征", vitalSigns.getTitle());
        FieldDefinition bilirubinLevel = template.fieldById("bilirubinLevel");
        assertNotNull(bilirubinLevel);
        assertEquals("黄疸值", bilirubinLevel.getLabel());
        assertEquals(FieldType.DECIMAL, bilirubinLevel.getType());
        assertEquals("输入黄疸值，如: 1.2", bilirubinLevel.getPlaceholder());
        assertEquals("mg/dl", bilirubinLevel.getUnit());
    }

    @Test
    public void validatorRequiresVisibleRequiredFields() {
        DepartmentTemplate template = BuiltInTemplates.cardioCerebrovascular();
        Map<String, FieldValue> fields = new LinkedHashMap<>();

        PatientInfoValidator.Result missingPatientId = PatientInfoValidator.validate(template, fields);
        assertFalse(missingPatientId.isValid());
        assertEquals("请输入病历号", missingPatientId.getMessage());

        fields.put("patientId", FieldValue.string("MR001"));
        PatientInfoValidator.Result missingName = PatientInfoValidator.validate(template, fields);
        assertFalse(missingName.isValid());
        assertEquals("请填写必填项: 姓名", missingName.getMessage());

        fields.put("patientName", FieldValue.string("张三"));
        PatientInfoValidator.Result valid = PatientInfoValidator.validate(template, fields);
        assertTrue(valid.isValid());
    }

    @Test
    public void patientInfoJsonRoundTripsIOSCompatibleFieldValues() {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        fields.put("patientName", FieldValue.string("张三"));
        fields.put("examDate", FieldValue.date("2026-06-03T00:00:00Z"));
        fields.put("diagnoses", FieldValue.stringArray(Arrays.asList(
                "I10.x00x002 高血压",
                "I25.103 冠状动脉粥样硬化性心脏病"
        )));

        PatientInfoData data = new PatientInfoData(
                "cardio_cerebrovascular",
                "心脑血管通用",
                "MR001",
                "Session_1710000000000",
                "2026-06-03T09:00:00Z",
                "2026-06-03T09:10:00Z",
                fields,
                null
        );

        Gson gson = PatientJson.gson();
        String json = gson.toJson(data);

        assertTrue(json.contains("\"templateId\":\"cardio_cerebrovascular\""));
        assertTrue(json.contains("\"examDate\":{\"type\":\"date\",\"value\":\"2026-06-03T00:00:00Z\"}"));
        assertTrue(json.contains("\"type\":\"stringArray\""));
        assertTrue(json.contains("\"value\":[\"I10.x00x002 高血压\",\"I25.103 冠状动脉粥样硬化性心脏病\"]"));

        PatientInfoData decoded = gson.fromJson(json, PatientInfoData.class);
        assertEquals("MR001", decoded.getPatientId());
        assertEquals(Arrays.asList(
                "I10.x00x002 高血压",
                "I25.103 冠状动脉粥样硬化性心脏病"
        ), decoded.getFields().get("diagnoses").asStringArray());
    }

    @Test
    public void formatToTextGroupsFieldsByTemplateAndHandlesUnits() {
        DepartmentTemplate template = BuiltInTemplates.cardioCerebrovascular();
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        fields.put("patientId", FieldValue.string("MR001"));
        fields.put("patientName", FieldValue.string("张三"));
        fields.put("heartRate", FieldValue.string("72"));
        fields.put("bloodPressure_systolic", FieldValue.string("120"));
        fields.put("bloodPressure_diastolic", FieldValue.string("80"));
        fields.put("diagnoses", FieldValue.stringArray(Arrays.asList(
                "I10.x00x002 高血压",
                "I50.900x007 心力衰竭"
        )));

        PatientInfoData data = new PatientInfoData(
                template.getId(),
                template.getName(),
                "MR001",
                "Session_1710000000000",
                "2026-06-03T09:00:00Z",
                "2026-06-03T09:10:00Z",
                fields,
                null
        );

        String text = data.formatToText(template);

        assertTrue(text.contains("模板: 心脑血管通用"));
        assertTrue(text.contains("患者ID: MR001"));
        assertTrue(text.contains("=== 基本信息 ==="));
        assertTrue(text.contains("姓名: 张三"));
        assertTrue(text.contains("=== 生命体征 ==="));
        assertTrue(text.contains("心率: 72 bpm"));
        assertTrue(text.contains("血压: 120/80 mmHg"));
        assertTrue(text.contains("=== 诊断信息 ==="));
        assertTrue(text.contains("诊断列表: I10.x00x002 高血压、I50.900x007 心力衰竭"));
    }

    @Test
    public void icd10CatalogSearchesByCodeAndNameAndParsesStoredValues() {
        String json = "["
                + "{\"code\":\"I10.x00x002\",\"name\":\"高血压\",\"source\":\"ICD-10医保版\"},"
                + "{\"code\":\"I50.900x007\",\"name\":\"心力衰竭\",\"source\":\"ICD-10医保版\"},"
                + "{\"code\":\"E11.900\",\"name\":\"2型糖尿病\",\"source\":\"ICD-10医保版\"}"
                + "]";
        ICD10Catalog catalog = ICD10Catalog.fromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals("I10.x00x002", catalog.search("高血压", 10).get(0).getCode());
        assertEquals("I50.900x007", catalog.search("i50", 10).get(0).getCode());
        assertEquals("E11.900 2型糖尿病", catalog.search("糖尿病", 10).get(0).storageValue());

        ICD10Diagnosis parsed = ICD10Catalog.parseStoredDiagnosis("I10.x00x002 高血压");
        assertNotNull(parsed);
        assertEquals("I10.x00x002", parsed.getCode());
        assertEquals("高血压", parsed.getName());
    }

    @Test
    public void templateRepositoryPersistsCustomTemplatesAndActiveTemplate() throws Exception {
        File templatesDir = temporaryFolder.newFolder("templates");
        File activeFile = temporaryFolder.newFile("active_template_id.txt");
        TemplateRepository repository = new TemplateRepository(templatesDir, activeFile);

        assertEquals("cardio_cerebrovascular", repository.getActiveTemplate().getId());

        DepartmentTemplate custom = BuiltInTemplates.cardioCerebrovascular().copyWith(
                "custom_1",
                "自定义模板",
                "基于心脑血管通用创建",
                false,
                BuiltInTemplates.cardioCerebrovascular().getSections()
        );

        repository.saveCustomTemplate(custom);
        repository.setActiveTemplateId("custom_1");

        TemplateRepository reloaded = new TemplateRepository(templatesDir, activeFile);
        assertEquals("custom_1", reloaded.getActiveTemplate().getId());
        assertEquals(2, reloaded.allTemplates().size());

        reloaded.deleteCustomTemplate("custom_1");

        TemplateRepository afterDelete = new TemplateRepository(templatesDir, activeFile);
        assertEquals("cardio_cerebrovascular", afterDelete.getActiveTemplate().getId());
        assertEquals(1, afterDelete.allTemplates().size());
    }

    @Test
    public void templateRepositoryKeepsRequiredPatientIdInCustomTemplates() throws Exception {
        File templatesDir = temporaryFolder.newFolder("templates-without-id");
        File activeFile = temporaryFolder.newFile("active_template_without_id.txt");
        TemplateRepository repository = new TemplateRepository(templatesDir, activeFile);

        DepartmentTemplate custom = new DepartmentTemplate(
                "custom_without_patient_id",
                "缺少病历号的模板",
                "用户编辑时误删了病历号",
                false,
                Collections.singletonList(new FormSection("notes", "备注", Collections.singletonList(
                        FieldDefinition.of("notes", "备注", FieldType.TEXT_AREA)
                )))
        );

        repository.saveCustomTemplate(custom);

        DepartmentTemplate loaded = repository.templateById("custom_without_patient_id");
        assertNotNull(loaded);
        FieldDefinition patientId = loaded.fieldById("patientId");
        assertNotNull(patientId);
        assertEquals("病历号", patientId.getLabel());
        assertEquals(FieldType.TEXT, patientId.getType());
        assertTrue(patientId.isRequired());
    }

    @Test
    public void patientRecordRepositoryCreatesUniqueFilesForSamePatientName() throws Exception {
        File session = temporaryFolder.newFolder("ExperimentA", "Session_1710000000000");
        PatientRecordRepository repository = new PatientRecordRepository(temporaryFolder.getRoot());

        File first = repository.saveForSession(session, patientData("MR001", "张三"));
        File second = repository.saveForSession(session, patientData("MR002", "张三"));

        assertTrue(first.exists());
        assertTrue(second.exists());
        assertFalse(first.getAbsolutePath().equals(second.getAbsolutePath()));
        assertEquals(2, jsonFiles(repository.patientInfoDir(session)).length);
        assertTrue(readText(first).contains("\"patientId\":\"MR001\""));
        assertTrue(readText(second).contains("\"patientId\":\"MR002\""));
    }

    @Test
    public void patientRecordRepositoryDoesNotDeleteOldFileWhenRenameWriteFails() throws Exception {
        File session = temporaryFolder.newFolder("ExperimentB", "Session_1710000000001");
        PatientRecordRepository repository = new PatientRecordRepository(temporaryFolder.getRoot());
        File patientDir = repository.patientInfoDir(session);
        PatientInfoData original = patientData("MR001", "李四");
        File oldFile = new File(patientDir, "李四.json");
        String originalJson = PatientJson.gson().toJson(original);
        writeText(oldFile, originalJson);

        try {
            new FailingPatientRecordRepository(temporaryFolder.getRoot())
                    .saveExisting(new PatientRecord(oldFile, session, original), patientData("MR001", "张三"));
            assertFalse("Expected simulated write failure", true);
        } catch (IOException expected) {
            assertTrue(oldFile.exists());
            assertEquals(originalJson, readText(oldFile));
        }
    }

    @Test
    public void patientRecordRepositoryKeepsExistingRecordWhenRenameTargetAlreadyExists() throws Exception {
        File session = temporaryFolder.newFolder("ExperimentC", "Session_1710000000002");
        PatientRecordRepository repository = new PatientRecordRepository(temporaryFolder.getRoot());

        File existing = repository.saveForSession(session, patientData("MR001", "张三"));
        PatientInfoData original = patientData("MR002", "李四");
        File oldFile = repository.saveForSession(session, original);

        File renamed = repository.saveExisting(new PatientRecord(oldFile, session, original), patientData("MR002", "张三"));

        assertTrue(existing.exists());
        assertTrue(renamed.exists());
        assertFalse(existing.getAbsolutePath().equals(renamed.getAbsolutePath()));
        assertEquals(2, jsonFiles(repository.patientInfoDir(session)).length);
        assertTrue(readText(existing).contains("\"patientId\":\"MR001\""));
        assertTrue(readText(renamed).contains("\"patientId\":\"MR002\""));
    }

    @Test
    public void patientRecordRepositoryDeletesWholeSessionDirectory() throws Exception {
        File session = temporaryFolder.newFolder("ExperimentDelete", "Session_1710000000003");
        File frontDir = new File(session, "front");
        assertTrue(frontDir.mkdirs());
        File frontVideo = new File(frontDir, "front_camera.mp4");
        writeText(frontVideo, "video placeholder");

        PatientRecordRepository repository = new PatientRecordRepository(temporaryFolder.getRoot());
        File patientDir = repository.patientInfoDir(session);
        File photo = new File(patientDir, "photo_patientPhoto_1710000000000.jpg");
        writeText(photo, "photo placeholder");

        PatientInfoData data = patientData("MR003", "王五", photo.getName());
        File recordFile = repository.saveForSession(session, data);
        PatientRecord record = new PatientRecord(recordFile, session, data);

        assertTrue(repository.deleteRecord(record));

        assertFalse(recordFile.exists());
        assertFalse(photo.exists());
        assertFalse(frontVideo.exists());
        assertFalse(session.exists());
    }

    @Test
    public void patientRecordRepositoryRefusesToDeleteNonSessionDirectory() throws Exception {
        File nonSession = temporaryFolder.newFolder("ExperimentDeleteSafety", "NotSession_1710000000003");
        PatientRecordRepository repository = new PatientRecordRepository(temporaryFolder.getRoot());
        PatientInfoData data = patientData("MR003", "王五");
        File recordFile = repository.saveForSession(nonSession, data);
        PatientRecord record = new PatientRecord(recordFile, nonSession, data);

        assertFalse(repository.deleteRecord(record));

        assertTrue(nonSession.exists());
        assertTrue(recordFile.exists());
    }

    @Test
    public void patientRecordRepositoryRefusesToDeleteMalformedSessionName() throws Exception {
        File malformedSession = temporaryFolder.newFolder("ExperimentDeleteSafety2", "Session_backup");
        PatientRecordRepository repository = new PatientRecordRepository(temporaryFolder.getRoot());
        PatientInfoData data = patientData("MR007", "孙八");
        File recordFile = repository.saveForSession(malformedSession, data);
        PatientRecord record = new PatientRecord(recordFile, malformedSession, data);

        assertFalse(repository.deleteRecord(record));

        assertTrue(malformedSession.exists());
        assertTrue(recordFile.exists());
    }

    @Test
    public void patientRecordRepositoryRefusesToDeleteSessionOutsideScanRoot() throws Exception {
        File scanRoot = temporaryFolder.newFolder("FacialCollectionRoot");
        File outsideSession = temporaryFolder.newFolder("OutsideRoot", "Session_1710000000006");
        PatientRecordRepository writer = new PatientRecordRepository(temporaryFolder.getRoot());
        PatientInfoData data = patientData("MR006", "周七");
        File recordFile = writer.saveForSession(outsideSession, data);

        PatientRecordRepository repository = new PatientRecordRepository(scanRoot);
        PatientRecord record = new PatientRecord(recordFile, outsideSession, data);

        assertFalse(repository.deleteRecord(record));

        assertTrue(outsideSession.exists());
        assertTrue(recordFile.exists());
    }

    @Test
    public void patientRecordRepositoryRefusesToDeleteSessionContainingEscapingSymlink() throws Exception {
        File session = temporaryFolder.newFolder("ExperimentDeleteSymlink", "Session_1710000000008");
        File outside = temporaryFolder.newFolder("OutsideDeleteTarget");
        File outsideFile = new File(outside, "keep.txt");
        writeText(outsideFile, "must stay");

        try {
            java.nio.file.Files.createSymbolicLink(new File(session, "escape").toPath(), outside.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.Assume.assumeTrue("Symlink creation unavailable in this environment", false);
        }

        PatientRecordRepository repository = new PatientRecordRepository(temporaryFolder.getRoot());
        PatientInfoData data = patientData("MR008", "吴九");
        File recordFile = repository.saveForSession(session, data);
        PatientRecord record = new PatientRecord(recordFile, session, data);

        try {
            repository.deleteRecord(record);
            assertFalse("Expected escaping symlink delete to fail", true);
        } catch (IOException expected) {
            assertTrue(session.exists());
            assertTrue(outside.exists());
            assertTrue(outsideFile.exists());
            assertTrue(recordFile.exists());
        }
    }

    @Test
    public void patientRecordRepositoryScansLegacyInfoTextRecords() throws Exception {
        File session = temporaryFolder.newFolder("ExperimentLegacy", "Session_1710000000004");
        File infoDir = new File(session, "info");
        assertTrue(infoDir.mkdirs());
        writeText(new File(infoDir, "subject_info_20260603.txt"),
                "姓名：赵六\n病历号：MR004\n备注: 旧格式记录\n");

        PatientRecordRepository.ScanResult result = new PatientRecordRepository(temporaryFolder.getRoot()).scanRecordsWithStatus();

        assertFalse(result.hasError());
        assertEquals(1, result.getRecords().size());
        PatientInfoData data = result.getRecords().get(0).getData();
        assertEquals("legacy_fixed_form", data.getTemplateId());
        assertEquals("MR004", data.getPatientId());
        assertEquals("赵六", data.getFields().get("patientName").asString());
    }

    @Test
    public void patientRecordRepositoryMigratesLegacyRecordEditsToPatientInfoJson() throws Exception {
        File root = temporaryFolder.newFolder("FacialCollectionLegacyEdit");
        File session = new File(root, "ExperimentLegacyEdit/Session_1710000000009");
        File infoDir = new File(session, "info");
        assertTrue(infoDir.mkdirs());
        File legacyFile = new File(infoDir, "subject_info_20260603.txt");
        writeText(legacyFile, "姓名: 钱十\n病历号: MR009\n");

        PatientRecordRepository repository = new PatientRecordRepository(root);
        PatientRecord legacy = repository.scanRecordsWithStatus().getRecords().get(0);

        File saved = repository.saveExisting(legacy, patientData("MR009", "钱十改"));

        assertTrue(saved.exists());
        assertTrue(saved.getAbsolutePath().contains(File.separator + "PatientInfo" + File.separator));
        assertTrue(saved.getName().endsWith(".json"));
        assertTrue(legacyFile.exists());

        PatientRecordRepository.ScanResult rescanned = repository.scanRecordsWithStatus();
        assertFalse(rescanned.hasError());
        assertEquals(1, rescanned.getRecords().size());
        assertEquals("钱十改", rescanned.getRecords().get(0).getData().getFields().get("patientName").asString());
    }

    @Test
    public void patientRecordRepositoryReportsMalformedPatientJson() throws Exception {
        File session = temporaryFolder.newFolder("ExperimentMalformed", "Session_1710000000005");
        File patientDir = new PatientRecordRepository(temporaryFolder.getRoot()).patientInfoDir(session);
        writeText(new File(patientDir, "bad.json"), "{not-json");

        PatientRecordRepository.ScanResult result = new PatientRecordRepository(temporaryFolder.getRoot()).scanRecordsWithStatus();

        assertTrue(result.getRecords().isEmpty());
        assertTrue(result.hasError());
        assertTrue(result.getErrorMessage().contains("部分患者记录读取失败"));
    }

    @Test
    public void patientRecordRepositoryReportsScanFailureSeparatelyFromNoRecords() {
        File missingRoot = new File(temporaryFolder.getRoot(), "missing-facial-collection");
        PatientRecordRepository.ScanResult result = new PatientRecordRepository(missingRoot).scanRecordsWithStatus();

        assertTrue(result.getRecords().isEmpty());
        assertTrue(result.hasError());
        assertTrue(result.getErrorMessage().contains("存储权限"));
    }

    @Test
    public void dateValuesUseIosCompatibleIsoStorageAndPlainDateEditingText() {
        FieldValue stored = FieldValue.date("2026-06-03T00:00:00Z");

        assertEquals("2026-06-03", PatientValueFormatter.dateInputText(stored));
        assertEquals("2026-06-03T00:00:00Z", PatientValueFormatter.dateFieldValueFromInput("2026-06-03").asString());
        assertEquals("2026-06-03T08:15:00Z", PatientValueFormatter.dateFieldValueFromInput("2026-06-03T08:15:00Z").asString());
        assertTrue(PatientValueFormatter.isValidDateInput("2026-06-03"));
        assertTrue(PatientValueFormatter.isValidDateInput("2026-06-03T08:15:00Z"));
        assertFalse(PatientValueFormatter.isValidDateInput("2026/06/03"));
        assertTrue(PatientValueFormatter.dateFieldValueFromInput("2026/06/03").isEmpty());
        assertEquals("", PatientValueFormatter.dateInputText((FieldValue) null));
    }

    @Test
    public void isoInstantsDisplayInDeviceLocalTimeForPatientRecords() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

            assertEquals("2026-06-12 16:14:41",
                    PatientValueFormatter.localDateTimeText("2026-06-12T08:14:41Z"));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    public void templateSanitizerPreventsImpossibleRequiredFields() {
        DepartmentTemplate template = new DepartmentTemplate(
                "custom_bad_fields",
                "问题模板",
                "包含无法填写的字段",
                false,
                Collections.singletonList(new FormSection("basic", "基本信息", Arrays.asList(
                        FieldDefinition.of("patientId", "病历号", FieldType.TEXT).required(),
                        FieldDefinition.of("status", "状态", FieldType.PICKER).required(),
                        FieldDefinition.of("bloodPressure", "血压", FieldType.BLOOD_PRESSURE).required(),
                        FieldDefinition.of("photo", "照片", FieldType.PHOTO).required()
                )))
        );

        TemplateSanitizer.sanitizeForSave(template);

        FieldDefinition status = template.fieldById("status");
        assertNotNull(status);
        assertEquals(Collections.singletonList("未填写"), status.getOptions());
        assertFalse(status.isRequired());

        FieldDefinition bloodPressure = template.fieldById("bloodPressure");
        assertNotNull(bloodPressure);
        assertFalse(bloodPressure.isRequired());

        FieldDefinition photo = template.fieldById("photo");
        assertNotNull(photo);
        assertFalse(photo.isRequired());
    }

    @Test
    public void templateRepositoryReportsMalformedCustomTemplates() throws Exception {
        File templatesDir = temporaryFolder.newFolder("malformed-templates");
        File activeFile = temporaryFolder.newFile("active_template_malformed.txt");
        writeText(new File(templatesDir, "bad.json"), "{not-json");

        TemplateRepository repository = new TemplateRepository(templatesDir, activeFile);

        assertEquals(1, repository.allTemplates().size());
        assertFalse(repository.getLastLoadError().isEmpty());
    }

    @Test
    public void templateSanitizerFallsBackBlankNamesAndBreaksConditionCycles() {
        FieldDefinition a = FieldDefinition.of("a", "A", FieldType.TEXT)
                .visibleWhen("b", "yes");
        FieldDefinition b = FieldDefinition.of("b", "B", FieldType.TEXT)
                .visibleWhen("a", "yes");
        DepartmentTemplate template = new DepartmentTemplate(
                "custom_cycle",
                "",
                "",
                false,
                Collections.singletonList(new FormSection("basic", "基本信息", Arrays.asList(a, b)))
        );

        TemplateSanitizer.sanitizeForSave(template);

        assertEquals("未命名模板", template.getName());
        assertEquals(null, template.fieldById("a").getVisibleWhen());
        assertEquals(null, template.fieldById("b").getVisibleWhen());
    }

    @Test
    public void templateSanitizerRenamesDuplicateCustomFieldIds() {
        DepartmentTemplate template = new DepartmentTemplate(
                "custom_duplicate_fields",
                "重复字段模板",
                "",
                false,
                Collections.singletonList(new FormSection("basic", "基本信息", Arrays.asList(
                        FieldDefinition.of("patientId", "病历号", FieldType.TEXT).required(),
                        FieldDefinition.of("duplicate", "字段A", FieldType.TEXT),
                        FieldDefinition.of("duplicate", "字段B", FieldType.TEXT)
                )))
        );

        TemplateSanitizer.sanitizeForSave(template);

        List<String> ids = template.allFieldIds();
        assertEquals(3, ids.size());
        assertEquals("patientId", ids.get(0));
        assertEquals("duplicate", ids.get(1));
        assertFalse(ids.get(1).equals(ids.get(2)));
        assertTrue(ids.get(2).startsWith("duplicate_"));
    }

    private static PatientInfoData patientData(String patientId, String patientName) {
        return patientData(patientId, patientName, null);
    }

    private static PatientInfoData patientData(String patientId, String patientName, String photoFileName) {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        fields.put("patientId", FieldValue.string(patientId));
        fields.put("patientName", FieldValue.string(patientName));
        fields.put("examDate", FieldValue.date("2026-06-03T00:00:00Z"));
        return new PatientInfoData(
                "cardio_cerebrovascular",
                "心脑血管通用",
                patientId,
                "Session_1710000000000",
                "2026-06-03T09:00:00Z",
                "2026-06-03T09:10:00Z",
                fields,
                photoFileName
        );
    }

    private static File[] jsonFiles(File dir) {
        File[] files = dir.listFiles((file, name) -> name.endsWith(".json"));
        return files == null ? new File[0] : files;
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = inputStream.read(data);
            return new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            writer.write(text == null ? "" : text);
        }
    }

    private static class FailingPatientRecordRepository extends PatientRecordRepository {
        FailingPatientRecordRepository(File facialCollectionRoot) {
            super(facialCollectionRoot);
        }

        @Override
        protected void writeRecordText(File file, String text) throws IOException {
            throw new IOException("simulated write failure");
        }
    }
}
