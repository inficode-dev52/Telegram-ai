package com.aditya.ai;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Typeface;
import android.graphics.Color;
import android.view.Gravity;
import android.util.TypedValue;

public class CrashReportActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String report;
        try { report = new String(openFileInput("last_crash.txt").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { report = "Crash report could not be read: " + e; }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 28, 24, 24);
        root.setBackgroundColor(Color.rgb(12, 18, 32));
        TextView title = new TextView(this);
        title.setText("ADITYA AI — Crash Report"); title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21); title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        TextView note = new TextView(this);
        note.setText("Please copy this error and send it to the developer. It contains technical crash details.");
        note.setTextColor(Color.LTGRAY); note.setTextSize(14); note.setPadding(0, 12, 0, 14);
        root.addView(note);
        Button copy = new Button(this); copy.setText("COPY CRASH REPORT");
        final String finalReport = report;
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Aditya crash report", finalReport));
            copy.setText("COPIED — PASTE IT IN CHATGPT");
        });
        root.addView(copy, new LinearLayout.LayoutParams(-1, -2));
        TextView body = new TextView(this); body.setText(report); body.setTextColor(Color.rgb(220,225,235));
        body.setTextSize(11); body.setTypeface(Typeface.MONOSPACE); body.setPadding(0, 16, 0, 16);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
}
