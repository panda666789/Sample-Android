package com.tsinghua.sample.activity;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.tsinghua.sample.patient.model.DepartmentTemplate;
import com.tsinghua.sample.patient.model.FieldDefinition;
import com.tsinghua.sample.patient.model.FormSection;
import com.tsinghua.sample.patient.storage.PatientStoragePaths;
import com.tsinghua.sample.patient.storage.TemplateRepository;
import com.tsinghua.sample.patient.ui.GroupedUi;

public class TemplateListActivity extends AppCompatActivity {
    private LinearLayout listContainer;
    private TemplateRepository repository;

    public static void start(Context context) {
        context.startActivity(new Intent(context, TemplateListActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = PatientStoragePaths.templateRepository(this);
        setTitle("科室模板");
        buildLayout();
        renderTemplates();
    }

    private void buildLayout() {
        listContainer = new LinearLayout(this);
        setContentView(GroupedUi.scrollRoot(this, listContainer));
    }

    private void renderTemplates() {
        listContainer.removeAllViews();

        listContainer.addView(GroupedUi.pageHeader(this, "科室模板", "管理内置模板与自定义采集表单"));

        MaterialButton create = GroupedUi.primaryButton(this, "新建自定义模板");
        create.setOnClickListener(v -> promptCreateTemplate());
        listContainer.addView(create, GroupedUi.matchWrap(this));

        if (!repository.getLastLoadError().isEmpty()) {
            TextView warning = GroupedUi.body(this, repository.getLastLoadError());
            warning.setTextColor(GroupedUi.WARNING);
            listContainer.addView(warning, GroupedUi.matchWrap(this));
        }

        addSectionTitle("内置模板");
        for (DepartmentTemplate template : repository.allTemplates()) {
            if (template.isBuiltIn()) addTemplateCard(template);
        }

        addSectionTitle("自定义模板");
        boolean hasCustom = false;
        for (DepartmentTemplate template : repository.customTemplates()) {
            hasCustom = true;
            addTemplateCard(template);
        }
        if (!hasCustom) {
            TextView empty = GroupedUi.body(this, "暂无自定义模板，可复制内置模板后编辑。");
            listContainer.addView(empty, GroupedUi.matchWrap(this));
        }
    }

    private void addTemplateCard(DepartmentTemplate template) {
        MaterialCardView card = GroupedUi.sectionCard(this);
        LinearLayout.LayoutParams cardParams = GroupedUi.matchWrap(this);

        LinearLayout content = GroupedUi.cardContent(this);
        card.addView(content);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = GroupedUi.title(this, template.getName());
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (template.isBuiltIn()) {
            header.addView(GroupedUi.chip(this, "内置", 0xFF0F766E, GroupedUi.ACCENT_BG));
        }
        if (repository.getActiveTemplate().getId().equals(template.getId())) {
            header.addView(GroupedUi.chip(this, "使用中", 0xFF1565C0, 0xFFE8F1FF));
        }
        content.addView(header);

        TextView desc = GroupedUi.body(this, template.getDescription());
        content.addView(desc);

        content.addView(GroupedUi.metaRow(this, "结构", template.getSections().size() + " 个分区 / " + template.allFieldIds().size() + " 个字段"));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        content.addView(actions);

        LinearLayout primaryActions = actionRow();
        actions.addView(primaryActions);
        primaryActions.addView(smallButton("启用", () -> {
            try {
                repository.setActiveTemplateId(template.getId());
                renderTemplates();
            } catch (Exception e) {
                toast("启用失败: " + e.getMessage());
            }
        }));
        primaryActions.addView(smallButton("预览", () -> showPreview(template)));
        primaryActions.addView(smallButton("复制", () -> promptDuplicate(template)));
        if (!template.isBuiltIn()) {
            LinearLayout editActions = actionRow();
            actions.addView(editActions);
            editActions.addView(smallButton("编辑", () -> TemplateEditorActivity.start(this, template.getId())));
            editActions.addView(smallButton("删除", () -> confirmDelete(template)));
        }

        listContainer.addView(card, cardParams);
    }

    private void showPreview(DepartmentTemplate template) {
        StringBuilder builder = new StringBuilder();
        builder.append(template.getName()).append("\n\n").append(template.getDescription()).append("\n\n");
        for (FormSection section : template.getSections()) {
            builder.append("【").append(section.getTitle()).append("】\n");
            for (FieldDefinition field : section.getFields()) {
                builder.append("- ").append(field.getLabel()).append(" / ").append(field.getType().getDisplayName());
                if (field.isRequired()) builder.append(" / 必填");
                if (field.getUnit() != null) builder.append(" / ").append(field.getUnit());
                builder.append("\n");
            }
            builder.append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("模板预览")
                .setMessage(builder.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void promptCreateTemplate() {
        EditText input = new EditText(this);
        input.setHint("模板名称");
        new AlertDialog.Builder(this)
                .setTitle("新建模板")
                .setView(input)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("请输入模板名称");
                        return;
                    }
                    try {
                        DepartmentTemplate created = repository.createBlankTemplate(name);
                        TemplateEditorActivity.start(this, created.getId());
                    } catch (Exception e) {
                        toast("创建失败: " + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptDuplicate(DepartmentTemplate source) {
        EditText input = new EditText(this);
        input.setText(source.getName() + " 自定义");
        new AlertDialog.Builder(this)
                .setTitle("复制模板")
                .setView(input)
                .setPositiveButton("复制", (dialog, which) -> {
                    try {
                        DepartmentTemplate created = repository.duplicateTemplate(source, input.getText().toString().trim());
                        TemplateEditorActivity.start(this, created.getId());
                    } catch (Exception e) {
                        toast("复制失败: " + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(DepartmentTemplate template) {
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("删除后无法恢复，确认删除「" + template.getName() + "」？")
                .setPositiveButton("删除", (dialog, which) -> {
                    repository.deleteCustomTemplate(template.getId());
                    renderTemplates();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (listContainer != null) renderTemplates();
    }

    private void addSectionTitle(String text) {
        listContainer.addView(GroupedUi.sectionHeader(this, text));
    }

    private TextView muted(String text) {
        return GroupedUi.body(this, text);
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        return row;
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
}
