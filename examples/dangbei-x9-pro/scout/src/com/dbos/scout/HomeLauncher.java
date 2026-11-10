package com.dbos.scout;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态解析 HOME 候选，不写死任何 launcher 包名。
 *
 * 规则（与用户约定）：
 *   - 用户桌面 = 所有 CATEGORY_HOME 候选中，第一个「非系统预装」的（安装时没有
 *     FLAG_SYSTEM 标志）。今天装的是 Kido，明天换成别的 launcher 也自动跟随。
 *   - 原厂桌面 = 候选中带 FLAG_SYSTEM 的包（leradlauncher / SplashActivity 等）。
 */
public final class HomeLauncher {

    private HomeLauncher() {}

    /** 第一个用户安装的 launcher；没有则 null */
    public static ComponentName resolveUserHome(Context ctx) {
        for (ResolveInfo ri : query(ctx)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName == null) continue;
            if ("com.dbos.scout".equals(ai.packageName)) continue;      // 不选自己
            if ((ai.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                continue;                                               // 跳过系统预装
            return new ComponentName(ai.packageName, ai.name);
        }
        return null;
    }

    /** 原厂（系统预装）桌面的包名集合 */
    public static List<String> resolveStockHomePkgs(Context ctx) {
        List<String> out = new ArrayList<String>();
        for (ResolveInfo ri : query(ctx)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName == null) continue;
            if ((ai.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0)
                continue;                                               // 只收系统包
            if (!out.contains(ai.packageName)) out.add(ai.packageName);
        }
        return out;
    }

    /**
     * v12: 原厂桌面的「主页 Activity」类名集合（SplashActivity 等，声明 CATEGORY_HOME 的
     * 那个组件）。守护只对主页面纠偏；桌面包的次级页面（应用市场 AppSecondaryActivity、
     * 应用详情页等）是用户主动进入的功能页，一律放行——否则市场跳板会被守护拉回
     * 用户桌面，用户按 BACK 退到主页时再纠偏即可。
     */
    public static List<String> resolveStockHomeClasses(Context ctx) {
        List<String> out = new ArrayList<String>();
        for (ResolveInfo ri : query(ctx)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName == null || ai.name == null) continue;
            if ((ai.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0)
                continue;
            if (!out.contains(ai.name)) out.add(ai.name);
        }
        return out;
    }

    private static List<ResolveInfo> query(Context ctx) {
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_HOME);
            PackageManager pm = ctx.getPackageManager();
            return pm.queryIntentActivities(it, 0);
        } catch (Throwable ignored) {
            return new ArrayList<ResolveInfo>();
        }
    }
}
