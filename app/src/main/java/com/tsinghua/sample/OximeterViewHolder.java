package com.tsinghua.sample;

import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.sample.device.model.OximeterData;

public class OximeterViewHolder extends RecyclerView.ViewHolder {
    public TextView deviceName;
    public Button startBtn;
    public ImageButton settingsBtn;
    public View infoLayout;
    public TextView hrText, spo2Text;

    private boolean infoVisible = false;

    public OximeterViewHolder(View itemView) {
        super(itemView);
        deviceName = itemView.findViewById(R.id.deviceName);
        startBtn = itemView.findViewById(R.id.startBtn);
        settingsBtn = itemView.findViewById(R.id.settingsBtn);
        infoLayout = itemView.findViewById(R.id.infoLayout);
        hrText = itemView.findViewById(R.id.hrText);
        spo2Text = itemView.findViewById(R.id.spo2Text);
    }

    public void toggleInfo() {
        if (infoVisible) {
            infoLayout.animate()
                    .translationY(-100) // 向上滑出
                    .alpha(0)
                    .setDuration(200)
                    .withEndAction(() -> infoLayout.setVisibility(View.GONE))
                    .start();
        } else {
            infoLayout.setVisibility(View.VISIBLE);
            infoLayout.setAlpha(0);
            infoLayout.setTranslationY(-100); // 初始从上方位置
            infoLayout.animate()
                    .translationY(0)   // 向下滑入
                    .alpha(1)
                    .setDuration(200)
                    .start();
        }
        infoVisible = !infoVisible;
    }

    public void bindData(OximeterData data) {
        if (data.hr >= 0) hrText.setText("HR: " + data.hr + " bpm");
        if (data.spo2 >= 0) spo2Text.setText("SpO₂: " + data.spo2 + " %");
    }
}
