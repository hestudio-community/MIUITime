package net.hestudio.miuitime;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Launcher side of the module.
 *
 * <p>HyperOS 3's launcher (com.miui.home) has no Java code at all
 * ({@code hasCode=false}, Flutter + Rust native), so there is nothing to hook
 * inside the app itself. The only observable surface is the framework widget
 * stack the native widget SDK calls into, which {@link WidgetRecon} instruments.</p>
 */
public final class LauncherBridge {

    private LauncherBridge() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        XLog.i("module loaded in com.miui.home process (native launcher, recon mode)");
        try {
            WidgetRecon.install(lpparam);
        } catch (Throwable t) {
            XLog.e("launcher recon failed", t);
        }
    }
}
