package com.tsinghua.sample;

import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

public class ImuViewHolder extends RecyclerView.ViewHolder {
    public TextView deviceName;
    public Button startBtn;
    public ImageButton settingsBtn;
    public TextView accelData;  // 用于显示加速度数据
    public TextView gyroData;   // 用于显示陀螺仪数据
    public View infoLayout;     // 用于显示 IMU 数据的预览区域
    private boolean infoVisible = false;

    public ImuViewHolder(View itemView) {
        super(itemView);
        deviceName = itemView.findViewById(R.id.deviceName);
        startBtn = itemView.findViewById(R.id.startBtn);
        settingsBtn = itemView.findViewById(R.id.settingsBtn);
        accelData = itemView.findViewById(R.id.accelData);  // 加速度数据 TextView
        gyroData = itemView.findViewById(R.id.gyroData);    // 陀螺仪数据 TextView
        infoLayout = itemView.findViewById(R.id.infoLayout);  // IMU 数据的预览区域
    }

    // 切换展开与收起的 IMU 数据显示
    public void toggleInfo() {
        if (infoVisible) {
            infoLayout.animate()
                    .translationY(100)
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> infoLayout.setVisibility(View.GONE))
                    .start();
        } else {
            infoLayout.setAlpha(0f);
            infoLayout.setTranslationY(100);
            infoLayout.setVisibility(View.VISIBLE);
            infoLayout.animate()
                    .translationY(0)
                    .alpha(1f)
                    .setDuration(200)
                    .start();
        }
        infoVisible = !infoVisible;
    }

    // 更新加速度和陀螺仪数据
    public void updateIMUData(String accel, String gyro) {
        accelData.setText("加速度数据:\n" + accel);
        gyroData.setText("陀螺仪数据:\n" + gyro);
    }
}
