package com.sana.smoothcursor;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a persistent mouse-style cursor over all apps.
 *
 * A full-screen touch layer captures the user's taps. A tap is NOT delivered
 * right away: the cursor first glides smoothly to the tap point, and only
 * when it arrives is a real tap injected there through the accessibility
 * service (while this layer is briefly made click-through, so the injected
 * tap reaches the app underneath instead of coming back to us).
 *
 * Drags / scrolls and two-finger gestures are recorded and replayed as
 * gestures, so scrolling keeps working.
 */
public class OverlayService extends Service {

    public static final String ACTION_STOP = "com.sana.smoothcursor.STOP";
    static final String PREFS = "sc_prefs";
    static final String KEY_SPEED = "speed_pct";
    static final String KEY_DELAY = "click_delay_ms";
    static final String KEY_STYLE = "cursor_style";
    static final String KEY_CUSTOM_SIZE = "custom_size_pct";
    static final String KEY_HOTSPOT_X = "custom_hotspot_x";
    static final String KEY_HOTSPOT_Y = "custom_hotspot_y";
    static final int STYLE_CUSTOM = 6;
    static final String CUSTOM_FILE = "custom_cursor.png";
    static final String[] STYLE_NAMES = {
            "فلش سفید", "فلش مشکی", "فلش نارنجی",
            "نشانه‌گیر", "حلقه", "نقطهٔ لیزری", "سفارشی"
    };

    private static OverlayService instance;

    public static boolean isRunning() {
        return instance != null && instance.overlayOn;
    }

    private WindowManager wm;
    private WindowManager.LayoutParams touchLp;
    private WindowManager.LayoutParams cursorLp;
    private View touchView;
    private ImageView cursorView;
    private boolean overlayOn = false;

    private int screenW, screenH;
    private float density;

    // Cursor position = the arrow tip ("hotspot") in screen coordinates.
    private float cursorX, cursorY;
    private float hotspotX, hotspotY;
    private int cursorSizePx;

    private final Handler main = new Handler(Looper.getMainLooper());

    // Tap queue ------------------------------------------------------------
    private static class TapReq {
        final float x, y;
        final boolean longPress;
        TapReq(float x, float y, boolean longPress) {
            this.x = x; this.y = y; this.longPress = longPress;
        }
    }
    private final List<TapReq> tapQueue = new ArrayList<>();
    private boolean processing = false;
    private ValueAnimator glideAnim;

    // Touch capture state ---------------------------------------------------
    private float downX, downY;
    private long downAt;
    private boolean moved, secondFinger;
    private boolean gestureActive = false;

    // Echo suppression ------------------------------------------------------
    // Our own injected gestures can bounce back into this touch layer if the
    // click-through flag change loses the race against the input dispatcher.
    // An echo always starts at the EXACT coordinates we injected and arrives
    // immediately, so we swallow any stream that matches that signature.
    private boolean echoStream = false;
    // Injection state: while a gesture is being injected, the touch layer is
    // REMOVED from the window manager entirely (not just flagged
    // not-touchable), so the injected events physically cannot land on it.
    private boolean touchAttached = false;
    private boolean injectionOpen = false;
    // Passthrough mode: toggled by the floating button. While on, the touch
    // layer is detached and the cursor stays frozen, so the screen (and the
    // keyboard) can be used completely normally. Toggling again restores
    // smooth-cursor mode. The button lives in its own small window, so it
    // stays tappable in both modes.
    private boolean passthrough = false;
    private TextView toggleBtn;
    private WindowManager.LayoutParams btnLp;
    private boolean btnAttached = false;

    private void togglePassthrough() {
        passthrough = !passthrough;
        if (passthrough) {
            tapQueue.clear();
            detachTouch();
            Toast.makeText(this, "صفحه آزاده — عادی استفاده کن، موس ثابته",
                    Toast.LENGTH_SHORT).show();
        } else {
            attachTouch();
            Toast.makeText(this, "موس فعال شد",
                    Toast.LENGTH_SHORT).show();
        }
        styleToggleBtn();
    }

    private void styleToggleBtn() {
        if (toggleBtn == null) return;
        // The icon shows what a tap will do: keyboard = free the screen,
        // mouse = bring the smooth cursor back.
        toggleBtn.setText(passthrough ? "\uD83D\uDDB1" : "⌨");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(passthrough ? 0xFF546E7A : 0xFFFF7A1A);
        bg.setStroke((int) (2 * density), 0xFFFFFFFF);
        toggleBtn.setBackground(bg);
    }

