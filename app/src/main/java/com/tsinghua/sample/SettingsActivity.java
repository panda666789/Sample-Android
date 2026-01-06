package com.tsinghua.sample;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.sample.utils.MacAdapter;
import com.tsinghua.sample.utils.MacItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {

    private EditText etTimeParam, etMacAddress, etMacDesc, etYAxisMin;
    private Button btnAddMac;
    private RecyclerView rvMacAddresses;
    private SharedPreferences prefs;
    private MacAdapter macAdapter;
    private List<MacItem> macList;
    private EditText etExperimentId; // 添加变量
    private EditText etRecordingDuration; // 录制时长
    private TextView tvDurationDisplay; // 时长显示


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        etExperimentId = findViewById(R.id.et_experiment_id);

        // UI 元素初始化
        etTimeParam = findViewById(R.id.et_time_param);
        etMacAddress = findViewById(R.id.et_mac_address);
        etMacDesc = findViewById(R.id.et_mac_desc);
        etYAxisMin = findViewById(R.id.et_y_axis_min);
        rvMacAddresses = findViewById(R.id.rv_mac_addresses);
        btnAddMac = findViewById(R.id.btn_add_mac);

        prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        int savedTime = prefs.getInt("time_parameter", 0);
        int savedYAxisMin = prefs.getInt("y_axis_min", 0);
        String savedExperimentId = prefs.getString("experiment_id", "");
        int savedRecordingDuration = prefs.getInt("recording_duration", 2400); // 默认40分钟

        etExperimentId.setText(savedExperimentId);

        // 初始化录制时长配置
        etRecordingDuration = findViewById(R.id.et_recording_duration);
        tvDurationDisplay = findViewById(R.id.tv_duration_display);
        etRecordingDuration.setText(String.valueOf(savedRecordingDuration));
        updateDurationDisplay(savedRecordingDuration);
        // 设置默认值
        if (savedTime != 0) {
            etTimeParam.setText(String.valueOf(savedTime));
        }
        if (savedYAxisMin != 0) {
            etYAxisMin.setText(String.valueOf(savedYAxisMin));
        }

        etExperimentId.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                prefs.edit().putString("experiment_id", s.toString().trim()).apply();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        etTimeParam.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int start, int before, int count) {
                try {
                    int timeParam = Integer.parseInt(charSequence.toString().trim());
                    prefs.edit().putInt("time_parameter", timeParam).apply();
                } catch (NumberFormatException e) {
                    // 若输入无效，忽略
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {}
        });

        etYAxisMin.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int start, int before, int count) {
                try {
                    int yAxisMin = Integer.parseInt(charSequence.toString().trim());
                    prefs.edit().putInt("y_axis_min", yAxisMin).apply();
                } catch (NumberFormatException e) {
                    // 若输入无效，忽略
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {}
        });

        // 录制时长配置监听
        etRecordingDuration.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                try {
                    int duration = Integer.parseInt(s.toString().trim());
                    // 限制范围 1-7200 秒
                    if (duration < 1) duration = 1;
                    if (duration > 7200) duration = 7200;
                    prefs.edit().putInt("recording_duration", duration).apply();
                    updateDurationDisplay(duration);
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 加载 MAC 地址列表
        macList = new ArrayList<>();
        Set<String> savedMacs = prefs.getStringSet("mac_list", new HashSet<>());
        for (String macData : savedMacs) {
            String[] parts = macData.split(";", 2);
            macList.add(new MacItem(parts[0], parts.length > 1 ? parts[1] : "无描述"));
        }

        // 设置 RecyclerView 和适配器
        macAdapter = new MacAdapter(this, macList, new MacAdapter.OnMacDeleteListener() {
            @Override
            public void onMacDeleted(MacItem macItem) {
                macList.remove(macItem);
                macAdapter.notifyDataSetChanged();
                saveMacList();
            }
        });

        rvMacAddresses.setLayoutManager(new LinearLayoutManager(this));
        rvMacAddresses.setAdapter(macAdapter);

        // 添加 MAC 地址
        btnAddMac.setOnClickListener(v -> {
            String macAddress = etMacAddress.getText().toString().trim();
            String macDesc = etMacDesc.getText().toString().trim();

            if (TextUtils.isEmpty(macAddress)) {
                Toast.makeText(this, "请输入MAC地址", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!macAddress.matches("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")) {
                Toast.makeText(this, "MAC地址格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }

            // 新增 MAC 地址
            macList.add(new MacItem(macAddress, macDesc));
            macAdapter.notifyDataSetChanged();
            saveMacList();
        });
        Button logoutButton = findViewById(R.id.btnLogout);
        logoutButton.setOnClickListener(v -> {
            SharedPreferences preferences = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            SharedPreferences.Editor editor = preferences.edit();
            editor.clear();
            editor.apply();

            // 跳转到 LoginRegisterActivity
            Intent intent = new Intent(SettingsActivity.this, LoginRegisterActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
    // 保存 MAC 地址列表
    private void saveMacList() {
        Set<String> macSet = new HashSet<>();
        for (MacItem item : macList) {
            macSet.add(item.getMacAddress() + ";" + item.getDescription());
        }
        prefs.edit().putStringSet("mac_list", macSet).apply();
    }

    // 保存选中的 MAC 地址
    private void saveSelectedMacAddress(String macAddress) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("mac_address", macAddress);
        editor.apply();
    }

    // 加载选中的 MAC 地址
    private String loadSelectedMacAddress() {
        return prefs.getString("mac_address", "");
    }

    // 更新录制时长显示
    private void updateDurationDisplay(int seconds) {
        if (tvDurationDisplay == null) return;
        if (seconds < 60) {
            tvDurationDisplay.setText(seconds + "秒");
        } else if (seconds < 3600) {
            int min = seconds / 60;
            int sec = seconds % 60;
            if (sec == 0) {
                tvDurationDisplay.setText(min + "分钟");
            } else {
                tvDurationDisplay.setText(min + "分" + sec + "秒");
            }
        } else {
            int hour = seconds / 3600;
            int min = (seconds % 3600) / 60;
            if (min == 0) {
                tvDurationDisplay.setText(hour + "小时");
            } else {
                tvDurationDisplay.setText(hour + "小时" + min + "分");
            }
        }
    }
}
