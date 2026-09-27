package com.hestudio.miuitime;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.widget.RemoteViews;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Phase 2 reconnaissance: HyperOS 3's launcher is native (Flutter + Rust), so the
 * only Java surface left for observing home screen widgets is the framework widget
 * stack that the native widget SDK bridges into. These hooks only log, they do not
 * change any behaviour.
 */
public final class WidgetRecon {

    private WidgetRecon() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        hookAppWidgetHost(cl);
        hookAppWidgetHostView(cl);
        hookInheritedViewMethods(cl);
        hookStatusBarProxies(cl);
        XLog.i("widget recon installed");
    }

    private static void hookAppWidgetHost(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClassIfExists("android.appwidget.AppWidgetHost", cl);
        if (cls == null) {
            XLog.w("AppWidgetHost not found");
            return;
        }
        for (Method m : cls.getDeclaredMethods()) {
            String name = m.getName();
            boolean interesting = name.equals("startListening")
                    || name.equals("stopListening")
                    || name.equals("allocateAppWidgetId")
                    || name.equals("deleteAppWidgetId")
                    || name.equals("createView")
                    || name.equals("updateAppWidget")
                    || name.equals("setAppWidgetHidden")
                    || name.equals("setAppWidgetPaused");
            if (!interesting || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            hook(m, "Host." + name);
        }
    }

    private static void hookAppWidgetHostView(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClassIfExists("android.appwidget.AppWidgetHostView", cl);
        if (cls == null) {
            XLog.w("AppWidgetHostView not found");
            return;
        }
        for (Method m : cls.getDeclaredMethods()) {
            String name = m.getName();
            boolean interesting = name.equals("updateAppWidget")
                    || name.equals("onAttachedToWindow")
                    || name.equals("onDetachedFromWindow");
            if (!interesting || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if ("updateAppWidget".equals(name) && m.getParameterCount() != 1) {
                continue;
            }
            hook(m, "HostView." + name);
        }
    }

    /** setVisibility/setAlpha/... are inherited from View, so hook View and filter. */
    private static void hookInheritedViewMethods(ClassLoader cl) {
        Class<?> view = XposedHelpers.findClassIfExists("android.view.View", cl);
        if (view == null) {
            return;
        }
        for (Method m : view.getDeclaredMethods()) {
            String name = m.getName();
            boolean interesting = name.equals("setVisibility")
                    || name.equals("setAlpha")
                    || name.equals("setTranslationX")
                    || name.equals("setTranslationY");
            if (!interesting || Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof AppWidgetHostView)) {
                            return;
                        }
                        logEvent("View." + name, param);
                    }
                });
            } catch (Throwable t) {
                XLog.w("recon cannot hook View." + name, t);
            }
        }
    }

    private static void hookStatusBarProxies(ClassLoader cl) {
        hookNamedMethod(cl, "android.view.Window", "setStatusBarColor");
        hookNamedMethod(cl, "com.android.internal.policy.PhoneWindow", "setStatusBarColor");
        hookNamedMethod(cl, "android.view.InsetsController", "setSystemBarsAppearance");
        hookNamedMethod(cl, "android.view.WindowInsetsController", "setSystemBarsAppearance");
        hookNamedMethod(cl, "android.view.WindowInsetsControllerImpl", "setSystemBarsAppearance");
    }

    private static void hookNamedMethod(ClassLoader cl, String className, String methodName) {
        Class<?> cls = XposedHelpers.findClassIfExists(className, cl);
        if (cls == null) {
            return;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(methodName) || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            hook(m, simple(cls) + "." + methodName);
        }
    }

    private static String simple(Class<?> cls) {
        String name = cls.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static void hook(Method method, String label) {
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    logEvent(label, param);
                }
            });
        } catch (Throwable t) {
            XLog.w("recon cannot hook " + label, t);
        }
    }

    private static void logEvent(String label, XC_MethodHook.MethodHookParam param) {
        try {
            StringBuilder sb = new StringBuilder(label);
            Object self = param.thisObject;
            if (self instanceof AppWidgetHostView) {
                AppWidgetHostView view = (AppWidgetHostView) self;
                sb.append(" id=").append(safeWidgetId(view));
            }
            for (Object arg : param.args) {
                sb.append(" arg=");
                if (arg instanceof RemoteViews) {
                    sb.append("RemoteViews");
                } else {
                    sb.append(arg);
                }
            }
            sb.append(" thread=").append(Thread.currentThread().getName());
            XLog.i(sb.toString());
        } catch (Throwable t) {
            XLog.w("recon log failed for " + label, t);
        }
    }

    private static String safeWidgetId(AppWidgetHostView view) {
        try {
            Object id = XposedHelpers.callMethod(view, "getAppWidgetId");
            if (id instanceof Integer) {
                return String.valueOf(id) + describeProvider(view.getContext(), (Integer) id);
            }
            return String.valueOf(id);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String describeProvider(Context context, int widgetId) {
        try {
            if (context == null) {
                return "";
            }
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(widgetId);
            if (info == null || info.provider == null) {
                return "";
            }
            return "[" + info.provider.flattenToShortString() + "]";
        } catch (Throwable t) {
            return "";
        }
    }
}
