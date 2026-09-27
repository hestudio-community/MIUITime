package net.hestudio.miuitime;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Feature 1: restore the MIUI style 12-hour clock in the HyperOS status bar.
 *
 * <p>HyperOS 3 status bar clock is {@code com.android.systemui.statusbar.views.MiuiClock}
 * (confirmed against SystemUI 17.03.260226). In 12-hour mode we want the text to read
 * like {@code 下午 3:48} (AM/PM marker + space + non padded hour), instead of whatever
 * HyperOS currently renders.</p>
 */
public final class SystemUiClockHook {

    private static final String CLOCK_CLASS = "com.android.systemui.statusbar.views.MiuiClock";
    private static final String STATUS_BAR_CLOCK_ID = "clock";
    private static final String FORMAT_12H = "aa h:mm";

    /** Captured status bar clock view (id {@code clock}), used later by the page-hide feature. */
    private static volatile WeakReference<TextView> sStatusBarClock = new WeakReference<>(null);

    private SystemUiClockHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> clockClass = XposedHelpers.findClassIfExists(CLOCK_CLASS, lpparam.classLoader);
        if (clockClass == null) {
            XLog.w("MiuiClock class not found, feature 1 inactive");
            return;
        }
        hookConstructors(clockClass);
        hookUpdateTime(clockClass);
        hookVisibilityRecon(clockClass, lpparam);
        ClockPageController.init(lpparam);
        XLog.i("feature 1 hooks installed");
    }

    static TextView statusBarClock() {
        return sStatusBarClock.get();
    }

    private static void hookConstructors(Class<?> clockClass) {
        int hooked = 0;
        for (Constructor<?> ctor : clockClass.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!(param.thisObject instanceof TextView)) {
                                return;
                            }
                            TextView view = (TextView) param.thisObject;
                            XLog.i("MiuiClock created id=" + resourceEntryName(view));
                            if (!isStatusBarClock(view)) {
                                return;
                            }
                            sStatusBarClock = new WeakReference<>(view);
                            XLog.i("status bar clock view captured");
                        } catch (Throwable t) {
                            XLog.w("clock constructor hook failed", t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                XLog.w("cannot hook constructor " + ctor, t);
            }
        }
        XLog.i("MiuiClock constructors hooked: " + hooked);
    }

    private static void hookUpdateTime(Class<?> clockClass) {
        int hooked = 0;
        for (Method method : clockClass.getDeclaredMethods()) {
            if (!"updateTime".equals(method.getName())) {
                continue;
            }
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof TextView) {
                                applyMiuiStyle((TextView) param.thisObject);
                            }
                        } catch (Throwable t) {
                            XLog.w("updateTime hook failed", t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                XLog.w("cannot hook updateTime " + method, t);
            }
        }
        XLog.i("MiuiClock updateTime methods hooked: " + hooked);
    }

    /**
     * Hooks the clock visibility plumbing:
     * <ul>
     *     <li>captures the {@code HomeStatusBarViewBinderInjector} instance so the page
     *     controller can drive {@code hideClock/showClock} with it;</li>
     *     <li>forces {@code setPolicyVisibility(INVISIBLE)} on the status bar clock while a
     *     clock widget page is active, so SystemUI cannot re-show it in between.</li>
     * </ul>
     */
    private static void hookVisibilityRecon(Class<?> clockClass, XC_LoadPackage.LoadPackageParam lpparam) {
        for (Method method : clockClass.getDeclaredMethods()) {
            if (!"setPolicyVisibility".equals(method.getName())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length > 0
                                    && param.thisObject instanceof View
                                    && isStatusBarClock((View) param.thisObject)
                                    && ClockPageController.shouldForceHide()) {
                                param.args[0] = View.INVISIBLE;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable t) {
                XLog.w("cannot hook setPolicyVisibility", t);
            }
        }

        Class<?> injector = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderInjector",
                lpparam.classLoader);
        if (injector == null) {
            XLog.w("HomeStatusBarViewBinderInjector not found (fallback to clock view)");
            return;
        }
        for (Method method : injector.getDeclaredMethods()) {
            String name = method.getName();
            if (!"hideClock".equals(name) && !"showClock".equals(name)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ClockPageController.setInjectorInstance(param.thisObject);
                    }
                });
            } catch (Throwable t) {
                XLog.w("cannot hook Injector." + name, t);
            }
        }
    }

    private static void applyMiuiStyle(TextView view) {
        if (!isStatusBarClock(view)) {
            return;
        }
        Context context = view.getContext();
        if (context == null || DateFormat.is24HourFormat(context)) {
            return;
        }
        String text = format12Hour(context);
        CharSequence current = view.getText();
        if (current != null && text.contentEquals(current)) {
            return;
        }
        view.setText(text);
        view.setContentDescription(text);
    }

    private static String format12Hour(Context context) {
        Locale locale = Locale.getDefault();
        try {
            return new SimpleDateFormat(FORMAT_12H, locale).format(new Date());
        } catch (Throwable t) {
            return new SimpleDateFormat(FORMAT_12H, Locale.US).format(new Date());
        }
    }

    private static boolean isStatusBarClock(View view) {
        return STATUS_BAR_CLOCK_ID.equals(resourceEntryName(view));
    }

    private static String resourceEntryName(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID) {
                return null;
            }
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }
}
