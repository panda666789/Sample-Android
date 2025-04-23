package com.tsinghua.sample;

import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

public class FrontCameraViewHolder extends RecyclerView.ViewHolder {
    public TextView deviceName;
    public Button startBtn;
    public ImageButton settingsBtn;
    public SurfaceView surfaceView;
    public View infoLayout;
    private boolean infoVisible = false;

    public FrontCameraViewHolder(View itemView) {
        super(itemView);
        deviceName = itemView.findViewById(R.id.deviceName);
        startBtn = itemView.findViewById(R.id.startBtn);
        settingsBtn = itemView.findViewById(R.id.settingsBtn);
        surfaceView = itemView.findViewById(R.id.surfaceView);
        infoLayout = itemView.findViewById(R.id.infoLayout);
    }

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
}
