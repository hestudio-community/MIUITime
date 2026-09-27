package net.hestudio.miuitime;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/** Logging helper: writes to logcat and to the Xposed/LSPosed log. */
public final class XLog {

    public static final String TAG = "MIUITime";

    private XLog() {
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        bridgeLog(msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
        bridgeLog(msg);
    }

    public static void w(String msg, Throwable t) {
        Log.w(TAG, msg, t);
        bridgeLog(msg + ": " + Log.getStackTraceString(t));
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        bridgeLog(msg + ": " + Log.getStackTraceString(t));
    }

    private static void bridgeLog(String msg) {
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
            // XposedBridge is not available when running outside of a hooked process.
        }
    }
}
