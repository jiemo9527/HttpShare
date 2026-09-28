package io.github.jiemo9527.httpshare;

import android.content.pm.ApplicationInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

/**
 * 作用域：
 * <ul>
 *   <li>本应用：让 {@link ModuleStatus#isActive()} 返回 true，界面据此自动隐藏桌面图标。</li>
 *   <li>系统框架：Android 10+ 对“声明了权限却没有启动器入口”的应用会生成指向应用详情的替身图标，
 *       这里让 LauncherAppsService.shouldShowSyntheticActivity 对本包返回 false，隐藏才彻底。</li>
 * </ul>
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "HttpShare";
    private static boolean systemHooked;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (BuildConfigPkg.NAME.equals(lp.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(ModuleStatus.class.getName(), lp.classLoader,
                        "isActive", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": self hook failed: " + t);
            }
            return;
        }
        if (!"android".equals(lp.packageName) || systemHooked) {
            return;
        }
        systemHooked = true;
        try {
            Class<?> impl = XposedHelpers.findClass(
                    "com.android.server.pm.LauncherAppsService$LauncherAppsImpl", lp.classLoader);
            int n = 0;
            for (Method m : impl.getDeclaredMethods()) {
                if (!m.getName().equals("shouldShowSyntheticActivity")) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        for (Object a : param.args) {
                            if (a instanceof ApplicationInfo
                                    && BuildConfigPkg.NAME.equals(((ApplicationInfo) a).packageName)) {
                                param.setResult(false);
                                return;
                            }
                        }
                    }
                });
                n++;
            }
            XposedBridge.log(TAG + ": hooked shouldShowSyntheticActivity x" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": system hook failed: " + t);
        }
    }

    /** 避免依赖生成的 BuildConfig（AGP 8 默认关闭） */
    static final class BuildConfigPkg {
        static final String NAME = "io.github.jiemo9527.httpshare";
    }
}
