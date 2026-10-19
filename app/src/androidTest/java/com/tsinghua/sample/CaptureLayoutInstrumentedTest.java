package com.tsinghua.sample;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.tsinghua.sample.activity.CaptureLayoutController;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CaptureLayoutInstrumentedTest {
    @Test
    public void tabletLandscapeKeepsControlsAndDevicesAccessibleWithoutReplacingPreview() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.Theme_Sample);
            ConstraintLayout root = (ConstraintLayout) LayoutInflater.from(context)
                    .inflate(R.layout.activity_list, null);
            CaptureLayoutController.attach(root);
            View surface = root.findViewById(R.id.mainCameraSurfaceView);
            View camera = root.findViewById(R.id.cameraControlCard);
            View devices = root.findViewById(R.id.deviceRecyclerView);
            View toolbar = root.findViewById(R.id.bottomBar);
            View indicator = root.findViewById(R.id.cameraRecordingIndicator);
            View heartRate = root.findViewById(R.id.heartRateLayout);
            indicator.setVisibility(View.VISIBLE);
            heartRate.setVisibility(View.VISIBLE);

            layout(root, 1164, 720);
            assertTrue("Devices must be beside the camera", devices.getLeft() >= camera.getRight());
            assertTrue("Device list must use the window height", devices.getHeight() > root.getHeight() * .8);
            assertTrue("Camera must not overlap controls", camera.getBottom() <= toolbar.getTop());
            assertTrue("Controls must stay inside the window", toolbar.getBottom() <= root.getHeight());
            assertTrue(root.findViewById(R.id.btn_start_all).getWidth() > 0);
            assertEquals(View.VISIBLE, indicator.getVisibility());
            assertEquals(View.VISIBLE, heartRate.getVisibility());

            layout(root, 777, 1100);
            assertTrue("Portrait list must be below camera", devices.getTop() >= camera.getBottom());
            assertTrue("Portrait list must have usable height", devices.getHeight() > 0);
            assertTrue(devices.getBottom() <= toolbar.getTop());
            assertSame("Rotation must retain the preview view", surface,
                    root.findViewById(R.id.mainCameraSurfaceView));

            layout(root, 1164, 600);
            assertTrue("Compact landscape controls must remain accessible", camera.getBottom() <= toolbar.getTop());
            View preview = root.findViewById(R.id.cameraPreviewContainer);
            assertTrue(preview.getHeight() > 0);
            assertEquals(16f / 9f, (float) preview.getWidth() / preview.getHeight(), .02f);
            assertSame(surface, root.findViewById(R.id.mainCameraSurfaceView));
        });
    }

    private static void layout(ConstraintLayout root, int widthDp, int heightDp) {
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density);
        int height = Math.round(heightDp * density);
        // Constraints and preview aspect ratio settle over successive layout passes.
        for (int i = 0; i < 8; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
        }
    }
}
