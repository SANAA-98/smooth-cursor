package com.sana.smoothcursor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * Injects real taps / holds / drags at screen coordinates, on behalf of the
 * overlay. This is the piece that performs the "click" once the cursor has
 * glided to the target.
 */
public class CursorAccessibilityService extends AccessibilityService {

    private static CursorAccessibilityService instance;

    public static boolean isEnabled() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not interested in events; we only perform gestures.
    }

    @Override
    public void onInterrupt() {
    }

    /** Wrap a completion Runnable as a gesture result callback. */
    private static GestureDescriptionCallback callbackFor(Runnable done) {
        if (done == null) return null;
        return new GestureDescriptionCallback(done);
    }

    private static class GestureDescriptionCallback
            extends GestureResultCallback {
        private final Runnable done;
        GestureDescriptionCallback(Runnable done) {
            this.done = done;
        }
        @Override
        public void onCompleted(GestureDescription gestureDescription) {
            done.run();
        }
        @Override
        public void onCancelled(GestureDescription gestureDescription) {
            done.run();
        }
    }

    private static boolean dispatch(GestureDescription g, Runnable done) {
        if (instance == null) {
            if (done != null) done.run();
            return false;
        }
        boolean ok = instance.dispatchGesture(g, callbackFor(done),
                new Handler(Looper.getMainLooper()));
        if (!ok && done != null) done.run();
        return ok;
    }

    /** A single tap (short duration) or long-press (long duration) at x,y. */
    public static boolean tap(float x, float y, long durationMs, Runnable done) {
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(
                        p, 0, Math.max(10, durationMs)))
                .build();
        return dispatch(g, done);
    }

    /** Replay a recorded single-finger drag path over the given duration. */
    public static boolean drag(List<PointF> points, long durationMs, Runnable done) {
        if (points == null || points.size() < 2) {
            if (done != null) done.run();
            return false;
        }
        Path p = new Path();
        p.moveTo(points.get(0).x, points.get(0).y);
        for (int i = 1; i < points.size(); i++) {
            p.lineTo(points.get(i).x, points.get(i).y);
        }
        long dur = Math.max(50, Math.min(durationMs, 5000));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, dur))
                .build();
        return dispatch(g, done);
    }

    /**
     * Best-effort two-finger replay (pinch / two-finger scroll):
     * two strokes starting at their own offsets.
     */
    public static boolean twoFinger(List<PointF> a, List<PointF> b,
                                    long offsetB, long durationMs, Runnable done) {
        if (a == null || a.size() < 2) {
            if (done != null) done.run();
            return false;
        }
        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path pa = new Path();
        pa.moveTo(a.get(0).x, a.get(0).y);
        for (int i = 1; i < a.size(); i++) pa.lineTo(a.get(i).x, a.get(i).y);
        long dur = Math.max(50, Math.min(durationMs, 5000));
        builder.addStroke(new GestureDescription.StrokeDescription(pa, 0, dur));
        if (b != null && b.size() >= 2) {
            Path pb = new Path();
            pb.moveTo(b.get(0).x, b.get(0).y);
            for (int i = 1; i < b.size(); i++) pb.lineTo(b.get(i).x, b.get(i).y);
            long off = Math.max(0, Math.min(offsetB, dur - 10));
            builder.addStroke(new GestureDescription.StrokeDescription(
                    pb, off, Math.max(10, dur - off)));
        }
        return dispatch(builder.build(), done);
    }
}