    private void showToggleBtn() {
        toggleBtn = new TextView(this);
        toggleBtn.setGravity(Gravity.CENTER);
        toggleBtn.setTextSize(15);
        int size = (int) (40 * density);
        btnLp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        btnLp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        btnLp.x = dm.widthPixels - size - (int) (12 * density);
        btnLp.y = (int) (dm.heightPixels * 0.55f);
        styleToggleBtn();
        final float[] bDown = new float[4]; // rawX, rawY, lpX, lpY
        final boolean[] bMoved = new boolean[1];
        final long[] bAt = new long[1];
        toggleBtn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    bDown[0] = e.getRawX();
                    bDown[1] = e.getRawY();
                    bDown[2] = btnLp.x;
                    bDown[3] = btnLp.y;
                    bMoved[0] = false;
                    bAt[0] = SystemClock.uptimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - bDown[0];
                    float dy = e.getRawY() - bDown[1];
                    if (Math.hypot(dx, dy) > 10 * density) bMoved[0] = true;
                    if (bMoved[0]) {
                        btnLp.x = Math.round(bDown[2] + dx);
                        btnLp.y = Math.round(bDown[3] + dy);
                        try {
                            wm.updateViewLayout(toggleBtn, btnLp);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!bMoved[0]
                            && SystemClock.uptimeMillis() - bAt[0] < 600) {
                        togglePassthrough();
                    }
                    return true;
                default:
                    return true;
            }
        });
        wm.addView(toggleBtn, btnLp);
        btnAttached = true;
    }

    private void hideToggleBtn() {
        if (toggleBtn != null && btnAttached) {
            try {
                wm.removeView(toggleBtn);
            } catch (Exception ignored) {
            }
        }
        btnAttached = false;
        toggleBtn = null;
    }
    private float echoX = -1, echoY = -1;
    private long echoUntil = 0;
    private final List<PointF> pathA = new ArrayList<>();
    private final List<PointF> pathB = new ArrayList<>();
    private long secondDownAt = -1;
    private long lastToastAt = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        density = getResources().getDisplayMetrics().density;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            hideOverlay();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "اول مجوز «نمایش روی اپ‌ها» رو بده",
                    Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        startFg();
        showOverlay();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private void startFg() {
        NotificationManager nm = (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    "smooth_cursor", "Smooth Cursor",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, OverlayService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "smooth_cursor")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Smooth Cursor")
                .setContentText("نشانگر نرم فعال است")
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "توقف", stopPi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(7, n);
        }
    }

    private void showOverlay() {
        if (overlayOn) return;
        measureScreen();

        // Full-screen touch catcher.
        touchView = new View(this);
        touchView.setBackgroundColor(Color.TRANSPARENT);
        touchView.setOnTouchListener((v, e) -> handleTouch(e));
        touchLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        touchLp.gravity = Gravity.TOP | Gravity.START;
        touchLp.x = 0;
        touchLp.y = 0;

        // The cursor itself: never touchable, always visible.
        cursorSizePx = Math.round(54 * density);
        cursorView = new ImageView(this);
        refreshCursorBitmap();
        cursorLp = new WindowManager.LayoutParams(
                effCursorSizePx, effCursorSizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        cursorLp.gravity = Gravity.TOP | Gravity.START;

        cursorX = screenW / 2f;
        cursorY = screenH / 2f;
        applyCursorPos();

        wm.addView(touchView, touchLp);
        touchAttached = true;
        wm.addView(cursorView, cursorLp);
        overlayOn = true;
        showToggleBtn();
    }

    private void hideOverlay() {
        if (!overlayOn) return;
        overlayOn = false;
        injectionOpen = false;
        passthrough = false;
        if (glideAnim != null) glideAnim.cancel();
        tapQueue.clear();
        processing = false;
        try {
            if (cursorView != null) wm.removeView(cursorView);
            if (touchView != null && touchAttached) wm.removeView(touchView);
        } catch (Exception ignored) {
        }
        touchAttached = false;
        cursorView = null;
        touchView = null;
        hideToggleBtn();
    }

    private void measureScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            screenW = b.width();
            screenH = b.height();
        } else {
            Point p = new Point();
            wm.getDefaultDisplay().getRealSize(p);
            screenW = p.x;
            screenH = p.y;
        }
    }

    private int effCursorSizePx;

    /** Re-read the chosen style and redraw the cursor (live, if shown). */
    private void refreshCursorBitmap() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int style = sp.getInt(KEY_STYLE, 0);
        Bitmap bmp = null;
        effCursorSizePx = cursorSizePx;
        if (style == STYLE_CUSTOM) {
            int pct = sp.getInt(KEY_CUSTOM_SIZE, 100);
            effCursorSizePx = Math.max(12,
                    Math.round(cursorSizePx * pct / 100f));
            bmp = loadCustomBitmap(effCursorSizePx);
            if (bmp == null) {
                style = 0;
                effCursorSizePx = cursorSizePx;
            }
        }
        if (bmp == null) {
            bmp = makeCursorBitmap(cursorSizePx, style);
            float[] hs = cursorHotspot(style, cursorSizePx);
            hotspotX = hs[0];
            hotspotY = hs[1];
        } else {
            // Custom image: the click point is the fraction the user picked
            // on the image, mapped through the same fit-center math that
            // loadCustomBitmap used to draw it into the square.
            android.graphics.BitmapFactory.Options o =
                    new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(
                    new java.io.File(getFilesDir(), CUSTOM_FILE)
                            .getAbsolutePath(), o);
            float sc = Math.min((float) effCursorSizePx / o.outWidth,
                    (float) effCursorSizePx / o.outHeight);
            float dw = o.outWidth * sc;
            float dh = o.outHeight * sc;
            float fx = sp.getFloat(KEY_HOTSPOT_X, 0.5f);
            float fy = sp.getFloat(KEY_HOTSPOT_Y, 0.5f);
            hotspotX = (effCursorSizePx - dw) / 2f + fx * dw;
            hotspotY = (effCursorSizePx - dh) / 2f + fy * dh;
        }
        if (cursorView != null) {
            cursorView.setImageBitmap(bmp);
        }
        if (cursorLp != null) {
            cursorLp.width = effCursorSizePx;
            cursorLp.height = effCursorSizePx;
        }
        applyCursorPos();
    }

    /** Load the user's own cursor image, fitted into a square of size px. */
    private Bitmap loadCustomBitmap(int size) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), CUSTOM_FILE);
            if (!f.exists()) return null;
            Bitmap src = android.graphics.BitmapFactory.decodeFile(
                    f.getAbsolutePath());
            if (src == null) return null;
            Bitmap out = Bitmap.createBitmap(size, size,
                    Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            float scale = Math.min((float) size / src.getWidth(),
                    (float) size / src.getHeight());
            float dw = src.getWidth() * scale;
            float dh = src.getHeight() * scale;
            Paint pnt = new Paint(Paint.ANTI_ALIAS_FLAG
                    | Paint.FILTER_BITMAP_FLAG);
            cv.drawBitmap(src, new android.graphics.Rect(0, 0,
                            src.getWidth(), src.getHeight()),
                    new android.graphics.RectF((size - dw) / 2f,
                            (size - dh) / 2f, (size + dw) / 2f,
                            (size + dh) / 2f),
                    pnt);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Called from settings when the user picks a different cursor style. */
    public static void notifyStyleChanged() {
        OverlayService s = instance;
        if (s != null) {
            s.main.post(s::refreshCursorBitmap);
        }
    }

    /** Hotspot (the exact point that clicks) in px, per style. */
    public static float[] cursorHotspot(int style, int size) {
        float s = size / 100f;
        if (style <= 2) {
            return new float[]{18 * s, 6 * s};   // arrow tip
        }
        return new float[]{50 * s, 50 * s};      // symmetric styles: center
    }

    /**
     * Cursor graphics, drawn in code on a 100x100 canvas.
     * Styles: 0 white arrow, 1 black arrow, 2 orange arrow,
     *         3 crosshair, 4 ring, 5 laser dot.
     */
    public static Bitmap makeCursorBitmap(int size, int style) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float s = size / 100f;

        if (style <= 2) {
            Path p = new Path();
            p.moveTo(18 * s, 6 * s);
            p.lineTo(18 * s, 78 * s);
            p.lineTo(36 * s, 62 * s);
            p.lineTo(47 * s, 88 * s);
            p.lineTo(59 * s, 84 * s);
            p.lineTo(47 * s, 58 * s);
            p.lineTo(68 * s, 58 * s);
            p.close();

            Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setColor(0x55000000);
            shadow.setStyle(Paint.Style.FILL);
            c.save();
            c.translate(2.5f * s, 3.5f * s);
            c.drawPath(p, shadow);
            c.restore();

            int fillColor = style == 0 ? Color.WHITE
                    : style == 1 ? 0xFF141414 : 0xFFFF7A1A;
            int strokeColor = style == 1 ? Color.WHITE : Color.BLACK;
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(fillColor);
            fill.setStyle(Paint.Style.FILL);
            c.drawPath(p, fill);

            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setColor(strokeColor);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(5 * s);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(p, stroke);
            return b;
        }

        float cx = 50 * s, cy = 50 * s;
        Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
        dark.setColor(Color.BLACK);
        Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
        light.setColor(Color.WHITE);

        if (style == 3) {
            // Crosshair: circle + four ticks + center dot.
            for (int pass = 0; pass < 2; pass++) {
                Paint pnt = pass == 0 ? dark : light;
                pnt.setStyle(Paint.Style.STROKE);
                pnt.setStrokeWidth((pass == 0 ? 8 : 3.6f) * s);
                pnt.setStrokeCap(Paint.Cap.ROUND);
                c.drawCircle(cx, cy, 24 * s, pnt);
                c.drawLine(cx, 8 * s, cx, 34 * s, pnt);
                c.drawLine(cx, 66 * s, cx, 92 * s, pnt);
                c.drawLine(8 * s, cy, 34 * s, cy, pnt);
                c.drawLine(66 * s, cy, 92 * s, cy, pnt);
            }
            dark.setStyle(Paint.Style.FILL);
            light.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 6 * s, dark);
            c.drawCircle(cx, cy, 3.4f * s, light);
        } else if (style == 4) {
            // Ring with a center dot.
            dark.setStyle(Paint.Style.STROKE);
            dark.setStrokeWidth(9 * s);
            light.setStyle(Paint.Style.STROKE);
            light.setStrokeWidth(5 * s);
            c.drawCircle(cx, cy, 27 * s, dark);
            c.drawCircle(cx, cy, 27 * s, light);
            dark.setStyle(Paint.Style.FILL);
            light.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 7 * s, dark);
            c.drawCircle(cx, cy, 4 * s, light);
        } else {
            // Laser dot: soft glow + solid red core + white rim.
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setColor(0x66FF3B30);
            glow.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 23 * s, glow);
            Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
            core.setColor(0xFFFF3B30);
            core.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 13 * s, core);
            light.setStyle(Paint.Style.STROKE);
            light.setStrokeWidth(3.2f * s);
            c.drawCircle(cx, cy, 13 * s, light);
            light.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 3 * s, light);
        }
        return b;
    }

    private void applyCursorPos() {
        if (cursorLp == null) return;
        cursorLp.x = Math.round(cursorX - hotspotX);
        cursorLp.y = Math.round(cursorY - hotspotY);
        if (overlayOn && cursorView != null) {
            try {
                wm.updateViewLayout(cursorView, cursorLp);
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------- touches

    private boolean handleTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getRawX();
                float y = e.getRawY();
                long now = SystemClock.uptimeMillis();
                // Swallow echoes of our own injected gestures: they start at
                // the exact injected coordinates, or arrive while we are in
                // the click-through window (when a real finger event should
                // not be able to reach us at all).
                if (injectionOpen
                        || (now < echoUntil
                            && Math.hypot(x - echoX, y - echoY) <= 3)) {
                    echoStream = true;
                    return true;
                }
                gestureActive = true;
                downX = x;
                downY = y;
                downAt = now;
                moved = false;
                secondFinger = false;
                secondDownAt = -1;
                pathA.clear();
                pathB.clear();
                pathA.add(new PointF(downX, downY));
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN:
                if (echoStream) return true;
                secondFinger = true;
                if (e.getActionIndex() == 1) {
                    secondDownAt = SystemClock.uptimeMillis();
                    pathB.clear();
                    pathB.add(new PointF(e.getRawX(1), e.getRawY(1)));
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (echoStream || !gestureActive) return true;
                float slop = 22 * density;
                for (int i = 0; i < e.getHistorySize(); i++) {
                    recordPoints(e, i);
                }
                recordPoints(e, -1);
                if (!moved && Math.hypot(e.getRawX() - downX,
                        e.getRawY() - downY) > slop) {
                    moved = true;
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (echoStream) {
                    echoStream = false;
                    return true;
                }
                // A stray UP with no DOWN we captured is not a user tap.
                if (!gestureActive) return true;
                gestureActive = false;
                long dt = SystemClock.uptimeMillis() - downAt;
                if (secondFinger) {
                    // Two-finger gesture: replay both paths.
                    glideCursorTo(pathA.isEmpty() ? downX
                            : pathA.get(pathA.size() - 1).x,
                            pathA.isEmpty() ? downY
                                    : pathA.get(pathA.size() - 1).y, true);
                    injectDrag(pathA, pathB,
                            secondDownAt < 0 ? 0 : secondDownAt - downAt, dt);
                } else if (!moved) {
                    if (dt >= 450) {
                        enqueueTap(downX, downY, true);
                    } else {
                        enqueueTap(downX, downY, false);
                    }
                } else {
                    // Single-finger drag / scroll: glide cursor to the end
                    // point quickly, then replay the recorded path.
                    PointF end = pathA.get(pathA.size() - 1);
                    glideCursorTo(end.x, end.y, true);
                    injectDrag(pathA, null, 0, dt);
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                echoStream = false;
                gestureActive = false;
                return true;
        }
        return true;
    }

    private void recordPoints(MotionEvent e, int historyPos) {
        try {
            // No getHistoricalRawX in the SDK: derive raw coords from the
            // historical coords plus this event's raw-minus-local offset.
            float dx = e.getRawX(0) - e.getX(0);
            float dy = e.getRawY(0) - e.getY(0);
            float x0 = historyPos < 0 ? e.getRawX(0)
                    : e.getHistoricalX(0, historyPos) + dx;
            float y0 = historyPos < 0 ? e.getRawY(0)
                    : e.getHistoricalY(0, historyPos) + dy;
            if (pathA.size() < 400) pathA.add(new PointF(x0, y0));
            if (secondFinger && e.getPointerCount() > 1) {
                float dx1 = e.getRawX(1) - e.getX(1);
                float dy1 = e.getRawY(1) - e.getY(1);
                float x1 = historyPos < 0 ? e.getRawX(1)
                        : e.getHistoricalX(1, historyPos) + dx1;
                float y1 = historyPos < 0 ? e.getRawY(1)
                        : e.getHistoricalY(1, historyPos) + dy1;
                if (pathB.size() < 400) pathB.add(new PointF(x1, y1));
            }
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------------- actions

    private void enqueueTap(float x, float y, boolean longPress) {
        if (tapQueue.size() > 6) tapQueue.remove(0);
        tapQueue.add(new TapReq(x, y, longPress));
        processQueue();
    }

    private void processQueue() {
        if (processing) return;
        if (tapQueue.isEmpty()) return;
        if (!CursorAccessibilityService.isEnabled()) {
            // Still glide to the tap point as feedback, then explain.
            TapReq first = tapQueue.remove(0);
            tapQueue.clear();
            glideCursorTo(first.x, first.y, false);
            toastThrottled("اول سرویس Accessibility رو فعال کن (از داخل اپ)");
            return;
        }
        final TapReq r = tapQueue.remove(0);
        processing = true;
        glideCursorTo(r.x, r.y, false);
        long dur = lastGlideDuration;
        final long stroke = r.longPress ? 600 : 80;
        // Adjustable pause between the cursor's arrival and the click, so
        // anyone watching (e.g. in a recorded tutorial) can clearly see
        // what is about to be tapped before it opens. The cursor rests
        // silently on the target during the pause; only at the very end
        // does it visibly "press" — and the real tap fires inside that
        // press (the gesture dispatches ~80 ms into the 170 ms pulse, at
        // its deepest point), so the visual tap and the opening line up.
        final int delayMs = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_DELAY, 500);
        main.postDelayed(() -> {
            main.postDelayed(() -> {
                pulseCursor();
                echoX = r.x;
                echoY = r.y;
                runInjection(
                        () -> CursorAccessibilityService.tap(r.x, r.y, stroke,
                                this::closeInjection),
                        stroke + 700,
                        () -> {
                            processing = false;
                            processQueue();
                        });
            }, delayMs);
        }, dur + 10);
    }

    private void injectDrag(List<PointF> a, List<PointF> b, long offsetB, long dt) {
        if (!CursorAccessibilityService.isEnabled()) {
            toastThrottled("اول سرویس Accessibility رو فعال کن (از داخل اپ)");
            return;
        }
        final List<PointF> fa = a;
        final List<PointF> fb = b;
        if (!fa.isEmpty()) {
            echoX = fa.get(0).x;
            echoY = fa.get(0).y;
        }
        long durMs = Math.min(Math.max(dt, 80), 5000);
        runInjection(
                () -> {
                    if (fb != null && fb.size() >= 2) {
                        CursorAccessibilityService.twoFinger(fa, fb, offsetB, dt,
                                this::closeInjection);
                    } else {
                        CursorAccessibilityService.drag(fa, dt,
                                this::closeInjection);
                    }
                },
                durMs + 700,
                null);
    }

    // ------------------------------------------------------------ injection

    private Runnable pendingAfterInjection;

    /**
     * Remove the touch layer, run the gesture dispatch, and re-attach the
     * layer when the gesture reports completion (or after a timeout).
     * Removing the window — instead of toggling FLAG_NOT_TOUCHABLE — makes
     * it impossible for the injected events to land back on this layer.
     */
    private void runInjection(Runnable dispatch, long timeoutMs, Runnable after) {
        detachTouch();
        injectionOpen = true;
        echoUntil = SystemClock.uptimeMillis() + timeoutMs + 600;
        pendingAfterInjection = after;
        main.postDelayed(() -> {
            if (!injectionOpen) return;
            dispatch.run();
            main.postDelayed(this::closeInjection, timeoutMs);
        }, 80);
    }

    /** Idempotent: called by the gesture callback and by the timeout. */
    private void closeInjection() {
        if (!injectionOpen) return;
        injectionOpen = false;
        attachTouch();
        echoUntil = SystemClock.uptimeMillis() + 400;
        Runnable after = pendingAfterInjection;
        pendingAfterInjection = null;
        if (after != null) after.run();
    }

    private void detachTouch() {
        if (touchView != null && touchAttached) {
            try {
                wm.removeView(touchView);
            } catch (Exception ignored) {
            }
            touchAttached = false;
        }
    }

    private void attachTouch() {
        if (touchView != null && !touchAttached && overlayOn && !passthrough) {
            try {
                wm.addView(touchView, touchLp);
                touchAttached = true;
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------ animation

    private long lastGlideDuration = 200;

    /**
     * Glide the cursor to (tx, ty) with a soft mouse-like ease-out.
     * If quick is true (drag replays), use a short fixed duration.
     */
    private void glideCursorTo(final float tx, final float ty, boolean quick) {
        if (glideAnim != null) glideAnim.cancel();
        final float sx = cursorX, sy = cursorY;
        double dist = Math.hypot(tx - sx, ty - sy);
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        float speedPct = sp.getInt(KEY_SPEED, 100) / 100f;
        long dur;
        if (quick) {
            dur = 130;
        } else {
            dur = Math.round((140 + dist * 0.33) / Math.max(0.3f, speedPct));
            dur = Math.max(110, Math.min(dur, 750));
        }
        lastGlideDuration = dur;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(dur);
        va.setInterpolator(new DecelerateInterpolator(1.7f));
        va.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            cursorX = sx + (tx - sx) * f;
            cursorY = sy + (ty - sy) * f;
            applyCursorPos();
        });
        glideAnim = va;
        va.start();
    }

    /** Little scale pop when the cursor "clicks". */
    private void pulseCursor() {
        if (cursorView == null) return;
        ValueAnimator va = ValueAnimator.ofFloat(1f, 0.82f, 1f);
        va.setDuration(170);
        va.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            cursorView.setScaleX(f);
            cursorView.setScaleY(f);
        });
        va.start();
    }

    private void toastThrottled(String msg) {
        long now = SystemClock.uptimeMillis();
        if (now - lastToastAt < 3000) return;
        lastToastAt = now;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
