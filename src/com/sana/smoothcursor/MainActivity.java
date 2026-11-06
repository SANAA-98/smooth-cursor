package com.sana.smoothcursor;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;

/**
 * Small settings screen: permission status + start/stop + glide speed.
 * Everything is built in code so the project needs no resource files
 * besides the manifest and the accessibility config.
 */
public class MainActivity extends Activity {

    private TextView statusOverlay;
    private TextView statusA11y;
    private Button toggleBtn;
    private TextView speedLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    "android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(22));
        root.setBackgroundColor(Color.rgb(18, 18, 20));

        TextView title = text("Smooth Cursor", 24, Color.WHITE, true);
        root.addView(title);
        root.addView(text("نشانگری که با هر تپ، نرم می‌لغزه روی نقطه و همون‌جا کلیک می‌کنه.",
                14, Color.rgb(190, 190, 195), false));

        statusOverlay = text("", 15, Color.WHITE, false);
        statusA11y = text("", 15, Color.WHITE, false);
        root.addView(spacer(14));
        root.addView(statusOverlay);
        root.addView(statusA11y);

        Button overlayBtn = button("۱) مجوز نمایش روی اپ‌ها");
        overlayBtn.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(overlayBtn);

        Button a11yBtn = button("۲) فعال‌کردن سرویس Accessibility");
        a11yBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(a11yBtn);
        root.addView(text("نکته: اگه اندرویدت جدیده و سرویس خاکستری بود، برو به «برنامه‌ها ← Smooth Cursor ← ⋮ ← اجازهٔ تنظیمات محدودشده»، بعد برگرد این‌جا.",
                12.5f, Color.rgb(150, 150, 155), false));

