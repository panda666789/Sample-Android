package com.tsinghua.sample.activity;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.tsinghua.sample.patient.icd10.ICD10Catalog;
import com.tsinghua.sample.patient.icd10.ICD10Diagnosis;
import com.tsinghua.sample.patient.model.DepartmentTemplate;
import com.tsinghua.sample.patient.model.FieldDefinition;
import com.tsinghua.sample.patient.model.FieldType;
import com.tsinghua.sample.patient.model.FieldValue;
import com.tsinghua.sample.patient.model.FormSection;
import com.tsinghua.sample.patient.model.PatientInfoData;
import com.tsinghua.sample.patient.model.PatientInfoValidator;
import com.tsinghua.sample.patient.model.PatientFieldResolver;
import com.tsinghua.sample.patient.model.PatientValueFormatter;
import com.tsinghua.sample.patient.storage.PatientRecord;
import com.tsinghua.sample.patient.storage.PatientRecordRepository;
import com.tsinghua.sample.patient.storage.PatientStoragePaths;
import com.tsinghua.sample.patient.storage.TemplateRepository;
import com.tsinghua.sample.patient.ui.GroupedUi;

import java.util.ArrayList;
import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class PatientRecordsActivity extends AppCompatActivity {
    private LinearLayout listContainer;
    private EditText searchInput;
    private List<PatientRecord> records = new ArrayList<>();
    private PatientRecordRepository recordRepository;
    private TemplateRepository templateRepository;
    private String scanErrorMessage;
    private ICD10Catalog icd10Catalog;

    public static void start(Context context) {
        context.startActivity(new Intent(context, PatientRecordsActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("患者记录");
        recordRepository = PatientStoragePaths.patientRecordRepository();
        templateRepository = PatientStoragePaths.templateRepository(this);
        icd10Catalog = new ICD10Catalog(null);
        buildLayout();
        loadRecords();
        loadIcd10CatalogAsync();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        setContentView(GroupedUi.scrollRoot(this, root));

        root.addView(GroupedUi.pageHeader(this, "患者记录", "搜索、查看并编辑已保存的患者信息"));

        searchInput = new EditText(this);
        searchInput.setHint("搜索病例号或姓名");
        searchInput.setSingleLine(true);
        searchInput.addTextChangedListener(new SimpleTextWatcher(this::renderRecords));
        root.addView(searchInput, GroupedUi.matchWrap(this));

        root.addView(GroupedUi.sectionHeader(this, "记录列表"));

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, matchWrap());
    }

    private void loadRecords() {
        PatientRecordRepository.ScanResult result = recordRepository.scanRecordsWithStatus();
        records = result.getRecords();
        scanErrorMessage = result.getErrorMessage();
        renderRecords();
    }

    private void renderRecords() {
        listContainer.removeAllViews();
        String query = searchInput.getText().toString().trim().toLowerCase();
        boolean any = false;
        for (PatientRecord record : records) {
            String patientId = nullToEmpty(record.getData().getPatientId());
            String patientName = displayPatientName(record, "");
            if (!query.isEmpty()
                    && !patientId.toLowerCase().contains(query)
                    && !patientName.toLowerCase().contains(query)) {
                continue;
            }
            any = true;
            addRecordCard(record);
        }
        if (!any) {
            String message = !isBlank(scanErrorMessage) ? scanErrorMessage : (records.isEmpty() ? "暂无患者记录" : "没有匹配记录");
            MaterialCardView card = GroupedUi.sectionCard(this);
            LinearLayout content = GroupedUi.cardContent(this);
            content.addView(GroupedUi.title(this, message));
            content.addView(GroupedUi.body(this, records.isEmpty() ? "完成一次录制并提交患者信息后会显示在这里。" : "换一个关键词试试。"));
            card.addView(content);
            listContainer.addView(card, GroupedUi.matchWrap(this));
        }
    }

    private void addRecordCard(PatientRecord record) {
        MaterialCardView card = GroupedUi.sectionCard(this);
        LinearLayout.LayoutParams cardParams = GroupedUi.matchWrap(this);

        LinearLayout content = GroupedUi.cardContent(this);
        card.addView(content);

        TextView title = GroupedUi.title(this, displayPatientName(record, "未填写姓名"));
        content.addView(title);

        content.addView(GroupedUi.metaRow(this, "病例号", nullToEmpty(record.getData().getPatientId())));
        content.addView(GroupedUi.divider(this));
        content.addView(GroupedUi.metaRow(this, "模板", record.getData().getTemplateName()));
        content.addView(GroupedUi.metaRow(this, "录制开始", PatientValueFormatter.localDateTimeText(record.getData().getRecordingStartTime())));
        content.addView(GroupedUi.metaRow(this, "填写情况", "已填写 " + filledCount(record.getData()) + " 项"));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        MaterialButton edit = GroupedUi.textButton(this, "编辑");
        edit.setOnClickListener(v -> showRecordEditor(record));
        MaterialButton delete = GroupedUi.textButton(this, "删除");
        delete.setTextColor(GroupedUi.WARNING);
        delete.setOnClickListener(v -> confirmDeleteRecord(record, null));
        actions.addView(edit);
        actions.addView(delete);
        content.addView(actions);

        card.setOnClickListener(v -> showRecordEditor(record));
        listContainer.addView(card, cardParams);
    }

    private void showRecordEditor(PatientRecord record) {
        DepartmentTemplate template = templateRepository.templateById(record.getData().getTemplateId());
        if (template == null) template = fallbackTemplateForRecord(record.getData());

        ScrollView scrollView = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(8));
        scrollView.addView(form);
        Map<String, EditText> inputs = new LinkedHashMap<>();
        Map<String, Set<String>> multiValues = new LinkedHashMap<>();
        Map<String, String> dateValues = new LinkedHashMap<>();
        Map<String, View> fieldRows = new LinkedHashMap<>();
        Runnable[] refreshRows = new Runnable[1];

        MaterialCardView sessionCard = GroupedUi.sectionCard(this);
        LinearLayout sessionContent = GroupedUi.cardContent(this);
        sessionContent.addView(GroupedUi.metaRow(this, "会话ID", record.getData().getSessionId()));
        sessionContent.addView(GroupedUi.metaRow(this, "录制开始时间", PatientValueFormatter.localDateTimeText(record.getData().getRecordingStartTime())));
        sessionCard.addView(sessionContent);
        form.addView(sessionCard, GroupedUi.matchWrap(this));

        for (FormSection section : template.getSections()) {
            form.addView(GroupedUi.sectionHeader(this, section.getTitle()));
            MaterialCardView sectionCard = GroupedUi.sectionCard(this);
            LinearLayout sectionContent = GroupedUi.cardContent(this);
            sectionCard.addView(sectionContent);
            for (FieldDefinition field : section.getFields()) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, 0, 0, dp(8));
                fieldRows.put(field.getId(), row);
                if (field.getType() == FieldType.PHOTO) {
                    addPhotoInfo(row, field.getLabel(), photoNameForField(record.getData(), field));
                } else if (field.getType() == FieldType.BLOOD_PRESSURE) {
                    addEditInput(row, inputs, field.getId() + "_systolic", field.getLabel() + " 收缩压",
                            valueOf(record.getData(), field.getId() + "_systolic"), () -> runRefresh(refreshRows));
                    addEditInput(row, inputs, field.getId() + "_diastolic", field.getLabel() + " 舒张压",
                            valueOf(record.getData(), field.getId() + "_diastolic"), () -> runRefresh(refreshRows));
                } else if (field.getType() == FieldType.PICKER || field.getType() == FieldType.SEGMENTED) {
                    addSingleChoice(row, inputs, field, valueOf(record.getData(), field.getId()), () -> runRefresh(refreshRows));
                } else if (field.getType() == FieldType.MULTI_SELECT) {
                    addMultiChoice(row, multiValues, field, record.getData().getFields().get(field.getId()), () -> runRefresh(refreshRows));
                } else if (field.getType() == FieldType.ICD10_DIAGNOSIS) {
                    addIcd10Single(row, inputs, field, valueOf(record.getData(), field.getId()), () -> runRefresh(refreshRows));
                } else if (field.getType() == FieldType.ICD10_DIAGNOSIS_LIST) {
                    addIcd10List(row, multiValues, field, record.getData().getFields().get(field.getId()), () -> runRefresh(refreshRows));
                } else if (field.getType() == FieldType.DATE) {
                    addDateInput(row, dateValues, field, dateValueOf(record.getData(), field.getId()), () -> runRefresh(refreshRows));
                } else {
                    addEditInput(row, inputs, field.getId(), field.getLabel(), valueOf(record.getData(), field.getId()), () -> runRefresh(refreshRows));
                }
                sectionContent.addView(row);
            }
            form.addView(sectionCard, GroupedUi.matchWrap(this));
        }
        DepartmentTemplate finalTemplate = template;
        refreshRows[0] = () -> refreshConditionalRows(finalTemplate, inputs, multiValues, dateValues, fieldRows);
        runRefresh(refreshRows);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("编辑患者信息")
                .setView(scrollView)
                .setPositiveButton("保存", null)
                .setNeutralButton("删除", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(shownDialog -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    PatientInfoData updated = updatedRecord(record.getData(), finalTemplate, inputs, multiValues, dateValues, fieldRows);
                    PatientInfoValidator.Result validation = PatientInfoValidator.validate(finalTemplate, updated.getFields());
                    if (!validation.isValid()) {
                        Toast.makeText(this, validation.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        recordRepository.saveExisting(record, updated);
                        Toast.makeText(this, "患者记录已更新", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        loadRecords();
                    } catch (Exception e) {
                        Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(GroupedUi.WARNING);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> confirmDeleteRecord(record, dialog::dismiss));
        });
        dialog.show();
    }

    private void confirmDeleteRecord(PatientRecord record, Runnable beforeReload) {
        String patientName = displayPatientName(record, "未填写姓名");
        new AlertDialog.Builder(this)
                .setTitle("删除患者记录")
                .setMessage("确定删除「" + patientName + "」对应的整个采集会话文件夹？患者信息、视频和设备数据都会一起删除。")
                .setPositiveButton("删除", (dialog, which) -> {
                    try {
                        boolean deleted = recordRepository.deleteRecord(record);
                        if (!deleted) {
                            Toast.makeText(this, "未删除: 会话目录不存在或不在采集目录内", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "采集会话已删除", Toast.LENGTH_SHORT).show();
                        }
                        if (beforeReload != null) {
                            beforeReload.run();
                        }
                        loadRecords();
                    } catch (Exception e) {
                        Toast.makeText(this, "删除失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addPhotoInfo(LinearLayout form, String label, String photoFileName) {
        form.addView(muted(label));
        if (photoFileName == null || photoFileName.trim().isEmpty()) {
            form.addView(muted("未拍摄照片"));
            return;
        }
        TextView photo = muted("照片文件: " + photoFileName.trim());
        form.addView(photo);
    }

    private void addEditInput(LinearLayout form, Map<String, EditText> inputs, String key, String hint, String value, Runnable afterChange) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value);
        editText.setSingleLine(false);
        editText.setMinLines(hint.contains("每行一项") ? 2 : 1);
        editText.addTextChangedListener(new SimpleTextWatcher(afterChange));
        inputs.put(key, editText);
        form.addView(editText, matchWrap());
    }

    private PatientInfoData updatedRecord(PatientInfoData original, DepartmentTemplate template, Map<String, EditText> inputs,
                                          Map<String, Set<String>> multiValues, Map<String, String> dateValues,
                                          Map<String, View> fieldRows) {
        Map<String, FieldValue> allFields = collectEditedFields(template, inputs, multiValues, dateValues);
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                if (!PatientInfoValidator.isVisible(field, allFields)) {
                    continue;
                }
                copyEditedField(field, allFields, fields);
            }
        }
        preservePhotoFields(original, template, allFields, fields);
        PatientFieldResolver.normalizePatientName(template, fields);
        FieldValue patientIdValue = fields.get("patientId");
        String patientId = patientIdValue == null ? "" : nullToEmpty(patientIdValue.asString()).trim();
        return new PatientInfoData(original.getTemplateId(), original.getTemplateName(), patientId,
                original.getSessionId(), original.getRecordingStartTime(), toIsoLike(new Date()),
                fields, original.getPhotoFileName());
    }

    private void preservePhotoFields(PatientInfoData original, DepartmentTemplate template,
                                     Map<String, FieldValue> allFields, Map<String, FieldValue> fields) {
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                if (field.getType() != FieldType.PHOTO || !PatientInfoValidator.isVisible(field, allFields)) {
                    continue;
                }
                FieldValue originalValue = original.getFields().get(field.getId());
                if (originalValue != null && !originalValue.isEmpty()) {
                    fields.put(field.getId(), originalValue);
                }
            }
        }
    }

    private Map<String, FieldValue> collectEditedFields(DepartmentTemplate template, Map<String, EditText> inputs,
                                                        Map<String, Set<String>> multiValues, Map<String, String> dateValues) {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                switch (field.getType()) {
                    case MULTI_SELECT:
                    case ICD10_DIAGNOSIS_LIST:
                        Set<String> selected = multiValues.get(field.getId());
                        if (selected != null && !selected.isEmpty()) {
                            fields.put(field.getId(), FieldValue.stringArray(new ArrayList<>(selected)));
                        }
                        break;
                    case BLOOD_PRESSURE:
                        putString(fields, field.getId() + "_systolic", text(inputs.get(field.getId() + "_systolic")));
                        putString(fields, field.getId() + "_diastolic", text(inputs.get(field.getId() + "_diastolic")));
                        break;
                    case DATE:
                        putDate(fields, field.getId(), dateValues.get(field.getId()));
                        break;
                    case PHOTO:
                        break;
                    default:
                        putString(fields, field.getId(), text(inputs.get(field.getId())));
                        break;
                }
            }
        }
        return fields;
    }

    private void copyEditedField(FieldDefinition field, Map<String, FieldValue> allFields, Map<String, FieldValue> visibleFields) {
        if (field.getType() == FieldType.BLOOD_PRESSURE) {
            copyIfPresent(allFields, visibleFields, field.getId() + "_systolic");
            copyIfPresent(allFields, visibleFields, field.getId() + "_diastolic");
        } else {
            copyIfPresent(allFields, visibleFields, field.getId());
        }
    }

    private void copyIfPresent(Map<String, FieldValue> source, Map<String, FieldValue> target, String key) {
        FieldValue value = source.get(key);
        if (value != null && !value.isEmpty()) {
            target.put(key, value);
        }
    }

    private void putString(Map<String, FieldValue> fields, String key, String value) {
        if (value != null && !value.trim().isEmpty() && !"未填写".equals(value.trim())) {
            fields.put(key, FieldValue.string(value.trim()));
        }
    }

    private void putDate(Map<String, FieldValue> fields, String key, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (!PatientValueFormatter.isValidDateInput(value)) return;
        fields.put(key, PatientValueFormatter.dateFieldValueFromInput(value));
    }

    private void addSingleChoice(LinearLayout form, Map<String, EditText> inputs, FieldDefinition field, String currentValue, Runnable afterChange) {
        form.addView(muted(field.getLabel()));
        EditText hidden = new EditText(this);
        hidden.setVisibility(View.GONE);
        hidden.setText(currentValue);
        inputs.put(field.getId(), hidden);
        form.addView(hidden);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        List<String> options = field.getOptions();
        if (options != null) {
            for (String option : options) {
                RadioButton radioButton = new RadioButton(this);
                radioButton.setText(option);
                radioButton.setChecked(option.equals(hidden.getText().toString()));
                radioButton.setOnClickListener(v -> {
                    hidden.setText(option);
                    afterChange.run();
                });
                group.addView(radioButton);
            }
        }
        form.addView(group);
    }

    private void addMultiChoice(LinearLayout form, Map<String, Set<String>> multiValues, FieldDefinition field, FieldValue currentValue, Runnable afterChange) {
        form.addView(muted(field.getLabel()));
        Set<String> selected = new LinkedHashSet<>();
        if (currentValue != null) selected.addAll(currentValue.asStringArray());
        multiValues.put(field.getId(), selected);
        List<String> options = field.getOptions();
        if (options != null) {
            for (String option : options) {
                CheckBox checkBox = new CheckBox(this);
                checkBox.setText(option);
                checkBox.setChecked(selected.contains(option));
                checkBox.setOnCheckedChangeListener((buttonView, checked) -> {
                    if (checked) selected.add(option);
                    else selected.remove(option);
                    afterChange.run();
                });
                form.addView(checkBox);
            }
        }
    }

    private void addDateInput(LinearLayout form, Map<String, String> dateValues, FieldDefinition field, String currentValue, Runnable afterChange) {
        form.addView(muted(field.getLabel()));
        TextView value = muted(isBlank(currentValue) ? "选择日期" : currentValue);
        dateValues.put(field.getId(), currentValue);
        value.setOnClickListener(v -> {
            Calendar calendar = Calendar.getInstance();
            if (!isBlank(dateValues.get(field.getId()))) {
                String[] parts = dateValues.get(field.getId()).split("-");
                if (parts.length == 3) {
                    try {
                        calendar.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
                    } catch (Exception ignored) {
                        // Keep today's date when the stored value is malformed.
                    }
                }
            }
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                String date = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                dateValues.put(field.getId(), date);
                value.setText(date);
                afterChange.run();
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
        });
        form.addView(value);
    }

    private void addIcd10Single(LinearLayout form, Map<String, EditText> inputs, FieldDefinition field, String currentValue, Runnable afterChange) {
        form.addView(muted(field.getLabel()));
        EditText hidden = new EditText(this);
        hidden.setVisibility(View.GONE);
        hidden.setText(currentValue);
        inputs.put(field.getId(), hidden);
        form.addView(hidden);
        TextView value = muted(isBlank(currentValue) ? "搜索医保 ICD-10 编码或诊断名称" : currentValue);
        value.setOnClickListener(v -> showDiagnosisSearchDialog(field, diagnosis -> {
            hidden.setText(diagnosis.storageValue());
            value.setText(diagnosis.storageValue());
            afterChange.run();
        }));
        form.addView(value);
    }

    private void addIcd10List(LinearLayout form, Map<String, Set<String>> multiValues, FieldDefinition field, FieldValue currentValue, Runnable afterChange) {
        form.addView(muted(field.getLabel()));
        Set<String> selected = new LinkedHashSet<>();
        if (currentValue != null) selected.addAll(currentValue.asStringArray());
        multiValues.put(field.getId(), selected);
        TextView summary = muted(selected.isEmpty() ? "添加 ICD-10 编码或诊断名称" : join(selected, "\n"));
        LinearLayout selectedList = new LinearLayout(this);
        selectedList.setOrientation(LinearLayout.VERTICAL);
        summary.setOnClickListener(v -> showDiagnosisSearchDialog(field, diagnosis -> {
            selected.add(diagnosis.storageValue());
            summary.setText(join(selected, "\n"));
            renderDiagnosisItems(selected, summary, selectedList, afterChange);
            afterChange.run();
        }));
        form.addView(summary);
        form.addView(selectedList);
        renderDiagnosisItems(selected, summary, selectedList, afterChange);
    }

    private void renderDiagnosisItems(Set<String> selected, TextView summary, LinearLayout selectedList, Runnable afterChange) {
        selectedList.removeAllViews();
        for (String diagnosis : new ArrayList<>(selected)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView text = GroupedUi.body(this, diagnosis);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            MaterialButton remove = GroupedUi.textButton(this, "移除");
            remove.setOnClickListener(v -> {
                selected.remove(diagnosis);
                summary.setText(selected.isEmpty() ? "添加 ICD-10 编码或诊断名称" : join(selected, "\n"));
                renderDiagnosisItems(selected, summary, selectedList, afterChange);
                afterChange.run();
            });
            row.addView(remove);
            selectedList.addView(row);
        }
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
            for (ICD10Diagnosis diagnosis : icd10Catalog.search(search.getText().toString(), 20)) {
                TextView item = muted(diagnosis.storageValue());
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

    private void refreshConditionalRows(DepartmentTemplate template, Map<String, EditText> inputs,
                                        Map<String, Set<String>> multiValues, Map<String, String> dateValues,
                                        Map<String, View> fieldRows) {
        Map<String, FieldValue> current = new LinkedHashMap<>();
        for (Map.Entry<String, EditText> entry : inputs.entrySet()) {
            putString(current, entry.getKey(), text(entry.getValue()));
        }
        for (Map.Entry<String, Set<String>> entry : multiValues.entrySet()) {
            if (!entry.getValue().isEmpty()) current.put(entry.getKey(), FieldValue.stringArray(new ArrayList<>(entry.getValue())));
        }
        for (Map.Entry<String, String> entry : dateValues.entrySet()) {
            putDate(current, entry.getKey(), entry.getValue());
        }
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                View row = fieldRows.get(field.getId());
                if (row != null) {
                    row.setVisibility(PatientInfoValidator.isVisible(field, current) ? View.VISIBLE : View.GONE);
                }
            }
        }
    }

    private String fieldText(PatientInfoData data, String fieldId) {
        return fieldText(data, fieldId, "");
    }

    private String fieldText(PatientInfoData data, String fieldId, String fallback) {
        FieldValue value = data.getFields().get(fieldId);
        String text = value == null ? "" : value.asString();
        return text == null || text.trim().isEmpty() ? fallback : text;
    }

    private String displayPatientName(PatientRecord record, String fallback) {
        DepartmentTemplate template = templateRepository.templateById(record.getData().getTemplateId());
        if (template == null) {
            template = fallbackTemplateForRecord(record.getData());
        }
        return PatientFieldResolver.patientName(record.getData(), template, fallback);
    }

    private String valueOf(PatientInfoData data, String key) {
        FieldValue value = data.getFields().get(key);
        if (value == null) return "";
        if (FieldValue.TYPE_STRING_ARRAY.equals(value.getType())) return joinLines(value);
        return nullToEmpty(value.asString());
    }

    private String dateValueOf(PatientInfoData data, String key) {
        return PatientValueFormatter.dateInputText(data.getFields().get(key));
    }

    private String photoNameForField(PatientInfoData data, FieldDefinition field) {
        FieldValue fieldValue = data.getFields().get(field.getId());
        String fieldPhoto = fieldValue == null ? "" : nullToEmpty(fieldValue.asString()).trim();
        return fieldPhoto.isEmpty() ? nullToEmpty(data.getPhotoFileName()).trim() : fieldPhoto;
    }

    private int filledCount(PatientInfoData data) {
        int count = 0;
        for (FieldValue value : data.getFields().values()) {
            if (value != null && !value.isEmpty()) count++;
        }
        return count;
    }

    private TextView muted(String text) {
        return GroupedUi.body(this, nullToEmpty(text));
    }

    private static String joinLines(FieldValue value) {
        if (value == null) return "";
        if (!FieldValue.TYPE_STRING_ARRAY.equals(value.getType())) return nullToEmpty(value.asString());
        StringBuilder builder = new StringBuilder();
        List<String> lines = value.asStringArray();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) builder.append("\n");
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    private static List<String> splitLines(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private static String text(EditText input) {
        return input == null || input.getText() == null ? "" : input.getText().toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
            } catch (Exception ignored) {
                // The fallback diagnosis list remains available.
            }
        }, "patient-records-icd10-loader").start();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private DepartmentTemplate fallbackTemplateForRecord(PatientInfoData data) {
        List<FieldDefinition> fields = new ArrayList<>();
        for (Map.Entry<String, FieldValue> entry : data.getFields().entrySet()) {
            if ("patientId".equals(entry.getKey())) {
                fields.add(FieldDefinition.of("patientId", "病历号", FieldType.TEXT).required());
            } else if ("patientName".equals(entry.getKey())) {
                fields.add(FieldDefinition.of("patientName", "姓名", FieldType.TEXT));
            } else {
                fields.add(FieldDefinition.of(entry.getKey(), entry.getKey(), FieldType.TEXT_AREA));
            }
        }
        if (data.getFields().get("patientId") == null) {
            fields.add(0, FieldDefinition.of("patientId", "病历号", FieldType.TEXT).required());
        }
        return new DepartmentTemplate(data.getTemplateId(), data.getTemplateName(), "记录原模板不可用", false,
                java.util.Collections.singletonList(new FormSection("legacy", "记录字段", fields)));
    }

    private static void runRefresh(Runnable[] refreshHolder) {
        if (refreshHolder != null && refreshHolder.length > 0 && refreshHolder[0] != null) {
            refreshHolder[0].run();
        }
    }

    private static String join(Set<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (String value : values) {
            if (index > 0) builder.append(separator);
            builder.append(value);
            index++;
        }
        return builder.toString();
    }

    private interface DiagnosisConsumer {
        void accept(ICD10Diagnosis diagnosis);
    }

    private static String toIsoLike(Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(date);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
