package com.tsinghua.sample.patient.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.tsinghua.sample.R;

public final class GroupedUi {
    public static final int BACKGROUND = 0xFFF4F5F7;
    public static final int CARD = 0xFFFFFFFF;
    public static final int TEXT_PRIMARY = 0xFF111827;
    public static final int TEXT_SECONDARY = 0xFF5F6673;
    public static final int TEXT_TERTIARY = 0xFF8A93A3;
    public static final int DIVIDER = 0xFFE6E8EE;
    public static final int WARNING = 0xFFD32F2F;
    public static final int ACCENT_BG = 0xFFEAF7F6;

    private GroupedUi() {
    }

    public static ScrollView scrollRoot(Context context, LinearLayout content) {
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(BACKGROUND);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 24));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scrollView;
    }

    public static TextView pageHeader(Context context, String title, String subtitle) {
        TextView view = text(context, title + (isBlank(subtitle) ? "" : "\n" + subtitle), 20, TEXT_PRIMARY, true);
        view.setPadding(0, dp(context, 4), 0, dp(context, 14));
        view.setLineSpacing(dp(context, 2), 1.0f);
        return view;
    }

    public static TextView sectionHeader(Context context, String title) {
        TextView view = text(context, title, 13, TEXT_SECONDARY, true);
        view.setAllCaps(false);
        view.setPadding(dp(context, 2), dp(context, 18), 0, dp(context, 8));
        return view;
    }

    public static MaterialCardView sectionCard(Context context) {
        MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(CARD);
        card.setRadius(dp(context, 10));
        card.setCardElevation(dp(context, 1));
        card.setStrokeWidth(0);
        return card;
    }

    public static LinearLayout cardContent(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        return content;
    }

    public static TextView title(Context context, String text) {
        return text(context, text, 17, TEXT_PRIMARY, true);
    }

    public static TextView body(Context context, String text) {
        TextView view = text(context, text, 14, TEXT_SECONDARY, false);
        view.setLineSpacing(dp(context, 2), 1.0f);
        return view;
    }

    public static TextView caption(Context context, String text) {
        return text(context, text, 12, TEXT_TERTIARY, false);
    }

    public static LinearLayout metaRow(Context context, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 6), 0, dp(context, 6));

        TextView key = text(context, label, 13, TEXT_TERTIARY, false);
        TextView val = text(context, value, 14, TEXT_PRIMARY, false);
        val.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        val.setMaxLines(2);

        row.addView(key, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f));
        row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f));
        return row;
    }

    public static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(DIVIDER);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(context, 1))
        ));
        return view;
    }

    public static TextView chip(Context context, String text, int textColor, int backgroundColor) {
        TextView view = text(context, text, 12, textColor, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 9), dp(context, 4), dp(context, 9), dp(context, 4));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(backgroundColor);
        drawable.setCornerRadius(dp(context, 999));
        view.setBackground(drawable);
        return view;
    }

    public static MaterialButton primaryButton(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setCornerRadius(dp(context, 14));
        button.setMinHeight(dp(context, 52));
        return button;
    }

    public static MaterialButton secondaryButton(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setCornerRadius(dp(context, 12));
        button.setStrokeColorResource(R.color.teal_700);
        button.setStrokeWidth(dp(context, 1));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00FFFFFF));
        button.setTextColor(0xFF00796B);
        return button;
    }

    public static MaterialButton textButton(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setCornerRadius(dp(context, 10));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00FFFFFF));
        button.setTextColor(0xFF00796B);
        return button;
    }

    public static LinearLayout.LayoutParams matchWrap(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(context, 10));
        return params;
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static TextView text(Context context, String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
