package net.hestudio.miuitime;

import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Applies feature 2: while the launcher home screen is showing a page that contains an
 * official clock widget, the status bar clock is hidden.
 *
 * <p>Page detection comes from {@link LauncherLogMonitor} (the launcher cannot be hooked),
 * foreground state comes from SystemUI's ActivityManagerWrapper, and the hide/show action
 * reuses MIUI's own clock visibility plumbing ({@code HomeStatusBarViewBinderInjector}),
 * plus a {@code View.GONE} collapse of the clock view so its layout width is freed
 * (INVISIBLE alone leaves a blank gap in front of the status bar notification icons),
 * with {@code MiuiClock.setPolicyVisibility} and {@code View.setVisibility} hooks as
 * fallback and enforcement points.</p>
 */
public final class ClockPageController implements LauncherLogMonitor.Listener {

    private static final long POLL_INTERVAL_MS = 3000L;
    private static final long APPLY_DEBOUNCE_MS = 250L;
    /** Shows settle longer than hides: page transitions must not flash the clock. */
    private static final long APPLY_SHOW_DEBOUNCE_MS = 600L;
    private static final int VISIBLE = View.VISIBLE;
    private static final int INVISIBLE = View.INVISIBLE;
    private static final int GONE = View.GONE;

    private static volatile ClockPageController sInstance;

    private final ClassLoader classLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LauncherLogMonitor monitor = new LauncherLogMonitor();

    private volatile boolean clockPage;
    private volatile boolean overlayShowing;
    private boolean applied;
    private boolean appliedHidden;
    private volatile boolean showPending;
    private boolean pollScheduled;
    private boolean retryScheduled;
    private String lastTopActivity;
    private volatile String lastReason = "?";
    private WeakReference<Object> injectorRef = new WeakReference<>(null);

