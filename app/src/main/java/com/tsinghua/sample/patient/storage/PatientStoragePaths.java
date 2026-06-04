package com.tsinghua.sample.patient.storage;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public final class PatientStoragePaths {
    private static final String ROOT_DIR = "FacialCollection";
    private static final String TEMPLATE_DIR = "FacialCollection/templates";
    private static final String ACTIVE_TEMPLATE_FILE = "active_template_id.txt";

    private PatientStoragePaths() {
    }

    public static TemplateRepository templateRepository(Context context) {
        File base = new File(context.getFilesDir(), TEMPLATE_DIR);
        File active = new File(base, ACTIVE_TEMPLATE_FILE);
        return new TemplateRepository(base, active);
    }

    public static PatientRecordRepository patientRecordRepository() {
        File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        return new PatientRecordRepository(new File(movies, ROOT_DIR));
    }
}
