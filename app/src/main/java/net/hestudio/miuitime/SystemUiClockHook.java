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
import java.util.Calendar;
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
 * like {@code 下午3:48} (MIUI day-period marker 半夜/凌晨/上午/中午/下午/傍晚/晚上 + non padded
 * hour, no separator), instead of whatever HyperOS currently renders. Non Chinese locales
 * keep the plain AM/PM marker.</p>
 */
public final class SystemUiClockHook {

    private static final String CLOCK_CLASS = "com.android.systemui.statusbar.views.MiuiClock";
    private static final String STATUS_BAR_CLOCK_ID = "clock";
    private static final String FORMAT_12H = "aa h:mm";
    private static final String FORMAT_12H_ZH = "h:mm";

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
        hookViewVisibility();
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
                            if (ClockPageController.shouldForceHide()) {
                                view.setVisibility(View.GONE);
                            }
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
     *     clock widget page is active, so SystemUI cannot re-show it in between, and
     *     collapses it to {@code View.GONE} afterwards so its layout width is freed
     *     (INVISIBLE alone would leave a blank gap before the notification icons).</li>
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

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof View
                                    && isStatusBarClock((View) param.thisObject)
                                    && ClockPageController.shouldForceHide()) {
                                ((View) param.thisObject).setVisibility(View.GONE);
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

    /**
     * Enforces the collapsed ({@code View.GONE}) state of the status bar clock while a clock
     * widget page is active. {@code View.setVisibility} is the funnel every visibility change
     * goes through, so SystemUI cannot re-show the clock in between and no layout width is
     * reserved in front of the status bar notification icons.
     */
    private static void hookViewVisibility() {
        Method target;
        try {
            target = View.class.getDeclaredMethod("setVisibility", int.class);
        } catch (Throwable t) {
            XLog.w("View.setVisibility not found", t);
            return;
        }
        try {
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0) {
                            return;
                        }
                        TextView clock = statusBarClock();
                        if (clock == null || !isSameClockInstance(param.thisObject, clock)) {
                            return;
                        }
                        if (ClockPageController.shouldForceHide()) {
                            param.args[0] = View.GONE;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XLog.i("View.setVisibility enforcement installed");
        } catch (Throwable t) {
            XLog.w("cannot hook View.setVisibility", t);
        }
    }

    /** Identity or same view id: SystemUI creates more than one clock view with id {@code clock}. */
    private static boolean isSameClockInstance(Object self, TextView clock) {
        return self == clock
                || (self instanceof View && ((View) self).getId() == clock.getId());
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
        Date now = new Date();
        Locale locale = Locale.getDefault();
        try {
            return format12Hour(locale, now);
        } catch (Throwable t) {
            return format12Hour(Locale.US, now);
        }
    }

    private static String format12Hour(Locale locale, Date when) {
        if (isChinese(locale)) {
            Calendar calendar = Calendar.getInstance(locale);
            calendar.setTime(when);
            return dayPeriod(calendar.get(Calendar.HOUR_OF_DAY))
                    + new SimpleDateFormat(FORMAT_12H_ZH, locale).format(when);
        }
        return new SimpleDateFormat(FORMAT_12H, locale).format(when);
    }

    /**
     * MIUI style Chinese day periods (theme {@code aa}):
     * 00:00–00:59 半夜, 01:00–05:59 凌晨, 06:00–11:59 上午, 12:00–12:59 中午,
     * 13:00–17:59 下午, 18:00–18:59 傍晚, 19:00–23:59 晚上.
     */
    private static String dayPeriod(int hourOfDay) {
        if (hourOfDay == 0) {
            return "半夜";
        }
        if (hourOfDay < 6) {
            return "凌晨";
        }
        if (hourOfDay < 12) {
            return "上午";
        }
        if (hourOfDay == 12) {
            return "中午";
        }
        if (hourOfDay < 18) {
            return "下午";
        }
        if (hourOfDay == 18) {
            return "傍晚";
        }
        return "晚上";
    }

    private static boolean isChinese(Locale locale) {
        return "zh".equals(locale.getLanguage());
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