    private final Runnable applyRunnable = new Runnable() {
        @Override
        public void run() {
            showPending = false;
            boolean hide = clockPage && !overlayShowing && isLauncherForeground();
            apply(hide, lastReason);
        }
    };

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            retryScheduled = false;
            reevaluate("retry");
        }
    };

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollScheduled = false;
            reevaluate("poll");
        }
    };

    private ClockPageController(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        ClockPageController controller = new ClockPageController(lpparam.classLoader);
        sInstance = controller;
        controller.start();
    }

    static void setInjectorInstance(Object injector) {
        ClockPageController instance = sInstance;
        if (instance != null && injector != null) {
            instance.injectorRef = new WeakReference<>(injector);
        }
    }

    /** Used by the visibility hooks to keep the clock hidden while the page requires it. */
    static boolean shouldForceHide() {
        ClockPageController instance = sInstance;
        if (instance == null) {
            return false;
        }
        if (!instance.showPending && !(instance.clockPage && !instance.overlayShowing)) {
            return false;
        }
        // While a show is deferred the clock must stay collapsed: otherwise the
        // transition back to hidden flashes it for the length of the debounce.
        return instance.isLauncherForeground();
    }

    private void start() {
        monitor.start(this);
        hookTaskStackEvents();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                reevaluate("init");
            }
        }, 3000L);
        XLog.i("clock page controller started");
    }

    @Override
    public void onLauncherStateChanged(boolean clockPage, boolean overlayShowing) {
        if (this.clockPage == clockPage && this.overlayShowing == overlayShowing) {
            return;
        }
        this.clockPage = clockPage;
        this.overlayShowing = overlayShowing;
        XLog.i("launcher clockPage=" + clockPage + " overlay=" + overlayShowing);
        reevaluate("launcher");
    }

    private void reevaluate(final String reason) {
        lastReason = reason;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                schedulePollIfNeeded();
                // Debounce: activity/page transitions can emit several state changes in a row,
                // applying only the settled state avoids a clock flicker. Shows settle
                // longer than hides so a clock page briefly misdetected mid-swipe
                // (e.g. before the launcher re-emits its exposure line) cannot flash.
                boolean wantsHide = clockPage && !overlayShowing;
                showPending = !wantsHide && applied && appliedHidden;
                mainHandler.removeCallbacks(applyRunnable);
                mainHandler.postDelayed(applyRunnable,
                        wantsHide ? APPLY_DEBOUNCE_MS : APPLY_SHOW_DEBOUNCE_MS);
            }
        });
    }

    private void schedulePollIfNeeded() {
        if (clockPage && !pollScheduled) {
            pollScheduled = true;
            mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        }
    }

    private void apply(boolean hide, String reason) {
        if (applied && hide == appliedHidden) {
            return;
        }
        boolean success = hide ? hideClock() : showClock();
        if (!success) {
            // The clock view / injector may not exist yet right after SystemUI starts.
            if (!retryScheduled) {
                retryScheduled = true;
                XLog.i("apply hidden=" + hide + " deferred (" + reason + ")");
                mainHandler.postDelayed(retryRunnable, 1000L);
            }
            return;
        }
        applied = true;
        appliedHidden = hide;
        XLog.i("apply hidden=" + hide + " (" + reason + ")");
    }

    private boolean hideClock() {
        boolean injectorOk = false;
        Object injector = injectorRef.get();
        if (injector != null) {
            try {
                XposedHelpers.callMethod(injector, "hideClock", false);
                injectorOk = true;
            } catch (Throwable t) {
                XLog.w("hideClock failed, fallback to view", t);
            }
        }
        return applyViewVisibility(true) || injectorOk;
    }

    private boolean showClock() {
        boolean injectorOk = false;
        Object injector = injectorRef.get();
        if (injector != null) {
            try {
                XposedHelpers.callMethod(injector, "showClock", false);
                injectorOk = true;
            } catch (Throwable t) {
                XLog.w("showClock failed, fallback to view", t);
            }
        }
        return applyViewVisibility(false) || injectorOk;
    }

    /**
     * Applies the clock view state directly. Hide collapses the view to {@code GONE} so its
     * layout width is freed (a plain INVISIBLE keeps the space reserved and the status bar
     * notification icons end up floating with a blank gap in front of them).
     */
    private boolean applyViewVisibility(boolean hide) {
        TextView view = SystemUiClockHook.statusBarClock();
        if (view == null) {
            XLog.w("no status bar clock view captured");
            return false;
        }
        boolean ok = false;
        try {
            XposedHelpers.callMethod(view, "setPolicyVisibility", hide ? INVISIBLE : VISIBLE);
            ok = true;
        } catch (Throwable t) {
            XLog.w("setPolicyVisibility failed", t);
        }
        try {
            view.setVisibility(hide ? GONE : VISIBLE);
            ok = true;
        } catch (Throwable t) {
            XLog.w("setVisibility failed", t);
        }
        return ok;
    }

    /** Only the actual home screen activities count; launcher settings/recents must not. */
    private static final String[] HOME_ACTIVITIES = {
            "com.miui.home.launcher.Launcher",
            "com.miui.home.launcher.SecondaryDisplayLauncher",
            "com.miui.home.safemode.SafeLauncher",
    };

    private boolean isLauncherForeground() {
        ComponentName top = topActivityOf(getTopTask());
        if (top == null) {
            return false;
        }
        String current = top.flattenToShortString();
        if (!current.equals(lastTopActivity)) {
            lastTopActivity = current;
            XLog.i("top activity: " + current);
        }
        if (!"com.miui.home".equals(top.getPackageName())) {
            return false;
        }
        String className = top.getClassName();
        if (className == null) {
            return false;
        }
        for (String home : HOME_ACTIVITIES) {
            if (home.equals(className)) {
                return true;
            }
        }
        return false;
    }

    private Object getTopTask() {
        // 1) SystemUI's own wrapper singleton (OS4: no getInstance(), use sInstance field).
        try {
            Class<?> wrapperClass = XposedHelpers.findClassIfExists(
                    "com.android.systemui.shared.system.ActivityManagerWrapper", classLoader);
            if (wrapperClass != null) {
                Object wrapper = XposedHelpers.getStaticObjectField(wrapperClass, "sInstance");
                if (wrapper != null) {
                    Object task = callMethodFlexible(wrapper, "getRunningTask");
                    if (task != null) {
                        return task;
                    }
                }
            }
        } catch (Throwable t) {
            XLog.w("wrapper getRunningTask failed", t);
        }
        // 2) Framework ActivityTaskManager.
        try {
            Class<?> atm = XposedHelpers.findClassIfExists("android.app.ActivityTaskManager", classLoader);
            if (atm != null) {
                Object service = XposedHelpers.callStaticMethod(atm, "getService");
                if (service != null) {
                    Object tasks = XposedHelpers.callMethod(service, "getTasks", 1);
                    if (tasks instanceof java.util.List && !((java.util.List<?>) tasks).isEmpty()) {
                        return ((java.util.List<?>) tasks).get(0);
                    }
                    Object focused = XposedHelpers.callMethod(service, "getFocusedRootTaskInfo");
                    if (focused != null) {
                        return focused;
                    }
                }
            }
        } catch (Throwable t) {
            XLog.w("ActivityTaskManager lookup failed", t);
        }
        return null;
    }

    private Object callMethodFlexible(Object target, String name) {
        try {
            return XposedHelpers.callMethod(target, name);
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.callMethod(target, name, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ComponentName topActivityOf(Object task) {
        if (task == null) {
            return null;
        }
        try {
            Object top = XposedHelpers.getObjectField(task, "topActivity");
            if (top instanceof ComponentName) {
                return (ComponentName) top;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object info = XposedHelpers.getObjectField(task, "topActivityInfo");
            if (info != null) {
                String pkg = (String) XposedHelpers.getObjectField(info, "packageName");
                String name = (String) XposedHelpers.getObjectField(info, "name");
                if (pkg != null) {
                    return new ComponentName(pkg, name == null ? "" : name);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void hookTaskStackEvents() {
        Class<?> impl = XposedHelpers.findClassIfExists(
                "com.android.systemui.shared.system.TaskStackChangeListeners$Impl", classLoader);
        if (impl == null) {
            XLog.w("TaskStackChangeListeners$Impl not found, relying on polling");
            return;
        }
        int hooked = 0;
        for (final Method method : impl.getDeclaredMethods()) {
            String name = method.getName();
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!name.startsWith("onTask") && !"onActivityUnpinned".equals(name)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        ClockPageController instance = sInstance;
                        if (instance != null) {
                            instance.reevaluate("task:" + method.getName());
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                XLog.w("cannot hook " + name, t);
            }
        }
        XLog.i("task stack event hooks installed: " + hooked);
    }
}
