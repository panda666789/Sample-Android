package com.tsinghua.sample.service;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.petterp.floatingx.FloatingX;
import com.petterp.floatingx.assist.FxScopeType;
import com.petterp.floatingx.assist.helper.FxAppHelper;
import com.petterp.floatingx.listener.IFxViewLifecycle;
import com.petterp.floatingx.view.FxViewHolder;
import com.tsinghua.sample.R;
import com.tsinghua.sample.media.CameraHelper;
import com.tsinghua.sample.media.IMURecorder;
import com.tsinghua.sample.media.MultiMicAudioRecorderHelper;

public class FloatingWindowService extends Service {


    private SurfaceView  surfaceViewBack;
    private CameraHelper cameraHelper;
    private Context context;
    private IMURecorder imuRecorder;
    private MultiMicAudioRecorderHelper multiMicAudioRecorderHelper;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        FxAppHelper helper = FxAppHelper.builder()
                .setContext(this)
                .setLayout(R.layout.floating_view_layout)
                .setScopeType(FxScopeType.SYSTEM_AUTO)
                .build();

        FloatingX.install(helper).show();
        new android.os.Handler().postDelayed(() -> {
            View floatingView = FloatingX.control().getView();
            if (floatingView == null) {
                Log.e("FloatingWindowService", "FloatingX view is null!");
                return;
            }
            FrameLayout recordingBorder = floatingView.findViewById(R.id.recordingBorder);
            recordingBorder.setBackgroundColor(Color.RED);
            surfaceViewBack = floatingView.findViewById(R.id.surfaceViewBack);
            cameraHelper = new CameraHelper(context, surfaceViewBack);
            imuRecorder = new IMURecorder(context);
            multiMicAudioRecorderHelper = new MultiMicAudioRecorderHelper();
            surfaceViewBack.setOnClickListener(view->{
                recordingBorder.setBackgroundColor(Color.GREEN); // 录制中显示绿色
                imuRecorder.startRecording();
                multiMicAudioRecorderHelper.startRecording();
                cameraHelper.startRecording();
                surfaceViewBack.setClickable(false);
                Intent intent = new Intent("com.tsinghua.UPDATE_TEXT_VIEW");
                intent.putExtra("newText", "结束录制");
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
            });
        }, 1000); // 延迟 1 秒

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cameraHelper.stopRecording(imuRecorder, multiMicAudioRecorderHelper, new CameraHelper.RecordingStopCallback() {
            @Override
            public void onRecordingStopped() {

            }
        });
        FloatingX.uninstallAll();

    }


}
