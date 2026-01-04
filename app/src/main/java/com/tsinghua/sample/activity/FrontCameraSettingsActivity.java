package com.tsinghua.sample.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.tsinghua.sample.R;

public class FrontCameraSettingsActivity extends AppCompatActivity {
    private Switch switchInference;
    private Switch switchQualityEvaluation;
    private RadioGroup radioGroupFormat;
    private RadioButton radioMp4;
    private RadioButton radioAvi;
    private RadioButton radioNone;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_front_camera_settings);

        String name = getIntent().getStringExtra("deviceName");
        setTitle(name + " 设置");

        // 初始化视图
        switchInference = findViewById(R.id.switchInference);
        switchQualityEvaluation = findViewById(R.id.switchQualityEvaluation);
        radioGroupFormat = findViewById(R.id.radioGroupFormat);
        radioMp4 = findViewById(R.id.radioMp4);
        radioAvi = findViewById(R.id.radioAvi);
        radioNone = findViewById(R.id.radioNone);
        TextView info = findViewById(R.id.settingsInfo);
        info.setText("这里是前置录制设备的设置页");

        // 读取缓存
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enable_inference", false);
        boolean qualityEvaluationEnabled = prefs.getBoolean("enable_quality_evaluation", true);
        String format = prefs.getString("video_format", "mp4");

        // 应用到视图
        switchInference.setChecked(enabled);
        switchQualityEvaluation.setChecked(qualityEvaluationEnabled);
        radioGroupFormat.setVisibility(enabled ? RadioGroup.VISIBLE : RadioGroup.GONE);
        if ("avi".equals(format)) {
            radioAvi.setChecked(true);
        } else if ("mp4".equals(format)) {
            radioMp4.setChecked(true);
        }
        else{
            radioNone.setChecked(true);
        }

        // 监听切换推理开关
        switchInference.setOnCheckedChangeListener((buttonView, isChecked) -> {
            radioGroupFormat.setVisibility(isChecked ? RadioGroup.VISIBLE : RadioGroup.GONE);
            prefs.edit()
                    .putBoolean("enable_inference", isChecked)
                    .apply();
        });

        // 监听切换质量评估开关
        switchQualityEvaluation.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit()
                    .putBoolean("enable_quality_evaluation", isChecked)
                    .apply();
        });

        // 监听格式选择
        radioGroupFormat.setOnCheckedChangeListener((group, checkedId) -> {
            String selected;
            if(checkedId == R.id.radioAvi){
                selected = "avi";
            }
            else if(checkedId == R.id.radioMp4){
                selected = "mp4";
            }
            else{
                selected = "none";
            }
            prefs.edit()
                    .putString("video_format", selected)
                    .apply();
        });
    }
}
