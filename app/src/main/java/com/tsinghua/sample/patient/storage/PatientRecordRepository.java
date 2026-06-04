package com.tsinghua.sample.patient.storage;

import com.tsinghua.sample.patient.model.FieldValue;
import com.tsinghua.sample.patient.model.PatientInfoData;
import com.tsinghua.sample.patient.model.PatientJson;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PatientRecordRepository {
    public static final String PATIENT_INFO_DIR = "PatientInfo";
    public static final String LEGACY_INFO_DIR = "info";
    private static final String LEGACY_TEMPLATE_ID = "legacy_fixed_form";
    private static final String LEGACY_TEMPLATE_NAME = "旧版固定患者信息";

    private final File facialCollectionRoot;

    public PatientRecordRepository(File facialCollectionRoot) {
        this.facialCollectionRoot = facialCollectionRoot;
    }

    public File patientInfoDir(File sessionDir) {
        File dir = new File(sessionDir, PATIENT_INFO_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public File saveForSession(File sessionDir, PatientInfoData data) throws IOException {
        File dir = patientInfoDir(sessionDir);
        String patientName = stringField(data, "patientName");
        String fileName;
        if (!isBlank(patientName)) {
            fileName = sanitizeFileName(patientName) + ".json";
        } else {
            fileName = "patient_" + System.currentTimeMillis() + ".json";
        }
        File target = uniqueFile(dir, fileName, null);
        writeRecordText(target, PatientJson.gson().toJson(data));
        return target;
    }

    public File saveExisting(PatientRecord record, PatientInfoData updatedData) throws IOException {
        File target = record.getFile();
        if (isLegacyRecordFile(target)) {
            target = uniqueFile(
                    patientInfoDir(record.getSessionDir()),
                    preferredRecordFileName(updatedData, "patient_" + System.currentTimeMillis() + ".json"),
                    null
            );
            writeRecordText(target, PatientJson.gson().toJson(updatedData));
            return target;
        }

        String patientName = stringField(updatedData, "patientName");
        if (!isBlank(patientName)) {
            File renamed = new File(target.getParentFile(), sanitizeFileName(patientName) + ".json");
            if (!renamed.equals(target)) {
                target = uniqueFile(target.getParentFile(), renamed.getName(), record.getFile());
            }
        }
        writeRecordText(target, PatientJson.gson().toJson(updatedData));
        if (!target.equals(record.getFile()) && record.getFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            record.getFile().delete();
        }
        return target;
    }

    public boolean deleteRecord(PatientRecord record) throws IOException {
        if (record == null || record.getSessionDir() == null) {
            return false;
        }
        File sessionDir = record.getSessionDir();
        if (!sessionDir.exists() || !sessionDir.isDirectory() || !isValidSessionDirName(sessionDir.getName())) {
            return false;
        }
        if (facialCollectionRoot != null && !isInsideRoot(sessionDir, facialCollectionRoot)) {
            return false;
        }
        deleteRecursively(sessionDir);
        return !sessionDir.exists();
    }

    public List<PatientRecord> scanRecords() {
        return scanRecordsWithStatus().getRecords();
    }

    public ScanResult scanRecordsWithStatus() {
        if (facialCollectionRoot == null || !facialCollectionRoot.exists()) {
            return ScanResult.error(Collections.emptyList(), "未找到患者记录目录，请确认存储权限或采集目录");
        }
        List<PatientRecord> records = new ArrayList<>();
        File[] experiments = facialCollectionRoot.listFiles(File::isDirectory);
        if (experiments == null) {
            return ScanResult.error(records, "无法读取患者记录目录，请检查存储权限");
        }

        int malformedCount = 0;
        for (File experiment : experiments) {
            File[] sessions = experiment.listFiles(file -> file.isDirectory() && isValidSessionDirName(file.getName()));
            if (sessions == null) continue;
            for (File session : sessions) {
                File patientDir = new File(session, PATIENT_INFO_DIR);
                File[] files = patientDir.listFiles((dir, name) -> name.endsWith(".json"));
                boolean foundJsonRecord = false;
                if (files != null) {
                    for (File file : files) {
                        try {
                            PatientInfoData data = PatientJson.gson().fromJson(readText(file), PatientInfoData.class);
                            if (data != null) {
                                records.add(new PatientRecord(file, session, data));
                                foundJsonRecord = true;
                            }
                        } catch (Exception ignored) {
                            malformedCount++;
                        }
                    }
                }
                if (!foundJsonRecord) {
                    PatientRecord legacy = readLegacyInfoRecord(session);
                    if (legacy != null) {
                        records.add(legacy);
                    }
                }
            }
        }

        records.sort((left, right) -> nullToEmpty(right.getData().getRecordingStartTime())
                .compareTo(nullToEmpty(left.getData().getRecordingStartTime())));
        if (malformedCount > 0) {
            return ScanResult.error(records, "部分患者记录读取失败: " + malformedCount + " 个文件");
        }
        return ScanResult.success(records);
    }

    public static class ScanResult {
        private final List<PatientRecord> records;
        private final String errorMessage;

        private ScanResult(List<PatientRecord> records, String errorMessage) {
            this.records = records == null ? Collections.emptyList() : records;
            this.errorMessage = errorMessage;
        }

        static ScanResult success(List<PatientRecord> records) {
            return new ScanResult(records, "");
        }

        static ScanResult error(List<PatientRecord> records, String errorMessage) {
            return new ScanResult(records, errorMessage);
        }

        public List<PatientRecord> getRecords() {
            return records;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public boolean hasError() {
            return errorMessage != null && !errorMessage.trim().isEmpty();
        }
    }

    private static String stringField(PatientInfoData data, String fieldId) {
        FieldValue value = data == null ? null : data.getFields().get(fieldId);
        return value == null ? "" : nullToEmpty(value.asString()).trim();
    }

    private static String preferredRecordFileName(PatientInfoData data, String fallback) {
        String patientName = stringField(data, "patientName");
        if (!isBlank(patientName)) {
            return sanitizeFileName(patientName) + ".json";
        }
        return fallback;
    }

    private static String sanitizeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").trim();
    }

    private static File uniqueFile(File dir, String preferredName, File allowedExisting) {
        File preferred = new File(dir, preferredName);
        if (!preferred.exists() || preferred.equals(allowedExisting)) {
            return preferred;
        }

        String baseName = preferredName;
        String extension = "";
        int dot = preferredName.lastIndexOf('.');
        if (dot > 0) {
            baseName = preferredName.substring(0, dot);
            extension = preferredName.substring(dot);
        }

        int index = 2;
        File candidate;
        do {
            candidate = new File(dir, baseName + "_" + index + extension);
            index++;
        } while (candidate.exists() && !candidate.equals(allowedExisting));
        return candidate;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static PatientRecord readLegacyInfoRecord(File session) {
        File infoDir = new File(session, LEGACY_INFO_DIR);
        File[] files = infoDir.listFiles((dir, name) -> name.startsWith("subject_info") && name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            return null;
        }
        File file = files[0];
        try {
            String text = readText(file);
            Map<String, FieldValue> fields = new LinkedHashMap<>();
            putLegacyField(fields, "patientName", firstLegacyValue(text, "姓名", "姓名:"));
            putLegacyField(fields, "patientId", firstLegacyValue(text, "病历号", "患者ID", "患者ID:"));
            putLegacyField(fields, "legacyText", text.trim());
            String patientId = fields.containsKey("patientId") ? fields.get("patientId").asString() : "";
            PatientInfoData data = new PatientInfoData(
                    LEGACY_TEMPLATE_ID,
                    LEGACY_TEMPLATE_NAME,
                    patientId,
                    session.getName(),
                    recordingTimeFromSessionName(session.getName()),
                    "",
                    fields,
                    null
            );
            return new PatientRecord(file, session, data);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void putLegacyField(Map<String, FieldValue> fields, String key, String value) {
        if (!isBlank(value)) {
            fields.put(key, FieldValue.string(value.trim()));
        }
    }

    private static String firstLegacyValue(String text, String... labels) {
        if (text == null) return "";
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            for (String label : labels) {
                String normalizedLabel = label;
                while (normalizedLabel.endsWith(":") || normalizedLabel.endsWith("：")) {
                    normalizedLabel = normalizedLabel.substring(0, normalizedLabel.length() - 1);
                }
                String asciiPrefix = normalizedLabel + ":";
                if (trimmed.startsWith(asciiPrefix)) {
                    return trimmed.substring(asciiPrefix.length()).trim();
                }
                String chinesePrefix = normalizedLabel + "：";
                if (trimmed.startsWith(chinesePrefix)) {
                    return trimmed.substring(chinesePrefix.length()).trim();
                }
            }
        }
        return "";
    }

    private static String recordingTimeFromSessionName(String sessionName) {
        if (!isValidSessionDirName(sessionName)) {
            return "";
        }
        try {
            String timestamp = sessionName.substring("Session_".length());
            return toIsoLike(new java.util.Date(Long.parseLong(timestamp)));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        deleteRecursively(file, file.getCanonicalFile());
    }

    private static void deleteRecursively(File file, File root) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!isInsideRoot(canonical, root)) {
            throw new IOException("拒绝删除会话目录外的路径: " + canonical.getAbsolutePath());
        }
        if (Files.isSymbolicLink(file.toPath())) {
            throw new IOException("拒绝删除包含符号链接的会话目录: " + file.getAbsolutePath());
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("无法读取目录: " + file.getAbsolutePath());
            }
            for (File child : children) {
                deleteRecursively(child, root);
            }
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("无法删除: " + file.getAbsolutePath());
        }
    }

    private static boolean isInsideRoot(File candidate, File root) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File current = candidate.getCanonicalFile();
        while (current != null) {
            if (canonicalRoot.equals(current)) {
                return true;
            }
            current = current.getParentFile();
        }
        return false;
    }

    private static boolean isValidSessionDirName(String name) {
        return name != null && name.matches("Session_\\d+");
    }

    private static boolean isLegacyRecordFile(File file) {
        if (file == null || !file.getName().startsWith("subject_info") || !file.getName().endsWith(".txt")) {
            return false;
        }
        File parent = file.getParentFile();
        return parent != null && LEGACY_INFO_DIR.equals(parent.getName());
    }

    private static String toIsoLike(java.util.Date date) {
        java.text.SimpleDateFormat formatter = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        formatter.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return formatter.format(date);
    }

    protected void writeRecordText(File file, String text) throws IOException {
        writeTextAtomically(file, text);
    }

    private static void writeTextAtomically(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File temp = File.createTempFile(".patient_", ".tmp", parent);
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
