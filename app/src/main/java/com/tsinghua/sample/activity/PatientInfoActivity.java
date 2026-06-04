package com.tsinghua.sample.activity;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tsinghua.sample.R;
import com.tsinghua.sample.patient.icd10.ICD10Catalog;
import com.tsinghua.sample.patient.icd10.ICD10Diagnosis;
import com.tsinghua.sample.patient.model.DepartmentTemplate;
import com.tsinghua.sample.patient.model.FieldDefinition;
import com.tsinghua.sample.patient.model.FieldType;
import com.tsinghua.sample.patient.model.FieldValue;
import com.tsinghua.sample.patient.model.FormSection;
import com.tsinghua.sample.patient.model.PatientInfoData;
import com.tsinghua.sample.patient.model.PatientInfoValidator;
import com.tsinghua.sample.patient.model.PatientValueFormatter;
import com.tsinghua.sample.patient.storage.PatientRecordRepository;
import com.tsinghua.sample.patient.storage.PatientStoragePaths;
import com.tsinghua.sample.patient.storage.TemplateRepository;
import com.tsinghua.sample.patient.ui.GroupedUi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class PatientInfoActivity extends AppCompatActivity {
    private static final String TAG = "PatientInfoActivity";
    private static final int REQUEST_CAPTURE_PHOTO = 701;

    public static final String EXTRA_SESSION_DIR = "session_dir";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_RECORDING_TIME = "recording_time";

    private LinearLayout sectionsContainer;
    private TextView tvSessionId;
    private TextView tvRecordingTime;
    private TextView tvTemplateName;

    private String sessionDir;
    private String sessionId;
    private String recordingDurationText;
    private DepartmentTemplate template;
    private ICD10Catalog icd10Catalog;

    private final Map<String, EditText> textInputs = new LinkedHashMap<>();
    private final Map<String, Set<String>> multiSelectValues = new LinkedHashMap<>();
    private final Map<String, List<String>> diagnosisListValues = new LinkedHashMap<>();
    private final Map<String, TextView> diagnosisSummaryViews = new LinkedHashMap<>();
    private final Map<String, String> dateValues = new LinkedHashMap<>();
    private final Map<String, View> fieldRows = new LinkedHashMap<>();
    private final Map<String, String> capturedPhotoFileNames = new LinkedHashMap<>();
    private String pendingPhotoFieldId;
    private TextView pendingPhotoStatusView;
    private boolean submitted;

    public static void start(Context context, String sessionDir, String sessionId, String recordingDurationText) {
        Intent intent = new Intent(context, PatientInfoActivity.class);
        intent.putExtra(EXTRA_SESSION_DIR, sessionDir);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        intent.putExtra(EXTRA_RECORDING_TIME, recordingDurationText);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient_info);

        sessionDir = getIntent().getStringExtra(EXTRA_SESSION_DIR);
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        recordingDurationText = getIntent().getStringExtra(EXTRA_RECORDING_TIME);

        TemplateRepository repository = PatientStoragePaths.templateRepository(this);
        template = repository.getActiveTemplate();
        icd10Catalog = new ICD10Catalog(null);

        initViews();
        renderTemplate();
        loadIcd10CatalogAsync();
    }

    private void initViews() {
        sectionsContainer = findViewById(R.id.sectionsContainer);
        tvSessionId = findViewById(R.id.tvSessionId);
        tvRecordingTime = findViewById(R.id.tvRecordingTime);
        tvTemplateName = findViewById(R.id.tvTemplateName);
        MaterialButton btnSubmit = findViewById(R.id.btnSubmit);
        MaterialButton btnSkip = findViewById(R.id.btnSkip);

        tvSessionId.setText("会话ID: " + valueOrDash(sessionId));
        tvRecordingTime.setText("录制时长: " + valueOrDash(recordingDurationText));
        tvTemplateName.setText("使用模板: " + template.getName());

        btnSubmit.setOnClickListener(v -> submitInfo());
        btnSkip.setOnClickListener(v -> {
            Log.w(TAG, "用户跳过患者信息填写");
            cleanupDraftPhotos();
            finish();
        });
    }

    private void renderTemplate() {
        sectionsContainer.removeAllViews();
        for (FormSection section : template.getSections()) {
            sectionsContainer.addView(GroupedUi.sectionHeader(this, section.getTitle()));

            MaterialCardView card = GroupedUi.sectionCard(this);
            LinearLayout content = GroupedUi.cardContent(this);
            card.addView(content);

            for (FieldDefinition field : section.getFields()) {
                View row = createFieldView(field);
                fieldRows.put(field.getId(), row);
                content.addView(row);
            }

            sectionsContainer.addView(card, GroupedUi.matchWrap(this));
        }
        refreshConditionalVisibility();
    }

    private View createFieldView(FieldDefinition field) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, dp(12));

        switch (field.getType()) {
            case TEXT:
                row.addView(createTextInput(field, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, false));
                break;
            case NUMBER:
                row.addView(createTextInput(field, InputType.TYPE_CLASS_NUMBER, false));
                break;
            case DECIMAL:
                row.addView(createTextInput(field, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, false));
                break;
            case TEXT_AREA:
                row.addView(createTextInput(field, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, true));
                break;
            case PICKER:
            case SEGMENTED:
                row.addView(labelView(field));
                row.addView(createSingleChoiceButtons(field));
                break;
            case MULTI_SELECT:
                row.addView(labelView(field));
                row.addView(createOptionButtons(field, true));
                break;
            case DATE:
                row.addView(labelView(field));
                row.addView(createDateButton(field));
                break;
            case BLOOD_PRESSURE:
                row.addView(labelView(field));
                row.addView(createBloodPressureInputs(field));
                break;
            case ICD10_DIAGNOSIS:
                row.addView(labelView(field));
                row.addView(createSingleDiagnosisPicker(field));
                break;
            case ICD10_DIAGNOSIS_LIST:
                row.addView(labelView(field));
                row.addView(createDiagnosisListPicker(field));
                break;
            case PHOTO:
                row.addView(labelView(field));
                row.addView(createPhotoField(field));
                break;
        }
        return row;
    }

    private View createTextInput(FieldDefinition field, int inputType, boolean multiLine) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(labelView(field));

        TextInputLayout layout = new TextInputLayout(this);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText editText = new TextInputEditText(layout.getContext());
        editText.setHint(field.getPlaceholder() == null ? "请输入" + field.getLabel() : field.getPlaceholder());
        editText.setInputType(inputType);
        if (multiLine) {
            editText.setMinLines(3);
            editText.setGravity(Gravity.TOP | Gravity.START);
        }
        layout.addView(editText);
        textInputs.put(field.getId(), editText);
        editText.addTextChangedListener(new SimpleTextWatcher(this::refreshConditionalVisibility));
        wrapper.addView(layout);
        return wrapper;
    }

    private View createSingleChoiceButtons(FieldDefinition field) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        List<String> options = field.getOptions();
        if (options == null) return group;

        textInputs.put(field.getId(), hiddenTextValue(""));
        for (String option : options) {
            RadioButton radioButton = new RadioButton(this);
            radioButton.setText(option);
            radioButton.setTextSize(14);
            radioButton.setOnClickListener(v -> {
                EditText value = textInputs.get(field.getId());
                if (value != null) value.setText(option);
                refreshConditionalVisibility();
            });
            group.addView(radioButton);
        }
        return group;
    }

    private View createOptionButtons(FieldDefinition field, boolean multiSelect) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        List<String> options = field.getOptions();
        if (options == null) return group;

        for (String option : options) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(option);
            checkBox.setTextSize(14);
            checkBox.setOnCheckedChangeListener((buttonView, checked) -> {
                Set<String> selected = multiSelectValues.computeIfAbsent(field.getId(), key -> new LinkedHashSet<>());
                if (checked) {
                    selected.add(option);
                } else {
                    selected.remove(option);
                }
                refreshConditionalVisibility();
            });
            group.addView(checkBox);
        }
        return group;
    }

    private View createDateButton(FieldDefinition field) {
        TextView value = actionText(field.getPlaceholder() == null ? "选择日期" : field.getPlaceholder());
        value.setOnClickListener(v -> {
            Calendar calendar = Calendar.getInstance();
            DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                String date = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                dateValues.put(field.getId(), date);
                value.setText(date);
                refreshConditionalVisibility();
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));
            dialog.show();
        });
        return value;
    }

    private View createBloodPressureInputs(FieldDefinition field) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(compactEditText(field.getId() + "_systolic", "收缩压", "如: 120"), weightedParams(1, 0, dp(8)));
        TextView slash = new TextView(this);
        slash.setText("/");
        slash.setTextSize(20);
        slash.setGravity(Gravity.CENTER);
        row.addView(slash, new LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(compactEditText(field.getId() + "_diastolic", "舒张压", "如: 80"), weightedParams(1, dp(8), 0));
        return row;
    }

    private View createSingleDiagnosisPicker(FieldDefinition field) {
        TextView value = actionText(field.getPlaceholder() == null ? "搜索医保 ICD-10 编码或诊断名称" : field.getPlaceholder());
        textInputs.put(field.getId(), hiddenTextValue(""));
        value.setOnClickListener(v -> showDiagnosisSearchDialog(field, diagnosis -> {
            textInputs.get(field.getId()).setText(diagnosis.storageValue());
            value.setText(diagnosis.storageValue());
            refreshConditionalVisibility();
        }));
        return value;
    }

    private View createDiagnosisListPicker(FieldDefinition field) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        TextView summary = mutedText(field.getPlaceholder() == null ? "添加 ICD-10 编码或诊断名称" : field.getPlaceholder());
        diagnosisSummaryViews.put(field.getId(), summary);
        wrapper.addView(summary);

        LinearLayout selectedList = new LinearLayout(this);
        selectedList.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(selectedList);

        MaterialButton add = new MaterialButton(this);
        add.setText("添加诊断");
        add.setAllCaps(false);
        add.setOnClickListener(v -> showDiagnosisSearchDialog(field, diagnosis -> {
            List<String> values = diagnosisListValues.computeIfAbsent(field.getId(), key -> new ArrayList<>());
            if (!values.contains(diagnosis.storageValue())) {
                values.add(diagnosis.storageValue());
            }
            updateDiagnosisSummary(field.getId());
            renderDiagnosisItems(field.getId(), selectedList);
            refreshConditionalVisibility();
        }));
        wrapper.addView(add);
        renderDiagnosisItems(field.getId(), selectedList);
        return wrapper;
    }

    private View createPhotoField(FieldDefinition field) {
        TextView value = actionText("拍摄照片");
        value.setOnClickListener(v -> {
            pendingPhotoFieldId = field.getId();
            pendingPhotoStatusView = value;
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(getPackageManager()) == null) {
                Toast.makeText(this, "当前设备不支持拍照", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivityForResult(intent, REQUEST_CAPTURE_PHOTO);
        });
        return value;
    }

    private void showDiagnosisSearchDialog(FieldDefinition field, DiagnosisConsumer consumer) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), 0);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("输入编码或诊断名称");
        content.addView(search);

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(field.getLabel())
                .setView(content)
                .setNegativeButton("取消", null)
                .create();

        Runnable render = () -> {
            results.removeAllViews();
            List<ICD10Diagnosis> matches = icd10Catalog.search(search.getText().toString(), 20);
            for (ICD10Diagnosis diagnosis : matches) {
                TextView item = actionText(diagnosis.storageValue());
                item.setOnClickListener(v -> {
                    consumer.accept(diagnosis);
                    dialog.dismiss();
                });
                results.addView(item);
            }
        };
        search.addTextChangedListener(new SimpleTextWatcher(render));
        dialog.setOnShowListener(d -> render.run());
        dialog.show();
    }

    private void updateDiagnosisSummary(String fieldId) {
        TextView summary = diagnosisSummaryViews.get(fieldId);
        if (summary == null) return;
        List<String> values = diagnosisListValues.get(fieldId);
        if (values == null || values.isEmpty()) {
            summary.setText("添加 ICD-10 编码或诊断名称");
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append("\n");
            builder.append(i == 0 ? "主要诊断: " : "其他诊断: ").append(values.get(i));
        }
        summary.setText(builder.toString());
    }

    private void renderDiagnosisItems(String fieldId, LinearLayout selectedList) {
        selectedList.removeAllViews();
        List<String> values = diagnosisListValues.get(fieldId);
        if (values == null || values.isEmpty()) {
            return;
        }
        for (String diagnosis : new ArrayList<>(values)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView text = GroupedUi.body(this, diagnosis);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            MaterialButton remove = GroupedUi.textButton(this, "移除");
            remove.setOnClickListener(v -> {
                List<String> current = diagnosisListValues.get(fieldId);
                if (current != null) {
                    current.remove(diagnosis);
                }
                updateDiagnosisSummary(fieldId);
                renderDiagnosisItems(fieldId, selectedList);
                refreshConditionalVisibility();
            });
            row.addView(remove);
            selectedList.addView(row);
        }
    }

    private View compactEditText(String id, String label, String hint) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(label);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText editText = new TextInputEditText(layout.getContext());
        editText.setHint(hint);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(editText);
        textInputs.put(id, editText);
        editText.addTextChangedListener(new SimpleTextWatcher(this::refreshConditionalVisibility));
        return layout;
    }

    private EditText hiddenTextValue(String value) {
        EditText editText = new EditText(this);
        editText.setText(value);
        editText.setVisibility(View.GONE);
        return editText;
    }

    private TextView labelView(FieldDefinition field) {
        TextView label = new TextView(this);
        String unit = field.getUnit();
        boolean showUnit = unit != null
                && field.getType() != FieldType.BLOOD_PRESSURE
                && field.getType() != FieldType.ICD10_DIAGNOSIS
                && field.getType() != FieldType.ICD10_DIAGNOSIS_LIST;
        label.setText(field.getLabel() + (showUnit ? " (" + unit + ")" : "") + (field.isRequired() ? " *" : ""));
        label.setTextSize(14);
        label.setTextColor(field.isRequired() ? GroupedUi.WARNING : GroupedUi.TEXT_PRIMARY);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(0, 0, 0, dp(6));
        return label;
    }

    private TextView mutedText(String text) {
        TextView view = GroupedUi.body(this, text);
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setBackgroundResource(R.drawable.rounded_bg_light);
        return view;
    }

    private TextView actionText(String text) {
        TextView view = mutedText(text);
        view.setTextColor(0xFF0288D1);
        view.setMinHeight(dp(44));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void refreshConditionalVisibility() {
        Map<String, FieldValue> current = collectFields(false);
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                View row = fieldRows.get(field.getId());
                if (row != null) {
                    row.setVisibility(PatientInfoValidator.isVisible(field, current) ? View.VISIBLE : View.GONE);
                }
            }
        }
    }

    private void submitInfo() {
        try {
            if (sessionDir == null || sessionDir.trim().isEmpty()) {
                throw new IllegalStateException("会话目录为空");
            }
            ensurePatientInfoWritable();
            Map<String, FieldValue> fields = collectFields(true);
            PatientInfoValidator.Result validation = PatientInfoValidator.validate(template, fields);
            if (!validation.isValid()) {
                Toast.makeText(this, validation.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }

            FieldValue patientIdValue = fields.get("patientId");
            if (patientIdValue == null || patientIdValue.isEmpty()) {
                Toast.makeText(this, "请输入病历号", Toast.LENGTH_SHORT).show();
                return;
            }
            Map<String, String> finalizedPhotos = finalizeCapturedPhotos();
            for (Map.Entry<String, String> entry : finalizedPhotos.entrySet()) {
                if (!isHiddenField(entry.getKey()) && !entry.getValue().trim().isEmpty()) {
                    fields.put(entry.getKey(), FieldValue.string(entry.getValue()));
                }
            }
            String patientId = patientIdValue.asString();
            PatientInfoData data = new PatientInfoData(
                    template.getId(),
                    template.getName(),
                    patientId,
                    valueOrDash(sessionId),
                    recordingStartIso(),
                    toIsoLike(new Date()),
                    fields,
                    firstPhotoName(finalizedPhotos)
            );

            new PatientRecordRepository(null).saveForSession(new File(sessionDir), data);
            writeLegacyInfoTextBestEffort(data);
            submitted = true;
            Toast.makeText(this, "患者信息已保存", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Log.e(TAG, "保存患者信息失败", e);
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Map<String, FieldValue> collectFields(boolean skipHidden) {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                View row = fieldRows.get(field.getId());
                if (skipHidden && row != null && row.getVisibility() != View.VISIBLE) {
                    continue;
                }

                switch (field.getType()) {
                    case TEXT:
                    case NUMBER:
                    case DECIMAL:
                    case TEXT_AREA:
                    case PICKER:
                    case SEGMENTED:
                    case ICD10_DIAGNOSIS:
                        putStringIfPresent(fields, field.getId(), textOf(field.getId()), true);
                        break;
                    case MULTI_SELECT:
                        Set<String> selected = multiSelectValues.get(field.getId());
                        if (selected != null && !selected.isEmpty()) {
                            List<String> ordered = new ArrayList<>();
                            List<String> options = field.getOptions();
                            if (options != null) {
                                for (String option : options) {
                                    if (selected.contains(option)) ordered.add(option);
                                }
                            }
                            fields.put(field.getId(), FieldValue.stringArray(ordered));
                        }
                        break;
                    case DATE:
                        putDateIfPresent(fields, field.getId(), dateValues.get(field.getId()));
                        break;
                    case BLOOD_PRESSURE:
                        putStringIfPresent(fields, field.getId() + "_systolic", textOf(field.getId() + "_systolic"), false);
                        putStringIfPresent(fields, field.getId() + "_diastolic", textOf(field.getId() + "_diastolic"), false);
                        break;
                    case ICD10_DIAGNOSIS_LIST:
                        List<String> diagnoses = diagnosisListValues.get(field.getId());
                        if (diagnoses != null && !diagnoses.isEmpty()) {
                            fields.put(field.getId(), FieldValue.stringArray(diagnoses));
                        }
                        break;
                    case PHOTO:
                        break;
                }
            }
        }
        return fields;
    }

    private void putStringIfPresent(Map<String, FieldValue> fields, String id, String value, boolean skipUnfilledPicker) {
        if (value == null) return;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return;
        if (skipUnfilledPicker && "未填写".equals(trimmed)) return;
        fields.put(id, FieldValue.string(trimmed));
    }

    private void putDateIfPresent(Map<String, FieldValue> fields, String id, String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            fields.put(id, PatientValueFormatter.dateFieldValueFromInput(trimmed));
        }
    }

    private void loadIcd10CatalogAsync() {
        new Thread(() -> {
            try {
                ICD10Catalog loaded = ICD10Catalog.fromAssets(this);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        icd10Catalog = loaded;
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "ICD10资源加载失败，使用内置兜底列表", e);
            }
        }, "patient-icd10-loader").start();
    }

    private void ensurePatientInfoWritable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Log.w(TAG, "当前未授予所有文件访问权限，患者信息保存可能失败");
        }
        File dir = new File(sessionDir, PatientRecordRepository.PATIENT_INFO_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建PatientInfo目录，请检查存储权限");
        }
        if (!dir.canWrite()) {
            throw new IllegalStateException("PatientInfo目录不可写，请检查存储权限");
        }
    }

    private String textOf(String id) {
        EditText input = textInputs.get(id);
        return input == null || input.getText() == null ? "" : input.getText().toString();
    }

    private static String toIsoLike(Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(date);
    }

    private String recordingStartIso() {
        if (sessionId != null && sessionId.startsWith("Session_")) {
            try {
                String timestamp = sessionId.substring("Session_".length());
                int suffixStart = timestamp.indexOf('_');
                if (suffixStart >= 0) {
                    timestamp = timestamp.substring(0, suffixStart);
                }
                return toIsoLike(new Date(Long.parseLong(timestamp)));
            } catch (Exception ignored) {
                // Fall through to current time if this is not a timestamp-based session id.
            }
        }
        return toIsoLike(new Date());
    }

    private boolean isHiddenField(String fieldId) {
        View row = fieldRows.get(fieldId);
        return row != null && row.getVisibility() != View.VISIBLE;
    }

    private Map<String, String> finalizeCapturedPhotos() throws IOException {
        Map<String, String> finalized = new LinkedHashMap<>();
        if (capturedPhotoFileNames.isEmpty()) {
            return finalized;
        }
        File dir = new File(sessionDir, PatientRecordRepository.PATIENT_INFO_DIR);
        for (Map.Entry<String, String> entry : capturedPhotoFileNames.entrySet()) {
            String fieldId = entry.getKey();
            String draftName = entry.getValue();
            if (isHiddenField(fieldId) || draftName == null || draftName.trim().isEmpty()) {
                deletePatientInfoFile(draftName);
                continue;
            }
            String finalName = draftName.startsWith("draft_") ? draftName.substring("draft_".length()) : draftName;
            File draft = new File(dir, draftName);
            File target = uniquePhotoFile(dir, finalName);
            if (draft.exists() && !draft.equals(target)) {
                movePhotoFile(draft, target);
            }
            finalized.put(fieldId, target.getName());
        }
        capturedPhotoFileNames.clear();
        capturedPhotoFileNames.putAll(finalized);
        return finalized;
    }

    private File uniquePhotoFile(File dir, String preferredName) {
        File preferred = new File(dir, preferredName);
        if (!preferred.exists()) {
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
        } while (candidate.exists());
        return candidate;
    }

    private String firstPhotoName(Map<String, String> photos) {
        for (String value : photos.values()) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private void writeLegacyInfoTextBestEffort(PatientInfoData data) {
        try {
            writeLegacyInfoText(data);
        } catch (IOException e) {
            Log.w(TAG, "旧版info文本写入失败，JSON患者记录已保存", e);
        }
    }

    private void writeLegacyInfoText(PatientInfoData data) throws IOException {
        File infoDir = new File(sessionDir, PatientRecordRepository.LEGACY_INFO_DIR);
        if (!infoDir.exists() && !infoDir.mkdirs()) {
            throw new IOException("无法创建旧版info目录");
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File target = new File(infoDir, "subject_info_" + ts + ".txt");
        try (FileOutputStream outputStream = new FileOutputStream(target);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write(data.formatToText(template));
        }
    }

    private void movePhotoFile(File draft, File target) throws IOException {
        try {
            Files.move(draft.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException first) {
            try {
                Files.copy(draft.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (!draft.delete()) {
                    Log.w(TAG, "照片草稿删除失败: " + draft.getAbsolutePath());
                }
            } catch (IOException second) {
                IOException failure = new IOException("照片保存失败: " + draft.getName());
                failure.initCause(second);
                throw failure;
            }
        }
    }

    private static String valueOrDash(String value) {
        return value == null || value.trim().isEmpty() ? "--" : value;
    }

    private LinearLayout.LayoutParams weightedParams(float weight, int marginStart, int marginEnd) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(marginStart, 0, marginEnd, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, "请点击提交或跳过", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (!submitted) {
            cleanupDraftPhotos();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE_PHOTO || resultCode != RESULT_OK || data == null) {
            return;
        }
        Bundle extras = data.getExtras();
        Object image = extras == null ? null : extras.get("data");
        if (!(image instanceof Bitmap)) {
            Toast.makeText(this, "照片获取失败", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String capturedPhotoFileName = saveCapturedPhoto((Bitmap) image);
            if (pendingPhotoFieldId != null) {
                capturedPhotoFileNames.put(pendingPhotoFieldId, capturedPhotoFileName);
            }
            if (pendingPhotoStatusView != null) {
                pendingPhotoStatusView.setText("已保存: " + capturedPhotoFileName);
            }
            Toast.makeText(this, "照片已保存: " + capturedPhotoFileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "照片保存失败", e);
            Toast.makeText(this, "照片保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String saveCapturedPhoto(Bitmap bitmap) throws Exception {
        if (sessionDir == null || sessionDir.trim().isEmpty()) {
            throw new IllegalStateException("会话目录为空");
        }
        File dir = new File(sessionDir, PatientRecordRepository.PATIENT_INFO_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建PatientInfo目录，请检查存储权限");
        }
        if (!dir.canWrite()) {
            throw new IllegalStateException("PatientInfo目录不可写，请检查存储权限");
        }
        String suffix = pendingPhotoFieldId == null ? "photo" : pendingPhotoFieldId;
        String fileName = "draft_patient_" + suffix + "_" + System.currentTimeMillis() + ".jpg";
        File file = new File(dir, fileName);
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream);
        }
        return fileName;
    }

    private void cleanupDraftPhotos() {
        for (String fileName : new ArrayList<>(capturedPhotoFileNames.values())) {
            deletePatientInfoFile(fileName);
        }
        capturedPhotoFileNames.clear();
    }

    private void deletePatientInfoFile(String fileName) {
        if (sessionDir == null || fileName == null || fileName.trim().isEmpty()) {
            return;
        }
        try {
            File dir = new File(sessionDir, PatientRecordRepository.PATIENT_INFO_DIR).getCanonicalFile();
            File file = new File(dir, fileName).getCanonicalFile();
            File parent = file.getParentFile();
            if (parent != null && dir.equals(parent.getCanonicalFile()) && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private interface DiagnosisConsumer {
        void accept(ICD10Diagnosis diagnosis);
    }

    private static class SimpleTextWatcher implements TextWatcher {
        private final Runnable afterChange;

        SimpleTextWatcher(Runnable afterChange) {
            this.afterChange = afterChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            afterChange.run();
        }
    }
}
