package net.hestudio.miuitime;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            switch (lpparam.packageName) {
                case "com.android.systemui":
                    SystemUiClockHook.init(lpparam);
                    break;
                case "com.miui.home":
                    LauncherBridge.init(lpparam);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            XLog.e("init failed for " + lpparam.packageName, t);
        }
    }
}
