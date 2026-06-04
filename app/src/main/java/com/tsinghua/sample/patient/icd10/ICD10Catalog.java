package com.tsinghua.sample.patient.icd10;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ICD10Catalog {
    private static final String SOURCE_NAME = "ICD-10医保版";
    private static final String ASSET_FILE = "icd10_nhsa_common.json";

    private final List<ICD10Diagnosis> diagnoses;

    public ICD10Catalog(List<ICD10Diagnosis> diagnoses) {
        this.diagnoses = diagnoses == null ? fallbackDiagnoses() : new ArrayList<>(diagnoses);
    }

    public static ICD10Catalog fromAssets(Context context) throws IOException {
        try (InputStream inputStream = context.getAssets().open(ASSET_FILE)) {
            return fromJson(inputStream);
        }
    }

    public static ICD10Catalog fromJson(InputStream inputStream) {
        try {
            String json = readAll(inputStream);
            Type type = new TypeToken<List<ICD10Diagnosis>>() {}.getType();
            List<ICD10Diagnosis> rows = new Gson().fromJson(json, type);
            return new ICD10Catalog(rows);
        } catch (Exception ignored) {
            return new ICD10Catalog(fallbackDiagnoses());
        }
    }

    public List<ICD10Diagnosis> search(String query, int limit) {
        int cappedLimit = limit <= 0 ? 50 : limit;
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) {
            return commonDiagnoses(cappedLimit);
        }

        List<ScoredDiagnosis> scored = new ArrayList<>();
        for (ICD10Diagnosis diagnosis : diagnoses) {
            String code = normalize(diagnosis.getCode());
            String name = normalize(diagnosis.getName());
            int score = -1;
            if (code.equals(normalizedQuery)) {
                score = 0;
            } else if (code.startsWith(normalizedQuery)) {
                score = 1;
            } else if (name.equals(normalizedQuery)) {
                score = 2;
            } else if (name.startsWith(normalizedQuery)) {
                score = 3;
            } else if (name.contains(normalizedQuery)) {
                score = 4;
            } else if (code.contains(normalizedQuery)) {
                score = 5;
            }

            if (score >= 0) {
                scored.add(new ScoredDiagnosis(diagnosis, score));
            }
        }

        Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(scored, (left, right) -> {
            if (left.score != right.score) {
                return left.score - right.score;
            }
            return collator.compare(left.diagnosis.getCode(), right.diagnosis.getCode());
        });

        List<ICD10Diagnosis> result = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < cappedLimit; i++) {
            result.add(scored.get(i).diagnosis);
        }
        return result;
    }

    public List<ICD10Diagnosis> allDiagnoses() {
        return new ArrayList<>(diagnoses);
    }

    public static ICD10Diagnosis parseStoredDiagnosis(String storedValue) {
        if (storedValue == null) return null;
        String trimmed = storedValue.trim();
        if (trimmed.isEmpty()) return null;
        int split = trimmed.indexOf(' ');
        if (split <= 0 || split >= trimmed.length() - 1) return null;
        return new ICD10Diagnosis(trimmed.substring(0, split), trimmed.substring(split + 1), SOURCE_NAME);
    }

    private List<ICD10Diagnosis> commonDiagnoses(int limit) {
        List<String> preferredCodes = Arrays.asList(
                "I10.x00x002",
                "I10.x00",
                "I25.103",
                "I25.104",
                "I25.200",
                "I50.000",
                "I50.100",
                "I63.000",
                "I63.102",
                "N18.300",
                "N18.400",
                "N18.900",
                "E11.900"
        );

        List<ICD10Diagnosis> result = new ArrayList<>();
        for (String code : preferredCodes) {
            for (ICD10Diagnosis diagnosis : diagnoses) {
                if (code.equals(diagnosis.getCode())) {
                    result.add(diagnosis);
                    break;
                }
            }
            if (result.size() >= limit) return result;
        }

        if (result.isEmpty()) {
            List<ICD10Diagnosis> fallback = fallbackDiagnoses();
            return fallback.subList(0, Math.min(limit, fallback.size()));
        }
        return result;
    }

    private static List<ICD10Diagnosis> fallbackDiagnoses() {
        return Arrays.asList(
                new ICD10Diagnosis("I10.x00x002", "高血压", SOURCE_NAME),
                new ICD10Diagnosis("I10.x09", "原发性高血压", SOURCE_NAME),
                new ICD10Diagnosis("I25.104", "冠心病心律失常型", SOURCE_NAME),
                new ICD10Diagnosis("I50.900x007", "心力衰竭", SOURCE_NAME),
                new ICD10Diagnosis("I63.900", "脑梗死", SOURCE_NAME),
                new ICD10Diagnosis("E11.900", "2型糖尿病", SOURCE_NAME),
                new ICD10Diagnosis("N03.900x006", "慢性肾病", SOURCE_NAME)
        );
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.CHINA);
    }

    private static String readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toString(StandardCharsets.UTF_8.name());
    }

    private static class ScoredDiagnosis {
        final ICD10Diagnosis diagnosis;
        final int score;

        ScoredDiagnosis(ICD10Diagnosis diagnosis, int score) {
            this.diagnosis = diagnosis;
            this.score = score;
        }
    }
}
