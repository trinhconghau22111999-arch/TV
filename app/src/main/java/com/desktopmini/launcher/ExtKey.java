package com.desktopmini.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

/**
 * Khóa nhận diện một ứng dụng ngoài trên Dock nổi và trong đa nhiệm.
 * Thường khóa là tên gói (ví dụ com.zing.zalo). Riêng app phụ "Trình duyệt + Ổ mềm" có hai màn hình
 * độc lập chung một gói, nên khóa của mỗi màn hình là tên đầy đủ "gói/lớp" để mỗi cái có icon, tên
 * và chấm đang chạy riêng.
 */
final class ExtKey {
    static final String WEB_PKG = "com.desktopmini.tvweb";
    static final String WEB_CLS = "com.desktopmini.web";
    /** Khóa của màn hình "Trình duyệt" và "Ổ mềm" trong app phụ. */
    static final String BROWSER = WEB_PKG + "/" + WEB_CLS + ".BrowserActivity";
    static final String OMEM = WEB_PKG + "/" + WEB_CLS + ".OmemActivity";

    /** Khóa của cửa sổ đang hiện; null nếu là cửa sổ phụ của app phụ (hộp thoại...) không cần theo dõi riêng. */
    static String of(String pkg, CharSequence cls) {
        if (pkg == null) return null;
        if (!WEB_PKG.equals(pkg)) return pkg;
        String c = cls == null ? "" : cls.toString();
        if (c.endsWith(".BrowserActivity")) return BROWSER;
        if (c.endsWith(".OmemActivity")) return OMEM;
        return null;
    }

    static String pkgOf(String key) {
        int s = key.indexOf('/');
        return s < 0 ? key : key.substring(0, s);
    }

    static ComponentName component(String key) {
        return key != null && key.indexOf('/') >= 0 ? ComponentName.unflattenFromString(key) : null;
    }

    static Intent launchIntent(PackageManager pm, String key) {
        ComponentName cn = component(key);
        if (cn != null) return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn);
        return pm.getLaunchIntentForPackage(key);
    }

    static String label(PackageManager pm, String key) throws PackageManager.NameNotFoundException {
        ComponentName cn = component(key);
        if (cn != null) return pm.getActivityInfo(cn, 0).loadLabel(pm).toString();
        return pm.getApplicationLabel(pm.getApplicationInfo(key, 0)).toString();
    }

    /** Icon riêng của từng màn hình (ví dụ ic_browser, ic_omem), không dùng icon mặc định của gói. */
    static Drawable icon(PackageManager pm, String key) throws PackageManager.NameNotFoundException {
        ComponentName cn = component(key);
        if (cn != null) return pm.getActivityIcon(cn);
        return pm.getApplicationIcon(key);
    }
}
