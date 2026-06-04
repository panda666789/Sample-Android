package com.tsinghua.sample.patient.storage;

import com.tsinghua.sample.patient.model.PatientInfoData;

import java.io.File;

public class PatientRecord {
    private final File file;
    private final File sessionDir;
    private final PatientInfoData data;

    public PatientRecord(File file, File sessionDir, PatientInfoData data) {
        this.file = file;
        this.sessionDir = sessionDir;
        this.data = data;
    }

    public File getFile() {
        return file;
    }

    public File getSessionDir() {
        return sessionDir;
    }

    public PatientInfoData getData() {
        return data;
    }
}
