package com.tsinghua.sample;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class TimestampFragment extends BottomSheetDialogFragment {

    private EditText etNewLabel;
    private Button btnAddLabel;
    private ListView lvLabels;
    private TextView tvPreview;
    private Button btnSaveRecords;

    // 存储所有记录的事件（每条记录格式：“标签: 时间戳”）
    private ArrayList<String> recordedEvents = new ArrayList<>();

    // 标签列表（仅存标签，不包含记录），并持久化到 SharedPreferences
    private ArrayList<String> tagList = new ArrayList<>();

    private TagAdapter adapter;

    // SharedPreferences keys
    private static final String PREFS_NAME = "LabelPrefs";
    private static final String KEY_TAGS = "saved_tags";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_timestamp, container, false);
        etNewLabel = view.findViewById(R.id.et_new_label);
        btnAddLabel = view.findViewById(R.id.btn_add_label);
        lvLabels = view.findViewById(R.id.lv_labels);
        tvPreview = view.findViewById(R.id.tv_preview);
        btnSaveRecords = view.findViewById(R.id.btn_save_records);

        // 从 SharedPreferences 加载已有标签
        loadTags();

        adapter = new TagAdapter(getContext(), tagList);
        lvLabels.setAdapter(adapter);

        // 添加新标签（仅添加到标签列表，不显示在预览区）
        btnAddLabel.setOnClickListener(v -> {
            String newTag = etNewLabel.getText().toString().trim();
            if (TextUtils.isEmpty(newTag)) {
                Toast.makeText(getContext(), "请输入标签", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!tagList.contains(newTag)) {
                tagList.add(newTag);
                adapter.notifyDataSetChanged();
                etNewLabel.setText("");
                saveTags();
            } else {
                Toast.makeText(getContext(), "标签已存在", Toast.LENGTH_SHORT).show();
            }
        });

        // 保存记录按钮：将预览区的内容保存到外部公共目录中
        btnSaveRecords.setOnClickListener(v -> saveRecordsToFile());

        updatePreview();

        return view;
    }

    // 从 SharedPreferences 加载标签列表
    private void loadTags() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> savedTags = prefs.getStringSet(KEY_TAGS, null);
        if (savedTags != null) {
            tagList.clear();
            tagList.addAll(savedTags);
        }
    }

    // 保存标签列表到 SharedPreferences
    private void saveTags() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> set = new HashSet<>(tagList);
        editor.putStringSet(KEY_TAGS, set);
        editor.apply();
    }

    // 更新预览区域，显示所有记录的事件
    private void updatePreview() {
        StringBuilder builder = new StringBuilder();
        for (String event : recordedEvents) {
            builder.append(event).append("\n");
        }
        tvPreview.setText(builder.toString());
    }

    // 保存记录到外部公共目录（如 Documents）
    private void saveRecordsToFile() {
        String fileName = "recorded_events_" + System.currentTimeMillis() + ".txt";
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)+"/Sample/TimeStamps/", fileName);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(tvPreview.getText().toString());
            writer.flush();
            Toast.makeText(getContext(), "保存成功：" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    // 自定义 Adapter，用于展示标签列表，每个项包含“记录”和“删除”按钮
    private class TagAdapter extends ArrayAdapter<String> {
        public TagAdapter(@NonNull Context context, ArrayList<String> tags) {
            super(context, 0, tags);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_label, parent, false);
            }
            String tag = getItem(position);
            TextView tvLabelName = convertView.findViewById(R.id.tv_label_name);
            Button btnRecordTimestamp = convertView.findViewById(R.id.btn_record_timestamp);
            Button btnDeleteLabel = convertView.findViewById(R.id.btn_delete_label);

            tvLabelName.setText(tag);

            // 点击“记录”按钮时，记录【标签 + 当前时间戳】到记录列表中
            btnRecordTimestamp.setOnClickListener(v -> {
                long currentTime = System.currentTimeMillis();
                String event = tag + ": " + currentTime;
                recordedEvents.add(event);
                updatePreview();
            });

            // 点击“删除”按钮时，删除该标签并更新持久化数据
            btnDeleteLabel.setOnClickListener(v -> {
                tagList.remove(tag);
                notifyDataSetChanged();
                saveTags();
            });

            return convertView;
        }
    }
}
