package com.tsinghua.sample.patient.storage;

import com.tsinghua.sample.patient.model.BuiltInTemplates;
import com.tsinghua.sample.patient.model.DepartmentTemplate;
import com.tsinghua.sample.patient.model.FieldDefinition;
import com.tsinghua.sample.patient.model.FieldType;
import com.tsinghua.sample.patient.model.FormSection;
import com.tsinghua.sample.patient.model.PatientJson;
import com.tsinghua.sample.patient.model.TemplateSanitizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TemplateRepository {
    private static final String DEFAULT_TEMPLATE_ID = "cardio_cerebrovascular";

    private final File templatesDir;
    private final File activeTemplateFile;
    private String lastLoadError = "";

    public TemplateRepository(File templatesDir, File activeTemplateFile) {
        this.templatesDir = templatesDir;
        this.activeTemplateFile = activeTemplateFile;
        if (!templatesDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            templatesDir.mkdirs();
        }
    }

    public List<DepartmentTemplate> allTemplates() {
        List<DepartmentTemplate> templates = new ArrayList<>();
        templates.addAll(BuiltInTemplates.all());
        templates.addAll(loadCustomTemplates());
        return templates;
    }

    public List<DepartmentTemplate> customTemplates() {
        return loadCustomTemplates();
    }

    public DepartmentTemplate getActiveTemplate() {
        String id = readActiveTemplateId();
        DepartmentTemplate template = templateById(id);
        return template == null ? BuiltInTemplates.cardioCerebrovascular() : template;
    }

    public void setActiveTemplateId(String id) throws IOException {
        writeText(activeTemplateFile, id == null || id.trim().isEmpty() ? DEFAULT_TEMPLATE_ID : id.trim());
    }

    public DepartmentTemplate templateById(String id) {
        for (DepartmentTemplate template : allTemplates()) {
            if (template.getId().equals(id)) {
                return template;
            }
        }
        return null;
    }

    public String getLastLoadError() {
        return lastLoadError == null ? "" : lastLoadError;
    }

    public void saveCustomTemplate(DepartmentTemplate template) throws IOException {
        if (template == null) return;
        template.setBuiltIn(false);
        TemplateSanitizer.sanitizeForSave(template);
        File target = new File(templatesDir, sanitizeFileName(template.getId()) + ".json");
        writeText(target, PatientJson.gson().toJson(template));
    }

    public void deleteCustomTemplate(String id) {
        if (id == null) return;
        if (BuiltInTemplates.cardioCerebrovascular().getId().equals(id)) return;
        File target = new File(templatesDir, sanitizeFileName(id) + ".json");
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (id.equals(readActiveTemplateId())) {
            try {
                setActiveTemplateId(DEFAULT_TEMPLATE_ID);
            } catch (IOException ignored) {
                // Next read falls back to the default built-in template.
            }
        }
    }

    public DepartmentTemplate duplicateTemplate(DepartmentTemplate source, String newName) throws IOException {
        String id = "custom_" + System.currentTimeMillis();
        DepartmentTemplate template = source.copyWith(
                id,
                newName,
                "基于「" + source.getName() + "」创建",
                false,
                source.getSections()
        );
        saveCustomTemplate(template);
        return template;
    }

    public DepartmentTemplate createBlankTemplate(String name) throws IOException {
        String id = "custom_" + System.currentTimeMillis();
        DepartmentTemplate template = new DepartmentTemplate(
                id,
                name,
                "自定义模板",
                false,
                Collections.singletonList(new FormSection("basic", "基本信息",
                        Collections.singletonList(
                                FieldDefinition.of("patientId", "病历号", FieldType.TEXT)
                                        .withPlaceholder("请输入病历号")
                                        .required()
                        )))
        );
        saveCustomTemplate(template);
        return template;
    }

    private List<DepartmentTemplate> loadCustomTemplates() {
        lastLoadError = "";
        File[] files = templatesDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            lastLoadError = "无法读取自定义模板目录";
            return Collections.emptyList();
        }

        List<DepartmentTemplate> result = new ArrayList<>();
        for (File file : files) {
            try {
                String json = readText(file);
                DepartmentTemplate template = PatientJson.gson().fromJson(json, DepartmentTemplate.class);
                if (template != null && !template.isBuiltIn()) {
                    TemplateSanitizer.sanitizeForSave(template);
                    result.add(template);
                }
            } catch (Exception e) {
                lastLoadError = "部分自定义模板读取失败: " + file.getName();
            }
        }

        Collections.sort(result, (left, right) -> left.getName().compareTo(right.getName()));
        return result;
    }

    private String readActiveTemplateId() {
        try {
            String id = readText(activeTemplateFile).trim();
            return id.isEmpty() ? DEFAULT_TEMPLATE_ID : id;
        } catch (Exception ignored) {
            return DEFAULT_TEMPLATE_ID;
        }
    }

    private static String sanitizeFileName(String id) {
        return id == null ? "template" : id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public static void ensureRequiredPatientId(DepartmentTemplate template) {
        TemplateSanitizer.ensureRequiredPatientId(template);
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = inputStream.read(data);
            return new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File temp = File.createTempFile(".template_", ".tmp", parent);
        try (FileOutputStream outputStream = new FileOutputStream(temp);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            writer.write(text == null ? "" : text);
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }
}
