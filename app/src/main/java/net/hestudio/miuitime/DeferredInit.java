package net.hestudio.miuitime;

import android.os.SystemClock;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runs the non-critical parts of the module startup after boot instead of on SystemUI's
 * {@code handleBindApplication} path.
 *
 * <p>{@code handleLoadPackage} executes inside SystemUI's bind phase, which is on the keyguard
 * critical path at boot: SystemUI must connect its keyguard service before the screen turns on,
 * otherwise the launcher is briefly visible and interactive before the lock screen appears (see
 * {@code docs/boot-keyguard-exposure.md}). Everything that is not needed to observe early
 * {@code MiuiClock} construction — the feature 2 controller, the launcher log reader (regex
 * compilation plus a {@code logcat} child process), the task-stack and injector hooks — is
 * therefore deferred here until {@code sys.boot_completed=1}.</p>
 */
final class DeferredInit {

    /** Give up waiting for {@code sys.boot_completed} after this long and start anyway. */
    private static final long MAX_BOOT_WAIT_MS = 60_000L;
    private static final long BOOT_POLL_MS = 500L;

    private DeferredInit() {
    }

    static void start(final XC_LoadPackage.LoadPackageParam lpparam) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doInit(lpparam);
            }
        }, "MIUITimeInit");
        worker.setDaemon(true);
        worker.start();
    }

    private static void doInit(XC_LoadPackage.LoadPackageParam lpparam) {
        boolean bootCompleted = awaitBootCompleted();
        XLog.i("deferred init start (bootCompleted=" + bootCompleted + ")");
        try {
            SystemUiClockHook.hookInjector(lpparam);
            ClockPageController.init(lpparam);
        } catch (Throwable t) {
            XLog.e("deferred init failed", t);
        }
    }

    private static boolean awaitBootCompleted() {
        long deadline = SystemClock.uptimeMillis() + MAX_BOOT_WAIT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (DeviceState.bootCompleted()) {
                return true;
            }
            try {
                Thread.sleep(BOOT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return DeviceState.bootCompleted();
    }
}
