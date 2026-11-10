package com.dbos.pilot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * HOME-candidate resolution. Nothing is hardcoded: the guard follows whatever
 * the user picked, with "the first third-party launcher" as the automatic rule.
 *
 * Targets: if the user selected a specific launcher (home_target setting),
 * that wins as long as it is still installed and still a HOME candidate.
 * Otherwise: the user's launcher = the first HOME candidate without
 * FLAG_SYSTEM; the stock launcher(s) = candidates with FLAG_SYSTEM.
 */
public final class HomeLauncher {

    public static final String AUTO = "auto";

    private HomeLauncher() {}

    /** The launcher the guard should keep in front; null if none is resolvable. */
    public static ComponentName resolveUserHome(Context ctx) {
        // Explicit user choice first, validated against the live candidate list.
        String target = Config.getStr(ctx, Config.Key.HOMETARGET, AUTO);
        if (target != null && !target.isEmpty() && !AUTO.equals(target)) {
            ComponentName chosen = ComponentName.unflattenFromString(target);
            if (chosen != null && isCandidate(ctx, chosen)) return chosen;
        }
        // Automatic rule: first non-system candidate (never the app itself).
        for (ResolveInfo ri : query(ctx)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName == null) continue;
            if ("com.dbos.pilot".equals(ai.packageName)) continue;
            if ((ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            return new ComponentName(ai.packageName, ai.name);
        }
        return null;
    }

    /** Is the component currently a HOME candidate? (validates the user choice) */
    private static boolean isCandidate(Context ctx, ComponentName cn) {
        if (cn == null) return false;
        for (ResolveInfo ri : query(ctx)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName == null || ai.name == null) continue;
            if (cn.getPackageName().equals(ai.packageName) && cn.getClassName().equals(ai.name))
                return true;
        }
        return false;
    }

    /** All HOME candidates except the app itself, for the launcher picker. */
    public static List<ComponentName> listCandidates(Context ctx) {
        List<ComponentName> out = new ArrayList<ComponentName>();
        for (ResolveInfo ri : query(ctx)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName == null || ai.name == null) continue;
            if ("com.dbos.pilot".equals(ai.packageName)) continue;
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            if (!out.contains(cn)) out.add(cn);
        }
        return out;
    }

    /** Display label for a candidate (app label, falls back to package name). */
    public static String labelOf(Context ctx, ComponentName cn) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ActivityInfo ai = pm.getActivityInfo(cn, 0);
            CharSequence l = ai.loadLabel(pm);
            return l != null ? l.toString() : cn.getPackageName();
        } catch (Throwable t) {
            return cn.getPackageName();
        }
    }

    /** Package names of the stock (system) launchers — the ones the guard corrects. */
    public static List<String> resolveStockHomePkgs(Context ctx) {
        List<String> out = new ArrayList<String>();
        for (ResolveInfo ri : query(ctx)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName == null) continue;
            if ((ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
            if (!out.contains(ai.packageName)) out.add(ai.packageName);
        }
        return out;
    }

    /**
     * The stock launchers' main HOME activities (e.g. SplashActivity). The guard
     * only corrects these; the stock launcher's secondary pages (the built-in
     * market, detail pages) are pages the user walked into and are allowed.
     */
    public static List<String> resolveStockHomeClasses(Context ctx) {
        List<String> out = new ArrayList<String>();
        for (ResolveInfo ri : query(ctx)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName == null || ai.name == null) continue;
            if ((ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
            if (!out.contains(ai.name)) out.add(ai.name);
        }
        return out;
    }

    private static List<ResolveInfo> query(Context ctx) {
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_HOME);
            return ctx.getPackageManager().queryIntentActivities(it, 0);
        } catch (Throwable ignored) {
            return new ArrayList<ResolveInfo>();
        }
    }
}
