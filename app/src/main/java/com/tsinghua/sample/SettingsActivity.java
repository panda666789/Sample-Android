package com.tsinghua.sample;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private EditText etTimeParam;
    private Button btnSaveTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etTimeParam = findViewById(R.id.et_time_param);
        btnSaveTime = findViewById(R.id.btn_save_time);

        // 加载已保存的时间参数
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        int savedTime = prefs.getInt("time_parameter", 0);
        if (savedTime != 0) {
            etTimeParam.setText(String.valueOf(savedTime));
        }

        btnSaveTime.setOnClickListener(v -> {
            String timeStr = etTimeParam.getText().toString().trim();
            if (TextUtils.isEmpty(timeStr)) {
                Toast.makeText(SettingsActivity.this, "请输入时间参数", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                int timeParam = Integer.parseInt(timeStr);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putInt("time_parameter", timeParam);
                editor.apply();
                Toast.makeText(SettingsActivity.this, "时间参数保存成功", Toast.LENGTH_SHORT).show();
                finish();
            } catch (NumberFormatException e) {
                Toast.makeText(SettingsActivity.this, "请输入合法数字", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
