package com.tsinghua.sample.activity;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.tsinghua.sample.patient.model.DepartmentTemplate;
import com.tsinghua.sample.patient.model.FieldCondition;
import com.tsinghua.sample.patient.model.FieldDefinition;
import com.tsinghua.sample.patient.model.FieldType;
import com.tsinghua.sample.patient.model.FormSection;
import com.tsinghua.sample.patient.storage.PatientStoragePaths;
import com.tsinghua.sample.patient.storage.TemplateRepository;
import com.tsinghua.sample.patient.ui.GroupedUi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class TemplateEditorActivity extends AppCompatActivity {
    private static final String EXTRA_TEMPLATE_ID = "template_id";

    private TemplateRepository repository;
    private DepartmentTemplate template;
    private LinearLayout root;
    private EditText etName;
    private EditText etDescription;

    public static void start(Context context, String templateId) {
        Intent intent = new Intent(context, TemplateEditorActivity.class);
        intent.putExtra(EXTRA_TEMPLATE_ID, templateId);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = PatientStoragePaths.templateRepository(this);
        String templateId = getIntent().getStringExtra(EXTRA_TEMPLATE_ID);
        template = repository.templateById(templateId);
        if (template == null || template.isBuiltIn()) {
            Toast.makeText(this, "只能编辑自定义模板", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setTitle("编辑模板");
        render();
    }

    private void render() {
        root = new LinearLayout(this);
        setContentView(GroupedUi.scrollRoot(this, root));

        root.addView(GroupedUi.pageHeader(this, "编辑模板", "调整分区、字段、必填项与条件显示"));

        root.addView(GroupedUi.sectionHeader(this, "模板信息"));
        MaterialCardView infoCard = GroupedUi.sectionCard(this);
        LinearLayout infoContent = GroupedUi.cardContent(this);
        infoCard.addView(infoContent);

        etName = edit("模板名称", template.getName());
        etDescription = edit("用途说明", template.getDescription());
        infoContent.addView(etName, matchWrap());
        infoContent.addView(etDescription, matchWrap());
        root.addView(infoCard, GroupedUi.matchWrap(this));

        root.addView(GroupedUi.sectionHeader(this, "操作"));
        MaterialCardView actionCard = GroupedUi.sectionCard(this);
        LinearLayout actionContent = GroupedUi.cardContent(this);
        actionCard.addView(actionContent);

        MaterialButton save = GroupedUi.primaryButton(this, "保存模板");
        save.setOnClickListener(v -> saveTemplateAndExit());
        actionContent.addView(save, matchWrap());

        MaterialButton addSection = GroupedUi.secondaryButton(this, "添加新分区");
        addSection.setOnClickListener(v -> promptAddSection());
        actionContent.addView(addSection, matchWrap());
        root.addView(actionCard, GroupedUi.matchWrap(this));

        for (int i = 0; i < template.getSections().size(); i++) {
            addSectionCard(i, template.getSections().get(i));
        }
    }

    private void addSectionCard(int sectionIndex, FormSection section) {
        root.addView(GroupedUi.sectionHeader(this, section.getTitle()));
        MaterialCardView card = GroupedUi.sectionCard(this);
        LinearLayout.LayoutParams cardParams = GroupedUi.matchWrap(this);

        LinearLayout content = GroupedUi.cardContent(this);
        card.addView(content);

        content.addView(GroupedUi.metaRow(this, "字段数", section.getFields().size() + " 项"));
        content.addView(GroupedUi.divider(this));

        LinearLayout sectionActions = new LinearLayout(this);
        sectionActions.setGravity(Gravity.END);
        sectionActions.addView(smallButton("重命名", () -> promptRenameSection(sectionIndex)));
        sectionActions.addView(smallButton("添加采集项", () -> addField(sectionIndex)));
        sectionActions.addView(smallButton("删除分区", () -> confirmDeleteSection(sectionIndex)));
        content.addView(sectionActions);

        for (int fieldIndex = 0; fieldIndex < section.getFields().size(); fieldIndex++) {
            FieldDefinition field = section.getFields().get(fieldIndex);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            row.addView(GroupedUi.title(this, field.getLabel()));
            StringBuilder detail = new StringBuilder(field.getType().getDisplayName());
            if (field.isRequired()) detail.append(" / 必填");
            if (field.getUnit() != null) detail.append(" / ").append(field.getUnit());
            if (field.getVisibleWhen() != null) detail.append(" / 条件显示");
            row.addView(GroupedUi.caption(this, detail.toString()));
            int finalFieldIndex = fieldIndex;
            LinearLayout fieldActions = new LinearLayout(this);
            fieldActions.setGravity(Gravity.END);
            MaterialButton edit = GroupedUi.textButton(this, "编辑");
            edit.setOnClickListener(v -> showFieldEditor(sectionIndex, finalFieldIndex));
            MaterialButton moveUp = GroupedUi.textButton(this, "上移");
            moveUp.setEnabled(fieldIndex > 0);
            moveUp.setOnClickListener(v -> moveField(sectionIndex, finalFieldIndex, -1));
            MaterialButton moveDown = GroupedUi.textButton(this, "下移");
            moveDown.setEnabled(fieldIndex < section.getFields().size() - 1);
            moveDown.setOnClickListener(v -> moveField(sectionIndex, finalFieldIndex, 1));
            fieldActions.addView(edit);
            fieldActions.addView(moveUp);
            fieldActions.addView(moveDown);
            row.addView(fieldActions);
            row.setOnClickListener(v -> showFieldEditor(sectionIndex, finalFieldIndex));
            content.addView(row);
            if (fieldIndex < section.getFields().size() - 1) {
                content.addView(GroupedUi.divider(this));
            }
        }

        root.addView(card, cardParams);
    }

    private void moveField(int sectionIndex, int fieldIndex, int delta) {
        List<FieldDefinition> fields = template.getSections().get(sectionIndex).getFields();
        int targetIndex = fieldIndex + delta;
        if (targetIndex < 0 || targetIndex >= fields.size()) {
            return;
        }
        Collections.swap(fields, fieldIndex, targetIndex);
        if (saveTemplate()) {
            render();
        } else {
            Collections.swap(fields, fieldIndex, targetIndex);
        }
    }

    private void showFieldEditor(int sectionIndex, int fieldIndex) {
        FieldDefinition original = template.getSections().get(sectionIndex).getFields().get(fieldIndex);
        boolean isPatientId = "patientId".equals(original.getId());
        FieldDraft draft = FieldDraft.from(original);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(8));
        scrollView.addView(form);

        EditText label = edit("采集项名称", draft.label);

        Spinner type = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, fieldTypeLabels());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        type.setAdapter(adapter);
        type.setSelection(Arrays.asList(FieldType.values()).indexOf(draft.type));
        type.setEnabled(!isPatientId);
        TextView typeDescription = GroupedUi.caption(this, fieldTypeDescription(draft.type));

        EditText placeholder = edit("输入提示", draft.placeholder);
        EditText unit = edit("单位", draft.unit);

        CheckBox required = new CheckBox(this);
        required.setText("设为必填项");
        required.setChecked(isPatientId || draft.required);
        required.setEnabled(!isPatientId);
        updateRequiredControl(required, isPatientId ? FieldType.TEXT : draft.type, isPatientId);

        form.addView(GroupedUi.sectionHeader(this, "基础设置"));
        MaterialCardView basicCard = GroupedUi.sectionCard(this);
        LinearLayout basicContent = GroupedUi.cardContent(this);
        basicCard.addView(basicContent);
        basicContent.addView(label);
        basicContent.addView(labeledBlock("字段类型", type, typeDescription));
        basicContent.addView(placeholder);
        basicContent.addView(unit);
        basicContent.addView(required);
        form.addView(basicCard, GroupedUi.matchWrap(this));

        TextView optionsHeader = GroupedUi.sectionHeader(this, "选项");
        form.addView(optionsHeader);
        MaterialCardView optionsCard = GroupedUi.sectionCard(this);
        LinearLayout optionsContent = GroupedUi.cardContent(this);
        optionsCard.addView(optionsContent);
        optionsContent.addView(GroupedUi.body(this, "下拉选择、按钮选择和多选会使用这些选项。"));
        EditText options = edit("每行一个选项，如: 未填写", joinLines(draft.options));
        options.setMinLines(3);
        optionsContent.addView(options);
        form.addView(optionsCard, GroupedUi.matchWrap(this));

        form.addView(GroupedUi.sectionHeader(this, "条件显示"));
        MaterialCardView conditionCard = GroupedUi.sectionCard(this);
        LinearLayout conditionContent = GroupedUi.cardContent(this);
        conditionCard.addView(conditionContent);
        CheckBox hasCondition = new CheckBox(this);
        hasCondition.setText("仅在特定条件下显示");
        hasCondition.setChecked(draft.visibleWhen != null);
        conditionContent.addView(hasCondition);

        List<FieldDefinition> conditionFields = conditionSourceFields(original.getId());
        Spinner conditionField = new Spinner(this);
        ArrayAdapter<String> conditionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, conditionFieldLabels(conditionFields));
        conditionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        conditionField.setAdapter(conditionAdapter);
        conditionField.setSelection(conditionFieldSelection(conditionFields, draft.visibleWhen));
        EditText conditionEquals = edit("等于这个值时显示", draft.visibleWhen == null ? "" : draft.visibleWhen.getEquals());
        TextView conditionHelp = GroupedUi.caption(this, "例如：字段「性别」等于「男」时显示本项。");
        conditionContent.addView(labeledBlock("依赖字段", conditionField, conditionHelp));
        conditionContent.addView(conditionEquals);
        form.addView(conditionCard, GroupedUi.matchWrap(this));

        updateOptionsVisibility(optionsHeader, optionsCard, isPatientId ? FieldType.TEXT : draft.type, isPatientId);
        updateConditionControls(hasCondition, conditionField, conditionEquals, conditionHelp, isPatientId, conditionFields);
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                FieldType selected = FieldType.values()[position];
                typeDescription.setText(fieldTypeDescription(selected));
                updateRequiredControl(required, selected, isPatientId);
                updateOptionsVisibility(optionsHeader, optionsCard, selected, isPatientId);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        hasCondition.setOnCheckedChangeListener((buttonView, checked) ->
                updateConditionControls(hasCondition, conditionField, conditionEquals, conditionHelp, isPatientId, conditionFields));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("编辑采集项")
                .setView(scrollView)
                .setPositiveButton("完成", null)
                .setNeutralButton("删除", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(shownDialog -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    draft.label = label.getText().toString().trim();
                    draft.type = isPatientId ? FieldType.TEXT : FieldType.values()[type.getSelectedItemPosition()];
                    draft.placeholder = emptyToNull(placeholder.getText().toString().trim());
                    draft.unit = isPatientId ? null : emptyToNull(unit.getText().toString().trim());
                    draft.required = isPatientId || required.isChecked();
                    if (isNeverRequiredType(draft.type)) {
                        draft.required = false;
                    }
                    draft.options = isPatientId ? null : splitLines(options.getText().toString());
                    if (hasCondition.isChecked()
                            && !isPatientId
                            && !conditionFields.isEmpty()
                            && !conditionEquals.getText().toString().trim().isEmpty()) {
                        draft.visibleWhen = new FieldCondition(
                                conditionFields.get(conditionField.getSelectedItemPosition()).getId(),
                                conditionEquals.getText().toString().trim());
                    } else if (hasCondition.isChecked() && !isPatientId) {
                        toast(conditionFields.isEmpty() ? "没有可作为条件的字段" : "请填写条件值");
                        return;
                    } else {
                        draft.visibleWhen = null;
                    }
                    if (isPatientId) {
                        draft.id = "patientId";
                        if (draft.label.isEmpty()) draft.label = "病历号";
                        if (draft.placeholder == null) draft.placeholder = "请输入病历号";
                        draft.visibleWhen = null;
                    }
                    if (draft.label.isEmpty()) {
                        toast("采集项名称不能为空");
                        return;
                    }
                    FieldDefinition previous = template.getSections().get(sectionIndex).getFields().get(fieldIndex);
                    template.getSections().get(sectionIndex).getFields().set(fieldIndex, draft.toField());
                    if (saveTemplate()) {
                        dialog.dismiss();
                        render();
                    } else {
                        template.getSections().get(sectionIndex).getFields().set(fieldIndex, previous);
                    }
                });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    if (isPatientId) {
                        toast("病历号为必需字段，不能删除");
                        return;
                    }
                    FieldDefinition removed = template.getSections().get(sectionIndex).getFields().remove(fieldIndex);
                    if (saveTemplate()) {
                        dialog.dismiss();
                        render();
                    } else {
                        template.getSections().get(sectionIndex).getFields().add(fieldIndex, removed);
                    }
                });
        });
        dialog.show();
    }

    private void addField(int sectionIndex) {
        FieldDefinition field = FieldDefinition.of("field_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "新采集项", FieldType.TEXT);
        template.getSections().get(sectionIndex).getFields().add(field);
        if (saveTemplate()) {
            render();
        } else {
            template.getSections().get(sectionIndex).getFields().remove(field);
        }
    }

    private void promptAddSection() {
        EditText input = new EditText(this);
        input.setHint("分区名称，如：化验指标");
        new AlertDialog.Builder(this)
                .setTitle("添加新分区")
                .setView(input)
                .setPositiveButton("添加", (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    if (title.isEmpty()) return;
                    FormSection section = new FormSection("section_" + System.currentTimeMillis(), title, new ArrayList<>());
                    template.getSections().add(section);
                    if (saveTemplate()) {
                        render();
                    } else {
                        template.getSections().remove(section);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptRenameSection(int index) {
        EditText input = new EditText(this);
        input.setText(template.getSections().get(index).getTitle());
        new AlertDialog.Builder(this)
                .setTitle("重命名分区")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    if (!title.isEmpty()) {
                        String previous = template.getSections().get(index).getTitle();
                        template.getSections().get(index).setTitle(title);
                        if (saveTemplate()) {
                            render();
                        } else {
                            template.getSections().get(index).setTitle(previous);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteSection(int index) {
        if (sectionContainsPatientId(template.getSections().get(index))) {
            toast("包含病历号的分区不能删除");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("删除该分区及其中所有采集项？")
                .setPositiveButton("删除", (dialog, which) -> {
                    FormSection removed = template.getSections().remove(index);
                    if (saveTemplate()) {
                        render();
                    } else {
                        template.getSections().add(index, removed);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean saveTemplate() {
        template.setName(etName == null ? template.getName() : etName.getText().toString().trim());
        template.setDescription(etDescription == null ? template.getDescription() : etDescription.getText().toString().trim());
        TemplateRepository.ensureRequiredPatientId(template);
        try {
            repository.saveCustomTemplate(template);
            return true;
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
            return false;
        }
    }

    private void saveTemplateAndExit() {
        if (saveTemplate()) {
            toast("模板已保存");
            finish();
        }
    }

    private EditText edit(String hint, String value) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value == null ? "" : value);
        editText.setSingleLine(false);
        editText.setPadding(0, dp(8), 0, dp(8));
        return editText;
    }

    private List<String> fieldTypeLabels() {
        List<String> labels = new ArrayList<>();
        for (FieldType value : FieldType.values()) {
            labels.add(value.getDisplayName());
        }
        return labels;
    }

    private LinearLayout labeledBlock(String label, View control, TextView helper) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(8), 0, dp(8));
        block.addView(GroupedUi.caption(this, label));
        block.addView(control, matchWrap());
        if (helper != null) {
            block.addView(helper);
        }
        return block;
    }

    private MaterialButton smallButton(String text, Runnable action) {
        MaterialButton button = GroupedUi.textButton(this, text);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static boolean sectionContainsPatientId(FormSection section) {
        if (section == null) return false;
        for (FieldDefinition field : section.getFields()) {
            if ("patientId".equals(field.getId())) {
                return true;
            }
        }
        return false;
    }

    private void updateRequiredControl(CheckBox required, FieldType type, boolean isPatientId) {
        if (isPatientId) {
            required.setText("设为必填项");
            required.setChecked(true);
            required.setEnabled(false);
            return;
        }
        if (isNeverRequiredType(type)) {
            required.setText("设为必填项（血压和照片不支持必填）");
            required.setChecked(false);
            required.setEnabled(false);
            return;
        }
        required.setText("设为必填项");
        required.setEnabled(true);
    }

    private void updateOptionsVisibility(View optionsHeader, View optionsCard, FieldType type, boolean isPatientId) {
        int visibility = !isPatientId && type != null && type.needsOptions() ? View.VISIBLE : View.GONE;
        optionsHeader.setVisibility(visibility);
        optionsCard.setVisibility(visibility);
    }

    private void updateConditionControls(CheckBox hasCondition, Spinner conditionField, EditText conditionEquals,
                                         TextView conditionHelp, boolean isPatientId,
                                         List<FieldDefinition> conditionFields) {
        boolean enabled = hasCondition.isChecked() && !isPatientId && !conditionFields.isEmpty();
        conditionField.setEnabled(enabled);
        conditionEquals.setEnabled(enabled);
        if (isPatientId) {
            conditionHelp.setText("病历号是基础必填项，始终显示。");
        } else if (conditionFields.isEmpty()) {
            conditionHelp.setText("当前模板里还没有可作为条件的其他字段。");
        } else {
            conditionHelp.setText("当依赖字段的保存值等于条件值时显示本项。");
        }
    }

    private List<FieldDefinition> conditionSourceFields(String currentFieldId) {
        List<FieldDefinition> fields = new ArrayList<>();
        for (FormSection section : template.getSections()) {
            for (FieldDefinition field : section.getFields()) {
                if (field.getId() == null || field.getId().equals(currentFieldId)) {
                    continue;
                }
                if (field.getType() == FieldType.PHOTO || field.getType() == FieldType.BLOOD_PRESSURE
                        || field.getType() == FieldType.MULTI_SELECT || field.getType() == FieldType.ICD10_DIAGNOSIS_LIST) {
                    continue;
                }
                if (wouldCreateConditionCycle(currentFieldId, field.getId())) {
                    continue;
                }
                fields.add(field);
            }
        }
        return fields;
    }

    private boolean wouldCreateConditionCycle(String currentFieldId, String candidateConditionFieldId) {
        String next = candidateConditionFieldId;
        List<String> seen = new ArrayList<>();
        while (next != null && !next.trim().isEmpty()) {
            if (currentFieldId != null && currentFieldId.equals(next)) {
                return true;
            }
            if (seen.contains(next)) {
                return true;
            }
            seen.add(next);
            FieldDefinition nextField = template.fieldById(next);
            if (nextField == null || nextField.getVisibleWhen() == null) {
                return false;
            }
            next = nextField.getVisibleWhen().getFieldId();
        }
        return false;
    }

    private List<String> conditionFieldLabels(List<FieldDefinition> fields) {
        List<String> labels = new ArrayList<>();
        for (FieldDefinition field : fields) {
            labels.add(field.getLabel() + " (" + field.getType().getDisplayName() + ")");
        }
        if (labels.isEmpty()) {
            labels.add("无可用字段");
        }
        return labels;
    }

    private int conditionFieldSelection(List<FieldDefinition> fields, FieldCondition condition) {
        if (condition == null || condition.getFieldId() == null) {
            return 0;
        }
        for (int i = 0; i < fields.size(); i++) {
            if (condition.getFieldId().equals(fields.get(i).getId())) {
                return i;
            }
        }
        return 0;
    }

    private static String fieldTypeDescription(FieldType type) {
        if (type == null) return "";
        switch (type) {
            case TEXT:
                return "用于姓名、病历号等短文本。";
            case NUMBER:
                return "只填写整数，例如年龄。";
            case DECIMAL:
                return "可填写小数，适合黄疸值、心率、化验指标。";
            case PICKER:
                return "从一组选项中选择一个，呈现为下拉/单选。";
            case SEGMENTED:
                return "从少量选项中选择一个，适合性别等快速选择。";
            case MULTI_SELECT:
                return "可同时选择多个选项。";
            case DATE:
                return "通过日期选择器填写，保存为 iOS 兼容日期。";
            case TEXT_AREA:
                return "多行文本，适合备注或补充说明。";
            case BLOOD_PRESSURE:
                return "自动拆成收缩压和舒张压，血压不支持必填。";
            case ICD10_DIAGNOSIS:
                return "搜索并保存单个医保 ICD-10 诊断。";
            case ICD10_DIAGNOSIS_LIST:
                return "搜索并保存多个医保 ICD-10 诊断。";
            case PHOTO:
                return "采集患者照片，照片不支持必填。";
            default:
                return "";
        }
    }

    private static boolean isNeverRequiredType(FieldType type) {
        return type == FieldType.BLOOD_PRESSURE || type == FieldType.PHOTO;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
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

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) builder.append("\n");
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    private static class FieldDraft {
        String id;
        String label;
        FieldType type;
        String placeholder;
        boolean required;
        List<String> options;
        String unit;
        FieldCondition visibleWhen;

        static FieldDraft from(FieldDefinition field) {
            FieldDraft draft = new FieldDraft();
            draft.id = field.getId();
            draft.label = field.getLabel();
            draft.type = field.getType();
            draft.placeholder = field.getPlaceholder();
            draft.required = field.isRequired();
            draft.options = field.getOptions();
            draft.unit = field.getUnit();
            draft.visibleWhen = field.getVisibleWhen();
            return draft;
        }

        FieldDefinition toField() {
            FieldDefinition field = new FieldDefinition(id, label, type, placeholder, required,
                    type.needsOptions() ? options : null, unit, visibleWhen);
            return field;
        }
    }
}
