package com.ik.simwheel.sim;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;

public class SimApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SimBridge.install(this);
        registerActivityLifecycleCallbacks(new Callbacks());
    }

    private static class Callbacks implements Application.ActivityLifecycleCallbacks {

        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            Window win = activity.getWindow();
            if (win != null) {
                Window.Callback original = win.getCallback();
                if (original != null) {
                    win.setCallback(new InputWrap(original));
                }
            }
        }

        public void onActivityStarted(Activity activity) {}
        public void onActivityResumed(Activity activity) {}
        public void onActivityPaused(Activity activity) {}
        public void onActivityStopped(Activity activity) {}
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
        public void onActivityDestroyed(Activity activity) {}
    }

    private static class InputWrap implements Window.Callback {
        private final Window.Callback original;

        InputWrap(Window.Callback original) {
            this.original = original;
        }

        public boolean dispatchKeyEvent(KeyEvent event) {
            SimBridge.onKey(event);
            return original.dispatchKeyEvent(event);
        }

        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            SimBridge.onMotion(event);
            return original.dispatchGenericMotionEvent(event);
        }

        // All other Window.Callback methods delegate to original

        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return original.dispatchKeyShortcutEvent(event);
        }

        public boolean dispatchTouchEvent(MotionEvent event) {
            return original.dispatchTouchEvent(event);
        }

        public boolean dispatchTrackballEvent(MotionEvent event) {
            return original.dispatchTrackballEvent(event);
        }

        public boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
            return original.dispatchPopulateAccessibilityEvent(event);
        }

        public android.view.View onCreatePanelView(int featureId) {
            return original.onCreatePanelView(featureId);
        }

        public boolean onCreatePanelMenu(int featureId, android.view.Menu menu) {
            return original.onCreatePanelMenu(featureId, menu);
        }

        public boolean onPreparePanel(int featureId, android.view.View view, android.view.Menu menu) {
            return original.onPreparePanel(featureId, view, menu);
        }

        public boolean onMenuOpened(int featureId, android.view.Menu menu) {
            return original.onMenuOpened(featureId, menu);
        }

        public boolean onMenuItemSelected(int featureId, android.view.MenuItem item) {
            return original.onMenuItemSelected(featureId, item);
        }

        public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams attrs) {
            original.onWindowAttributesChanged(attrs);
        }

        public void onContentChanged() {
            original.onContentChanged();
        }

        public void onWindowFocusChanged(boolean hasFocus) {
            original.onWindowFocusChanged(hasFocus);
        }

        public void onAttachedToWindow() {
            original.onAttachedToWindow();
        }

        public void onDetachedFromWindow() {
            original.onDetachedFromWindow();
        }

        public void onPanelClosed(int featureId, android.view.Menu menu) {
            original.onPanelClosed(featureId, menu);
        }

        public boolean onSearchRequested() {
            return original.onSearchRequested();
        }

        public boolean onSearchRequested(android.view.SearchEvent searchEvent) {
            return original.onSearchRequested(searchEvent);
        }

        public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback) {
            return original.onWindowStartingActionMode(callback);
        }

        public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback, int type) {
            return original.onWindowStartingActionMode(callback, type);
        }

        public void onActionModeStarted(android.view.ActionMode mode) {
            original.onActionModeStarted(mode);
        }

        public void onActionModeFinished(android.view.ActionMode mode) {
            original.onActionModeFinished(mode);
        }

        public void onProvideKeyboardShortcuts(java.util.List<android.view.KeyboardShortcutGroup> data, android.view.Menu menu, int deviceId) {
            original.onProvideKeyboardShortcuts(data, menu, deviceId);
        }

        public void onPointerCaptureChanged(boolean hasCapture) {
            original.onPointerCaptureChanged(hasCapture);
        }
    }
}
