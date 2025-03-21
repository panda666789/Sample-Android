package com.tsinghua.sample;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.tsinghua.sample.utils.SharedViewModel;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class TimestampFragment extends BottomSheetDialogFragment {
    private SharedViewModel viewModel;
    private EditText etNewLabel;
    private Button btnAddLabel, btnSaveRecords;
    private ListView lvLabels;
    private TextView tvPreview;
    private ArrayList<String> tagList = new ArrayList<>();
    private ArrayList<String> recordedEvents = new ArrayList<>();
    private TagAdapter adapter;
    private static final String PREFS_EVENTS = "EventsPrefs";
    private static final String KEY_EVENTS = "saved_events";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        View view = inflater.inflate(R.layout.fragment_timestamp, container, false);

        etNewLabel = view.findViewById(R.id.et_new_label);
        btnAddLabel = view.findViewById(R.id.btn_add_label);
        lvLabels = view.findViewById(R.id.lv_labels);
        tvPreview = view.findViewById(R.id.tv_preview);
        btnSaveRecords = view.findViewById(R.id.btn_save_records);

        loadTags(); // 加载标签
        loadRecordedEvents(); // 加载时间戳记录

        adapter = new TagAdapter(getContext(), tagList);
        lvLabels.setAdapter(adapter);

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

        btnSaveRecords.setOnClickListener(v -> saveRecordsToFile()); // 保存记录到文件

        updatePreview(); // 初始化预览区域
        return view;
    }

    private void loadRecordedEvents() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_EVENTS, MODE_PRIVATE);
        Set<String> savedEvents = prefs.getStringSet(KEY_EVENTS, null);
        if (savedEvents != null) {
            recordedEvents.clear();
            recordedEvents.addAll(savedEvents);
        }
        updatePreview();
    }

    private void saveRecordedEvents() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_EVENTS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> set = new HashSet<>(recordedEvents);
        editor.putStringSet(KEY_EVENTS, set);
        editor.apply();
    }

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

            // 记录时间戳，并持久化到 SharedPreferences
            btnRecordTimestamp.setOnClickListener(v -> {
                long currentTime = System.currentTimeMillis();
                String event = tag + ": " + currentTime;
                recordedEvents.add(event);
                saveRecordedEvents(); // 立即保存到 SharedPreferences
                updatePreview();
            });

            // 删除标签
            btnDeleteLabel.setOnClickListener(v -> {
                tagList.remove(tag);
                notifyDataSetChanged();
                saveTags();
            });

            return convertView;
        }
    }

    private void updatePreview() {
        tvPreview.setText(TextUtils.join("\n", recordedEvents));
    }

    private void saveTags() {
        SharedPreferences prefs = getContext().getSharedPreferences("TagsPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> set = new HashSet<>(tagList);
        editor.putStringSet("saved_tags", set);
        editor.apply();
    }

    private void loadTags() {
        SharedPreferences prefs = getContext().getSharedPreferences("TagsPrefs", MODE_PRIVATE);
        Set<String> savedTags = prefs.getStringSet("saved_tags", null);
        if (savedTags != null) {
            tagList.clear();
            tagList.addAll(savedTags);
        }
    }

    private void saveRecordsToFile() {
        recordedEvents.clear();
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_EVENTS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.apply();
        SharedPreferences prefsSettings;
        prefsSettings = getContext().getSharedPreferences("AppSettings", MODE_PRIVATE);

        String savedExperimentId = prefsSettings.getString("experiment_id", "");
        String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) + "/Sample/"+savedExperimentId+"/TimeStamps/";
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            if (directory.mkdirs()) {
                Log.d("FileSave", "目录创建成功：" + directoryPath);
            } else {
                Log.e("FileSave", "目录创建失败：" + directoryPath);
                Toast.makeText(getContext(), "无法创建目录，保存失败", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        String fileName = "recorded_events_" + System.currentTimeMillis() + ".txt";
        File file = new File(directory, fileName);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(tvPreview.getText().toString());
            writer.flush();
            Toast.makeText(getContext(), "保存成功：" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "保存失败", Toast.LENGTH_SHORT).show();
        }
        updatePreview();


    }
}
