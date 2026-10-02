package net.hestudio.miuitime;

import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *
 * <p>Startup and hot-path rules (see {@code docs/boot-keyguard-exposure.md}): this controller is
 * created by {@link DeferredInit} after boot, never on SystemUI's bind path. The visibility hooks
 * only read the flag published through {@link SystemUiClockHook#setForceHide(boolean)} — a plain
 * volatile read — and this class keeps that flag updated from debounced apply runs and a
 * background probe thread. Nothing here may run binder calls or reflection inside a
 * {@code View.setVisibility} dispatch.</p>
 */
public final class ClockPageController implements LauncherLogMonitor.Listener {

    private static final long POLL_INTERVAL_MS = 3000L;
    /** While the post-boot keyguard gate is still closed, poll quickly to catch the first unlock. */
    private static final long GATE_POLL_INTERVAL_MS = 1000L;
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
    /** Single-threaded probe: foreground/keyguard/boot state, all binder/reflect work lives here. */
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "MIUITimeProbe");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final AtomicBoolean probeBusy = new AtomicBoolean();
    private volatile boolean probeRequested;

    /**
     * Hide decision state and — critically — the publication of that decision to the enforcement
     * hooks. The hooks only read the published flag, so the gate must publish <em>before</em>
     * every apply action runs (see {@link HideGate#beginApply}); publishing after the action made
     * the hooks veto the module's own show calls and left the clock {@code GONE} on non-clock
     * pages.
     */
    private final HideGate gate = new HideGate(new HideGate.Sink() {
        @Override
        public void publish(boolean forceHide) {
            SystemUiClockHook.setForceHide(forceHide);
        }
    });

    private boolean applied;
    private boolean appliedHidden;
    private boolean pollScheduled;
    private boolean retryScheduled;
    private String lastTopActivity;
    private volatile String lastReason = "?";
    private WeakReference<Object> injectorRef = new WeakReference<>(null);

    // Reflection caches for the probe thread (resolved once).
    private Object activityManagerWrapper;
    private Method wrapperGetRunningTask;
    private boolean wrapperResolved;
    private Method atmGetService;
    private Method atmGetTasks;
    private Method atmGetFocused;
    private boolean atmResolved;

    private final Runnable applyRunnable = new Runnable() {
        @Override
        public void run() {
            // beginApply publishes the settled decision to the enforcement hooks BEFORE the
            // hide/show calls run — the hooks veto any visibility call that contradicts the
            // published flag, so acting first would leave the clock stuck GONE.
            gate.beginApply(new HideGate.ApplyAction() {
                @Override
                public void run(boolean hide) {
                    apply(hide, lastReason);
                }
            });
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

    private void start() {
        // The inventory cache file is resolved lazily by LauncherLogMonitor (the app files dir
        // is not always available at this point); it bridges the mid-session SystemUI restart
        // gap where the launcher's one-shot cold-start log lines are already gone.
        monitor.start(this);
        hookTaskStackEvents();
        requestProbe();
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
        if (gate.clockPage() == clockPage && gate.overlayShowing() == overlayShowing) {
            return;
        }
        // Gate state only; publication happens on the debounced apply path (pendingShow) so the
        // clock stays collapsed for the whole settle window.
        gate.onLauncherState(clockPage, overlayShowing);
        XLog.i("launcher clockPage=" + clockPage + " overlay=" + overlayShowing);
        reevaluate("launcher");
    }

    private void reevaluate(final String reason) {
        lastReason = reason;
        requestProbe();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                schedulePollIfNeeded();
                // Debounce: activity/page transitions can emit several state changes in a row,
                // applying only the settled state avoids a clock flicker. Shows settle
                // longer than hides so a clock page briefly misdetected mid-swipe
                // (e.g. before the launcher re-emits its exposure line) cannot flash.
                boolean wantsHide = gate.clockPage() && !gate.overlayShowing();
                gate.pendingShow(!wantsHide && applied && appliedHidden);
                mainHandler.removeCallbacks(applyRunnable);
                mainHandler.postDelayed(applyRunnable,
                        wantsHide ? APPLY_DEBOUNCE_MS : APPLY_SHOW_DEBOUNCE_MS);
            }
        });
    }

    private void schedulePollIfNeeded() {
        if ((gate.clockPage() || !gate.keyguardGateOpen()) && !pollScheduled) {
            pollScheduled = true;
            mainHandler.postDelayed(pollRunnable,
                    gate.keyguardGateOpen() ? POLL_INTERVAL_MS : GATE_POLL_INTERVAL_MS);
        }
    }

    /**
     * Refreshes the gate's launcher-foreground / boot / keyguard state off the main
     * thread. Coalesced: at most one probe runs at a time, and a request arriving mid-probe
     * triggers exactly one follow-up.
     */
    private void requestProbe() {
        probeRequested = true;
        if (!probeBusy.compareAndSet(false, true)) {
            return;
        }
        probeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    while (probeRequested) {
                        probeRequested = false;
                        boolean boot = DeviceState.bootCompleted();
                        boolean unlocked = !DeviceState.keyguardLocked();
                        boolean foreground = isLauncherForeground();
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                applyProbe(boot, unlocked, foreground);
                            }
                        });
                    }
                } catch (Throwable t) {
                    XLog.w("probe failed", t);
                } finally {
                    probeBusy.set(false);
                    if (probeRequested) {
                        requestProbe();
                    }
                }
            }
        });
    }

    private void applyProbe(boolean boot, boolean unlocked, boolean foreground) {
        boolean changed = gate.onProbe(boot, unlocked, foreground);
        schedulePollIfNeeded();
        if (changed) {
            // Fresh state can flip the decision (e.g. first unlock while on a clock page):
            // re-run the debounced apply instead of waiting for the next event. Keep the
            // direction-specific settle times (shows settle longer than hides) even here.
            boolean wantsHide = gate.clockPage() && !gate.overlayShowing();
            mainHandler.removeCallbacks(applyRunnable);
            mainHandler.postDelayed(applyRunnable,
                    wantsHide ? APPLY_DEBOUNCE_MS : APPLY_SHOW_DEBOUNCE_MS);
        }
    }

    private void apply(boolean hide, String reason) {
        if (applied && hide == appliedHidden) {
            return;
        }
        if (!hide && !appliedHidden) {
            // Nothing of ours to restore: the clock was never hidden by this module, so leave
            // its visibility to SystemUI (e.g. its own "hide clock due to keyguard showing"
            // policy) instead of actively re-showing it.
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
     * Applies the clock view state directly on every captured {@code id=clock} instance and
     * verifies the result in the same frame. Hide collapses the views to {@code GONE} so their
     * layout width is freed (a plain INVISIBLE keeps the space reserved and the status bar
     * notification icons end up floating with a blank gap in front of them).
     *
     * <p>The verification turns silently vetoed calls into failures so {@link #apply} retries
     * instead of recording a restore that never happened (the enforcement hooks rewrite any
     * visibility call that contradicts the published flag — including the module's own, if the
     * flag was not published first).</p>
     */
    private boolean applyViewVisibility(boolean hide) {
        List<TextView> clocks = SystemUiClockHook.statusBarClocks();
        if (clocks.isEmpty()) {
            XLog.w("no status bar clock view captured");
            return false;
        }
        int expected = hide ? GONE : VISIBLE;
        boolean ok = false;
        for (TextView view : clocks) {
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
        }
        if (!ok) {
            return false;
        }
        for (TextView view : clocks) {
            if (view.getVisibility() != expected) {
                XLog.w("clock visibility not applied: want=" + expected
                        + " got=" + view.getVisibility());
                return false;
            }
        }
        return true;
    }

    /** Only the actual home screen activities count; launcher settings/recents must not. */
    private static final String[] HOME_ACTIVITIES = {
            "com.miui.home.launcher.Launcher",
            "com.miui.home.launcher.SecondaryDisplayLauncher",
            "com.miui.home.safemode.SafeLauncher",
    };

    /** Binder call: probe thread only. */
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

    /** Binder + reflection: probe thread only. Reflection lookups are resolved once and cached. */
    private Object getTopTask() {
        // 1) SystemUI's own wrapper singleton (OS4: no getInstance(), use sInstance field).
        try {
            if (!wrapperResolved) {
                wrapperResolved = true;
                Class<?> wrapperClass = XposedHelpers.findClassIfExists(
                        "com.android.systemui.shared.system.ActivityManagerWrapper", classLoader);
                if (wrapperClass != null) {
                    activityManagerWrapper =
                            XposedHelpers.getStaticObjectField(wrapperClass, "sInstance");
                    wrapperGetRunningTask = findMethodUpTo1Arg(wrapperClass, "getRunningTask");
                }
            }
            if (activityManagerWrapper != null && wrapperGetRunningTask != null) {
                Object task = wrapperGetRunningTask.getParameterCount() == 0
                        ? wrapperGetRunningTask.invoke(activityManagerWrapper)
                        : wrapperGetRunningTask.invoke(activityManagerWrapper, 0);
                if (task != null) {
                    return task;
                }
            }
        } catch (Throwable t) {
            XLog.w("wrapper getRunningTask failed", t);
        }
        // 2) Framework ActivityTaskManager.
        try {
            if (!atmResolved) {
                atmResolved = true;
                Class<?> atm = XposedHelpers.findClassIfExists("android.app.ActivityTaskManager", classLoader);
                if (atm != null) {
                    atmGetService = atm.getMethod("getService");
                }
            }
            if (atmGetService != null) {
                Object service = atmGetService.invoke(null);
                if (service != null) {
                    if (atmGetTasks == null) {
                        atmGetTasks = findMethodUpTo1Arg(service.getClass(), "getTasks");
                    }
                    if (atmGetTasks != null) {
                        Object tasks = atmGetTasks.getParameterCount() == 0
                                ? atmGetTasks.invoke(service)
                                : atmGetTasks.invoke(service, 1);
                        if (tasks instanceof List && !((List<?>) tasks).isEmpty()) {
                            return ((List<?>) tasks).get(0);
                        }
                    }
                    if (atmGetFocused == null) {
                        atmGetFocused = findMethodUpTo1Arg(service.getClass(), "getFocusedRootTaskInfo");
                    }
                    if (atmGetFocused != null) {
                        Object focused = atmGetFocused.invoke(service);
                        if (focused != null) {
                            return focused;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XLog.w("ActivityTaskManager lookup failed", t);
        }
        return null;
    }

    private static Method findMethodUpTo1Arg(Class<?> cls, String name) {
        for (Method method : cls.getDeclaredMethods()) {
            if (!name.equals(method.getName())
                    || Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() > 1) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        // Also walk the public surface (binder proxies expose the interface methods).
        for (Method method : cls.getMethods()) {
            if (name.equals(method.getName())
                    && !Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() <= 1) {
                return method;
            }
        }
        return null;
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
