package com.tsinghua.sample.activity;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.tsinghua.sample.R;

/**
 * Fits the existing capture views to the current window without replacing camera surfaces.
 * Wide landscape windows show camera/controls beside an independently scrolling device list.
 */
public final class CaptureLayoutController {
    private final ConstraintLayout root;
    private final ConstraintLayout controls;
    private final View cameraCard;
    private final View cameraContent;
    private final View preview;
    private final View bottomBar;
    private final View deviceList;
    private final ConstraintSet portrait = new ConstraintSet();
    private final ConstraintSet landscape = new ConstraintSet();
    private final ConstraintSet portraitControls = new ConstraintSet();
    private final ConstraintSet landscapeControls = new ConstraintSet();
    private final LinearLayout.LayoutParams portraitPreview;
    private Boolean wideLandscape;

    /** Call once after inflating activity_list, including before permissions are granted. */
    public static void attach(ConstraintLayout root) {
        new CaptureLayoutController(root);
    }

    private CaptureLayoutController(ConstraintLayout root) {
        this.root = root;
        controls = root.findViewById(R.id.captureControls);
        cameraCard = root.findViewById(R.id.cameraControlCard);
        cameraContent = root.findViewById(R.id.cameraContentLayout);
        preview = root.findViewById(R.id.cameraPreviewContainer);
        bottomBar = root.findViewById(R.id.bottomBar);
        deviceList = root.findViewById(R.id.deviceRecyclerView);
        portraitPreview = new LinearLayout.LayoutParams(
                (LinearLayout.LayoutParams) preview.getLayoutParams());

        portrait.clone(root);
        preserveVisibility(portrait, root);
        landscape.clone(portrait);
        landscape.connect(R.id.cameraControlCard, ConstraintSet.END,
                R.id.captureColumnGuide, ConstraintSet.START, dp(8));
        landscape.connect(R.id.bottomBar, ConstraintSet.END,
                R.id.captureColumnGuide, ConstraintSet.START, dp(8));
        landscape.connect(R.id.deviceRecyclerView, ConstraintSet.TOP,
                ConstraintSet.PARENT_ID, ConstraintSet.TOP, dp(14));
        landscape.connect(R.id.deviceRecyclerView, ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0);
        landscape.connect(R.id.deviceRecyclerView, ConstraintSet.START,
                R.id.captureColumnGuide, ConstraintSet.START, 0);

        portraitControls.clone(controls);
        preserveVisibility(portraitControls, controls);
        landscapeControls.clone(portraitControls);
        addControlRow(new int[]{R.id.btn_one_click_connect, R.id.btn_start_all},
                new float[]{1f, 1.4f}, R.id.statusText, 56, false);
        addControlRow(new int[]{R.id.btn_settings, R.id.btn_patient_records, R.id.btn_templates},
                new float[]{1f, 1f, 1f}, R.id.btn_start_all, 48, true);

        // Visibility changes (camera collapse, heart-rate graph) also change the space available
        // for preview. Only apply differing dimensions, so this listener converges after layout.
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::updateLayout);
    }

    private void addControlRow(int[] ids, float[] weights, int above, int heightDp,
                               boolean lastRow) {
        for (int id : ids) {
            landscapeControls.clear(id, ConstraintSet.TOP);
            landscapeControls.clear(id, ConstraintSet.BOTTOM);
            landscapeControls.constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT);
            landscapeControls.constrainHeight(id, dp(heightDp));
            landscapeControls.connect(id, ConstraintSet.TOP, above, ConstraintSet.BOTTOM, dp(8));
            if (lastRow) {
                landscapeControls.connect(id, ConstraintSet.BOTTOM,
                        ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0);
            }
        }
        landscapeControls.createHorizontalChainRtl(
                ConstraintSet.PARENT_ID, ConstraintSet.START,
                ConstraintSet.PARENT_ID, ConstraintSet.END,
                ids, weights, ConstraintSet.CHAIN_SPREAD);
        for (int i = 0; i < ids.length; i++) {
            landscapeControls.setMargin(ids[i], ConstraintSet.START, i == 0 ? 0 : dp(4));
            landscapeControls.setMargin(ids[i], ConstraintSet.END,
                    i == ids.length - 1 ? 0 : dp(4));
        }
    }

    private void updateLayout() {
        int width = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int height = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }
        boolean useColumns = width >= dp(600) && width > height;
        if (wideLandscape == null || wideLandscape != useColumns) {
            wideLandscape = useColumns;
            (useColumns ? landscape : portrait).applyTo(root);
            (useColumns ? landscapeControls : portraitControls).applyTo(controls);
            int controlPadding = dp(useColumns ? 12 : 16);
            controls.setPadding(controlPadding, controlPadding, controlPadding, controlPadding);
            deviceList.setPaddingRelative(dp(useColumns ? 8 : 16), dp(useColumns ? 0 : 8),
                    dp(16), dp(16));
            // The new constraints must be measured before using card and toolbar bounds.
            return;
        }
        if (cameraContent.getVisibility() != View.VISIBLE) {
            return;
        }

        int availableWidth = cameraContent.getWidth()
                - cameraContent.getPaddingLeft() - cameraContent.getPaddingRight();
        int cameraChromeHeight = cameraCard.getHeight() - preview.getHeight();
        int availableHeight = bottomBar.getTop() - cameraCard.getTop() - cameraChromeHeight;
        if (availableWidth <= 0) {
            return;
        }
        int previewHeight;
        int previewWidth;
        int previewGravity;
        if (useColumns) {
            previewHeight = Math.max(1,
                    Math.min(Math.round(availableWidth * 9f / 16f), availableHeight - dp(12)));
            previewWidth = Math.min(availableWidth, Math.round(previewHeight * 16f / 9f));
            previewGravity = Gravity.CENTER_HORIZONTAL;
        } else {
            // Reserve the device list before enlarging a tablet's portrait viewfinder.
            // The budget excludes the preview itself, so successive layouts keep the same result.
            int maxPreviewHeight = width >= dp(600) ? dp(360) : portraitPreview.height;
            previewHeight = Math.max(1, Math.min(maxPreviewHeight, availableHeight - dp(160)));
            previewWidth = portraitPreview.width;
            previewGravity = portraitPreview.gravity;
        }
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) preview.getLayoutParams();
        if (params.width != previewWidth || params.height != previewHeight
                || params.gravity != previewGravity) {
            params.width = previewWidth;
            params.height = previewHeight;
            params.gravity = previewGravity;
            preview.setLayoutParams(params);
        }
    }

    private static void preserveVisibility(ConstraintSet constraints, ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            constraints.setVisibilityMode(parent.getChildAt(i).getId(),
                    ConstraintSet.VISIBILITY_MODE_IGNORE);
        }
    }

    private int dp(int value) {
        return Math.round(value * root.getResources().getDisplayMetrics().density);
    }
}
