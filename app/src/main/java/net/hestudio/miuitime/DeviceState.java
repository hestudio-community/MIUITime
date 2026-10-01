package net.hestudio.miuitime;

import android.app.KeyguardManager;
import android.content.Context;

import java.lang.reflect.Method;

/**
 * Cheap process-wide state probes used by the boot gate and the foreground/hide logic.
 *
 * <p>All probes are fail-safe: an unknown state must never lead to hiding the status bar clock
 * (fail toward showing it). Nothing here may be called from the {@code View.setVisibility} hook
 * — probes do reflection and (for the keyguard state) a binder call and belong on a background
 * thread or the debounced apply path.</p>
 */
final class DeviceState {

    private static volatile Method sSystemPropertiesGet;
    private static volatile Boolean sSystemPropertiesAvailable;

    private DeviceState() {
    }

    /** {@code sys.boot_completed == 1} once the boot animation has finished. */
    static boolean bootCompleted() {
        try {
            Method get = sSystemPropertiesGet;
            if (get == null) {
                Class<?> cls = Class.forName("android.os.SystemProperties");
                get = cls.getMethod("get", String.class);
                sSystemPropertiesGet = get;
                sSystemPropertiesAvailable = Boolean.TRUE;
            }
            Object value = get.invoke(null, "sys.boot_completed");
            return "1".equals(value);
        } catch (Throwable t) {
            if (!Boolean.FALSE.equals(sSystemPropertiesAvailable)) {
                sSystemPropertiesAvailable = Boolean.FALSE;
                XLog.w("sys.boot_completed probe failed", t);
            }
            return false;
        }
    }

    /**
     * {@code KeyguardManager.isKeyguardLocked()}. Returns {@code true} (locked) when the state is
     * unknown — e.g. before the application context exists — so callers stay on the safe side.
     */
    static boolean keyguardLocked() {
        try {
            Context context = currentApplication();
            if (context == null) {
                return true;
            }
            KeyguardManager manager =
                    (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            return manager == null || manager.isKeyguardLocked();
        } catch (Throwable t) {
            return true;
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getMethod("currentApplication");
            Object app = currentApplication.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
