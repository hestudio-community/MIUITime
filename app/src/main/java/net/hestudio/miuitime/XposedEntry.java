package net.hestudio.miuitime;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if ("com.android.systemui".equals(lpparam.packageName)) {
                SystemUiClockHook.init(lpparam);
            }
        } catch (Throwable t) {
            XLog.e("init failed for " + lpparam.packageName, t);
        }
    }
}