        toggleBtn = button("");
        toggleBtn.setOnClickListener(v -> {
            if (OverlayService.isRunning()) {
                startService(new Intent(this, OverlayService.class)
                        .setAction(OverlayService.ACTION_STOP));
            } else {
                Intent i = new Intent(this, OverlayService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            }
            root.postDelayed(this::refreshStatus, 350);
        });
        root.addView(spacer(10));
        root.addView(toggleBtn);

        speedLabel = text("", 14, Color.WHITE, false);
        root.addView(spacer(16));
        root.addView(speedLabel);
        SeekBar sb = new SeekBar(this);
        SharedPreferences sp = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        int cur = sp.getInt(OverlayService.KEY_SPEED, 100);
        sb.setMax(170);
        sb.setProgress(cur - 30);
        updateSpeedLabel(cur);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int pct = progress + 30;
                sp.edit().putInt(OverlayService.KEY_SPEED, pct).apply();
                updateSpeedLabel(pct);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(sb);

        root.addView(spacer(16));
        root.addView(text("شکل نشانگر موس:", 14, Color.WHITE, false));
        styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(styleRow);
        populateStyleRow(sp);

        Button pickBtn = button("🖼 انتخاب / تغییر تصویر نشانگر سفارشی");
        pickBtn.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, REQ_PICK_CURSOR);
        });
        root.addView(pickBtn);
        deleteCustomBtn = button("🗑 حذف تصویر سفارشی");
        deleteCustomBtn.setOnClickListener(v -> {
            new java.io.File(getFilesDir(),
                    OverlayService.CUSTOM_FILE).delete();
            if (sp.getInt(OverlayService.KEY_STYLE, 0)
                    == OverlayService.STYLE_CUSTOM) {
                sp.edit().putInt(OverlayService.KEY_STYLE, 0)
                        .putFloat(OverlayService.KEY_HOTSPOT_X, 0.5f)
                        .putFloat(OverlayService.KEY_HOTSPOT_Y, 0.5f)
                        .apply();
            }
            populateStyleRow(sp);
            OverlayService.notifyStyleChanged();
        });
        root.addView(deleteCustomBtn);
        hotspotBtn = button("🎯 تعیین نقطهٔ کلیک روی تصویر");
        hotspotBtn.setOnClickListener(v -> openHotspotEditor(sp));
        root.addView(hotspotBtn);

        customSizeLabel = text("", 14, Color.WHITE, false);
        root.addView(spacer(10));
        root.addView(customSizeLabel);
        SeekBar csb = new SeekBar(this);
        int curCs = sp.getInt(OverlayService.KEY_CUSTOM_SIZE, 100);
        csb.setMax(160);
        csb.setProgress(curCs - 40);
        updateCustomSizeLabel(curCs);
        csb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress,
                                          boolean fromUser) {
                int pct = progress + 40;
                sp.edit().putInt(OverlayService.KEY_CUSTOM_SIZE, pct).apply();
                updateCustomSizeLabel(pct);
                OverlayService.notifyStyleChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(csb);

        root.addView(spacer(14));
        root.addView(text("وقتی روشنه: تپ کن ← نشانگر می‌لغزه و کلیک می‌کنه. اسکرول و لانگ‌پرس هم عادی کار می‌کنن (اسکرول کمی با مکث پخش می‌شه).",
                12.5f, Color.rgb(150, 150, 155), false));

        // The settings are taller than one screen — make the page scroll.
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void updateSpeedLabel(int pct) {
        speedLabel.setText("سرعت لغزش نشانگر: " + pct + "٪");
    }

    private static final int REQ_PICK_CURSOR = 42;
    private final java.util.List<View> styleCells = new java.util.ArrayList<>();
    private LinearLayout styleRow;
    private Button deleteCustomBtn;
    private Button hotspotBtn;
    private TextView customSizeLabel;

    private void updateCustomSizeLabel(int pct) {
        customSizeLabel.setText("اندازهٔ نشانگر سفارشی: " + pct + "٪");
    }

    private boolean customExists() {
        return new java.io.File(getFilesDir(),
                OverlayService.CUSTOM_FILE).exists();
    }

    /** (Re)build the row of cursor previews; tapping one selects it live. */
    private void populateStyleRow(SharedPreferences sp) {
        styleRow.removeAllViews();
        styleCells.clear();
        int previewPx = dp(40);
        for (int i = 0; i < OverlayService.STYLE_NAMES.length; i++) {
            final int style = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(2), dp(8), dp(2), dp(6));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cell.setLayoutParams(clp);

            if (style == OverlayService.STYLE_CUSTOM && !customExists()) {
                TextView plus = text("+", 26, Color.rgb(210, 210, 215), true);
                plus.setGravity(Gravity.CENTER);
                plus.setLayoutParams(new LinearLayout.LayoutParams(
                        previewPx, previewPx));
                cell.addView(plus);
            } else if (style == OverlayService.STYLE_CUSTOM) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(
                        new java.io.File(getFilesDir(),
                                OverlayService.CUSTOM_FILE).getAbsolutePath()));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setLayoutParams(new LinearLayout.LayoutParams(
                        previewPx, previewPx));
                cell.addView(iv);
            } else {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(
                        OverlayService.makeCursorBitmap(previewPx * 2, style));
                iv.setLayoutParams(new LinearLayout.LayoutParams(
                        previewPx, previewPx));
                cell.addView(iv);
            }
            TextView name = text(OverlayService.STYLE_NAMES[i], 10.5f,
                    Color.rgb(210, 210, 215), false);
            name.setGravity(Gravity.CENTER);
            cell.addView(name);

            cell.setOnClickListener(v -> {
                if (style == OverlayService.STYLE_CUSTOM && !customExists()) {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.setType("image/*");
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(pick, REQ_PICK_CURSOR);
                    return;
                }
                sp.edit().putInt(OverlayService.KEY_STYLE, style).apply();
                styleSelectionHighlight(sp);
                OverlayService.notifyStyleChanged();
            });
            styleCells.add(cell);
            styleRow.addView(cell);
        }
        styleSelectionHighlight(sp);
        if (deleteCustomBtn != null) {
            deleteCustomBtn.setVisibility(
                    customExists() ? View.VISIBLE : View.GONE);
        }
        if (hotspotBtn != null) {
            hotspotBtn.setVisibility(
                    customExists() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Hotspot editor: shows the custom image big; wherever she taps becomes
     * the click point (a ring marks it). Saved as fractions of the image.
     */
    private void openHotspotEditor(SharedPreferences sp) {
        java.io.File f = new java.io.File(getFilesDir(),
                OverlayService.CUSTOM_FILE);
        android.graphics.Bitmap bmp =
                android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
        if (bmp == null) return;

        android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setBackgroundColor(Color.rgb(24, 24, 28));
        box.addView(text("هر جای تصویر رو لمس کنی، همون‌جا نقطهٔ کلیک می‌شه:",
                14, Color.WHITE, false));

        int stagePx = Math.min(dp(300),
                getResources().getDisplayMetrics().widthPixels - dp(52));
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                stagePx, stagePx);
        slp.topMargin = dp(10);
        stage.setLayoutParams(slp);
        stage.setBackgroundColor(Color.rgb(58, 58, 63));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(bmp);
        stage.addView(iv, new android.widget.FrameLayout.LayoutParams(
                stagePx, stagePx));
        View marker = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x33000000);
        ring.setStroke(dp(2.4f), Color.rgb(255, 59, 48));
        marker.setBackground(ring);
        stage.addView(marker);
        box.addView(stage);

        float scale = Math.min((float) stagePx / bmp.getWidth(),
                (float) stagePx / bmp.getHeight());
        final float dw = bmp.getWidth() * scale;
        final float dh = bmp.getHeight() * scale;
        final float dl = (stagePx - dw) / 2f;
        final float dtp = (stagePx - dh) / 2f;
        final float[] frac = {
                sp.getFloat(OverlayService.KEY_HOTSPOT_X, 0.5f),
                sp.getFloat(OverlayService.KEY_HOTSPOT_Y, 0.5f)};
        Runnable placeMarker = () -> {
            int ms = dp(26);
            android.widget.FrameLayout.LayoutParams mlp =
                    new android.widget.FrameLayout.LayoutParams(ms, ms);
            mlp.leftMargin = Math.round(dl + frac[0] * dw - ms / 2f);
            mlp.topMargin = Math.round(dtp + frac[1] * dh - ms / 2f);
            marker.setLayoutParams(mlp);
        };
        placeMarker.run();
        stage.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN
                    || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                frac[0] = Math.max(0f, Math.min(1f, (e.getX() - dl) / dw));
                frac[1] = Math.max(0f, Math.min(1f, (e.getY() - dtp) / dh));
                placeMarker.run();
                return true;
            }
            return true;
        });

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button save = button("ذخیره");
        save.setOnClickListener(v -> {
            sp.edit()
                    .putFloat(OverlayService.KEY_HOTSPOT_X, frac[0])
                    .putFloat(OverlayService.KEY_HOTSPOT_Y, frac[1])
                    .apply();
            OverlayService.notifyStyleChanged();
            android.widget.Toast.makeText(this, "نقطهٔ کلیک ذخیره شد",
                    android.widget.Toast.LENGTH_SHORT).show();
            dlg.dismiss();
        });
        Button center = button("وسط تصویر");
        center.setOnClickListener(v -> {
            frac[0] = 0.5f;
            frac[1] = 0.5f;
            placeMarker.run();
        });
        Button cancel = button("لغو");
        cancel.setOnClickListener(v -> dlg.dismiss());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        blp.topMargin = dp(8);
        save.setLayoutParams(blp);
        center.setLayoutParams(blp);
        cancel.setLayoutParams(blp);
        btns.addView(save);
        btns.addView(center);
        btns.addView(cancel);
        box.addView(btns);

        dlg.setContentView(box);
        dlg.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_CURSOR || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        try {
            java.io.InputStream in =
                    getContentResolver().openInputStream(data.getData());
            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeStream(in);
            if (in != null) in.close();
            if (bmp == null) return;
            // Cap the stored copy at 512px; a cursor never needs more.
            int max = Math.max(bmp.getWidth(), bmp.getHeight());
            if (max > 512) {
                float f = 512f / max;
                bmp = android.graphics.Bitmap.createScaledBitmap(bmp,
                        Math.round(bmp.getWidth() * f),
                        Math.round(bmp.getHeight() * f), true);
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(),
                            OverlayService.CUSTOM_FILE));
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,
                    100, out);
            out.close();
            SharedPreferences sp = getSharedPreferences(
                    OverlayService.PREFS, MODE_PRIVATE);
            // A new image starts with its click point in the center.
            sp.edit().putInt(OverlayService.KEY_STYLE,
                            OverlayService.STYLE_CUSTOM)
                    .putFloat(OverlayService.KEY_HOTSPOT_X, 0.5f)
                    .putFloat(OverlayService.KEY_HOTSPOT_Y, 0.5f)
                    .apply();
            populateStyleRow(sp);
            OverlayService.notifyStyleChanged();
            android.widget.Toast.makeText(this,
                    "نشانگر سفارشی فعال شد — نقطهٔ کلیک، وسط تصویره",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }

    private void styleSelectionHighlight(SharedPreferences sp) {
        int sel = sp.getInt(OverlayService.KEY_STYLE, 0);
        for (int i = 0; i < styleCells.size(); i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            if (i == sel) {
                bg.setColor(Color.rgb(46, 40, 34));
                bg.setStroke(dp(1.6f), Color.rgb(255, 122, 26));
            } else {
                bg.setColor(Color.rgb(32, 32, 36));
            }
            styleCells.get(i).setBackground(bg);
        }
    }

    private void refreshStatus() {
        if (statusOverlay == null) return;
        boolean ov = Settings.canDrawOverlays(this);
        boolean ac = isA11yEnabled();
        statusOverlay.setText((ov ? "✅ " : "⬜ ") + "مجوز نمایش روی اپ‌ها: " + (ov ? "داده شده" : "نداده‌ای"));
        statusA11y.setText((ac ? "✅ " : "⬜ ") + "سرویس Accessibility: " + (ac ? "فعال" : "غیرفعال"));
        toggleBtn.setText(OverlayService.isRunning() ? "⏹ توقف نشانگر" : "▶️ شروع نشانگر");
    }

    private boolean isA11yEnabled() {
        if (CursorAccessibilityService.isEnabled()) return true;
        try {
            AccessibilityManager am = (AccessibilityManager)
                    getSystemService(ACCESSIBILITY_SERVICE);
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : list) {
                if (info.getId() != null && info.getId().contains(getPackageName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------ UI utils

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.START);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View spacer(int hDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(hDp)));
        return v;
    }
}
