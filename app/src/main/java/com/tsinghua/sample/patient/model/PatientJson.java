package com.tsinghua.sample.patient.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class PatientJson {
    private PatientJson() {
    }

    public static Gson gson() {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .create();
    }
}
