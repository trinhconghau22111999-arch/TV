package com.desktopmini.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Size;
import androidx.webkit.WebViewAssetLoader;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity {
    private static MainActivity inst;
    FrameLayout root;
    private boolean askingRuntime = false;
    WebView web;
    final HashMap<String, WebView> ext = new HashMap<String, WebView>();
    /* Zoom (2 ngón / Ctrl+lăn chuột) + nhớ mức zoom theo từng ứng dụng web nhúng */
    final HashMap<String, ZoomHelper> zooms = new HashMap<String, ZoomHelper>();
    /* Tự đóng hẳn trang web nhúng đã ẩn lâu để giải phóng RAM, như điện thoại đóng ứng dụng nền.
       Mở lại cửa sổ đó thì trang được tạo mới và tải lại (extShow -> extGet). Đổi EXT_IDLE_MS để chỉnh thời gian chờ. */
    private static final long EXT_IDLE_MS = 10 * 60 * 1000L;
    /* YouTube: báo cho trang luôn là "đang hiển thị" để trình phát không tự dừng khi cửa sổ bị ẩn / app khác che */
    private static final String YT_KEEP_JS = "(function(){try{var d=document;Object.defineProperty(d,'hidden',{configurable:true,get:function(){return false}});Object.defineProperty(d,'visibilityState',{configurable:true,get:function(){return 'visible'}});['visibilitychange','webkitvisibilitychange'].forEach(function(t){d.addEventListener(t,function(e){e.stopImmediatePropagation()},true)});window.addEventListener('blur',function(e){e.stopImmediatePropagation()},true)}catch(e){}})();";
    final HashMap<String, Long> extHiddenAt = new HashMap<String, Long>();
    private final android.os.Handler reaper = new android.os.Handler(android.os.Looper.getMainLooper());
    /* Cài/gỡ/cập nhật app: dựng lại danh sách app cho Launchpad và Tìm kiếm (gộp các sự kiện bắn liền nhau, chờ 2 giây) */
    private final Runnable appsRefresh = new Runnable() { public void run() { appsAt = 0; prefetchApps(); } };
    private final android.content.BroadcastReceiver pkgRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reaper.removeCallbacks(appsRefresh); reaper.postDelayed(appsRefresh, 2000); }
    };
    /* Độ sáng thấp nhất khi không dùng: không chạm/gõ/rê chuột sau idleDimMs thì hạ độ sáng cửa sổ xuống mức tối nhất, chạm lại thì sáng như cũ */
    volatile boolean idleDimOn = false;
    volatile long idleDimMs = 5 * 60000L;
    volatile long lastTouchAt = android.os.SystemClock.elapsedRealtime();
    private boolean idleDimmed = false, inFg = false;
    private float idleDimPrev = -1f;
    private final Runnable idleTask = new Runnable() { public void run() { checkIdleDim(); reaper.postDelayed(this, 5000); } };

    private void userActive() {
        lastTouchAt = android.os.SystemClock.elapsedRealtime();
        if (idleDimmed) undimIdle();
    }
    void undimIdle() {
        if (!idleDimmed) return;
        idleDimmed = false;
        try { WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = idleDimPrev; getWindow().setAttributes(lp); } catch (Exception e) { }
    }
    /** Chưa tới hạn thì thôi; đang xem video toàn màn hình hoặc đang phát nhạc/video trong trang web nhúng thì không hạ (tránh tối màn hình khi đang xem). */
    private void checkIdleDim() {
        if (!idleDimOn || idleDimmed || !inFg) return;
        if (android.os.SystemClock.elapsedRealtime() - lastTouchAt < idleDimMs) return;
        if (extFullView != null) return;
        try {
            boolean music = ((android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive();
            if (music) for (WebView v : ext.values()) if (v.getVisibility() == View.VISIBLE) return;
        } catch (Exception e) { }
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            idleDimPrev = lp.screenBrightness; lp.screenBrightness = 0.004f; getWindow().setAttributes(lp);
            idleDimmed = true;
        } catch (Exception e) { }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent ev) { userActive(); return super.dispatchTouchEvent(ev); }
    @Override public boolean dispatchKeyEvent(KeyEvent ev) { userActive(); return super.dispatchKeyEvent(ev); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) { userActive(); return super.dispatchGenericMotionEvent(ev); }

    private final Runnable reapTask = new Runnable() { public void run() { reapIdleExt(); reaper.postDelayed(this, 60000); } };
    /** Gmail / Drive / Zalo / Facebook web: giao diện máy tính. */
    private static final String UA_DESK = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    /** Đẩy sự kiện từ service nền vào giao diện HTML. */
    static void push(final String fn, final String json) {
        final MainActivity a = inst;
        if (a == null || a.web == null) return;
        a.runOnUiThread(new Runnable() { public void run() { a.web.evaluateJavascript("window." + fn + "&&window." + fn + "(" + json + ")", null); } });
    }

    static String iconOf(Context c, String pkg) {
        try { return icon(c.getPackageManager().getApplicationIcon(pkg), 64); } catch (Exception e) { return ""; }
    }

    private final BroadcastReceiver media = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String cmd = i.getStringExtra("cmd");
            if (cmd == null) return;
            if ("toggle".equals(cmd)) {
                /* Phát/Dừng xử lý thẳng ở Android (chạy cả khi trang web đang bị tạm dừng), rồi báo giao diện + dựng lại thông báo */
                int st = LauncherBridge.toggleNative();
                if (st >= 0) {
                    final boolean pl = st == 1;
                    try {
                        Intent n = new Intent(c, MusicService.class).putExtra("title", LauncherBridge.lastTitle).putExtra("artist", LauncherBridge.lastArtist).putExtra("playing", pl);
                        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(n); else c.startService(n);
                    } catch (Exception e) { }
                    if (web != null) web.evaluateJavascript("window.nativeMediaState&&window.nativeMediaState(" + pl + ")", null);
                    return;
                }
            }
            if (web != null) web.evaluateJavascript("window.nativeMedia&&window.nativeMedia('" + cmd + "')", null);
        }
    };

    private final BroadcastReceiver scr = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (web != null) web.evaluateJavascript("window.nativeScreenOff&&window.nativeScreenOff()", null);
        }
    };
    android.media.Ringtone previewTone;
    /* ---- Bluetooth: thiết bị tìm thấy xung quanh + proxy hồ sơ để biết thiết bị nào đang kết nối ---- */
    /** "Duyệt web": trình duyệt Webandr đầy đủ chạy nhúng trong cửa sổ (tạo khi mở lần đầu). */
    BrowserHost browserHost;
    final java.util.LinkedHashMap<String, JSONObject> btFound = new java.util.LinkedHashMap<String, JSONObject>();
    final HashMap<Integer, BluetoothProfile> btProx = new HashMap<Integer, BluetoothProfile>();
    boolean btRecv = false, btProxReq = false;
    final BroadcastReceiver btRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try {
                String act = i.getAction();
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(act)) { btDiscovering = false; return; }
                if (!BluetoothDevice.ACTION_FOUND.equals(act)) return;
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || d.getBondState() == BluetoothDevice.BOND_BONDED) return;
                String nm = i.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (nm == null || nm.length() == 0) { try { nm = d.getName(); } catch (Exception e) { } }
                boolean named = nm != null && nm.length() > 0;
                String ad = d.getAddress();
                JSONObject o = new JSONObject(); o.put("name", named ? nm : "Thiết bị " + (ad == null ? "" : ad)); o.put("addr", ad); o.put("named", named);
                synchronized (btFound) {
                    JSONObject old = btFound.get(ad);
                    if (old != null && old.optBoolean("named") && !named) return;
                    btFound.put(ad, o);
                }
            } catch (Exception e) { }
        }
    };
    boolean btDiscovering = false;


    /** Origin ảo của trang chính; các tệp trong assets/ được phục vụ qua WebViewAssetLoader. */
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net/assets/";
    private WebViewAssetLoader assetLoader;

    /** Một lần duy nhất sau khi chuyển sang AssetLoader: xóa dữ liệu localStorage cũ của origin file:// (trang mới ở origin khác nên không đọc được, chỉ tốn chỗ). */
    private void purgeOldFileOrigin() {
        final android.content.SharedPreferences sp = getSharedPreferences("dm_native", MODE_PRIVATE);
        if (sp.getBoolean("purged_file_origin_v1", false)) return;
        try {
            final WebView pw = new WebView(this);
            WebSettings ps = pw.getSettings();
            ps.setJavaScriptEnabled(true); ps.setDomStorageEnabled(true);
            ps.setAllowFileAccess(false); ps.setAllowContentAccess(false);
            pw.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(final WebView v, String u) {
                    /* chờ vài giây cho WebView kịp ghi việc xóa xuống đĩa rồi mới hủy và đánh dấu đã xong */
                    v.postDelayed(new Runnable() { public void run() {
                        sp.edit().putBoolean("purged_file_origin_v1", true).apply();
                        try { v.destroy(); } catch (Exception e) { }
                    } }, 6000);
                }
            });
            pw.loadUrl("file:///android_asset/purge-old.html");
        } catch (Throwable t) { }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        CrashLog.install(getApplicationContext());
        final String lastExit = CrashLog.reportLastExit(this);
        final String javaCrash = CrashLog.takeLastCrash(this);
        final boolean uncleanExit = CrashLog.beginSession(this);
        CrashLog.reportAsync(this, lastExit, javaCrash, uncleanExit);   /* lần trước lỗi / tắt đột ngột -> hiện hộp báo lỗi trên màn hình để chụp gửi */
        reaper.postDelayed(hbTask, 5000);
        new Thread(new Runnable() { public void run() { try { TrashStore.purgeOld(MainActivity.this); } catch (Exception e) { } } }).start();   /* Thùng rác: dọn mục quá 7 ngày */
        assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or2, int ob) {
            if (b != ob || t != ot) reportDockGap(); } });
        if (Build.VERSION.SDK_INT >= 30) {
            /* Không để hệ thống tự chừa chỗ cho thanh trạng thái/điều hướng: nếu không, mỗi lần thanh hiện thoáng qua (vuốt mép, quay lại app...)
               giao diện bị đẩy xuống ("giật"), có lúc kẹt lại một khoảng trống bằng thanh menu ở trên cùng. Chỉ chừa chỗ cho bàn phím. */
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    int ime = in.getInsets(WindowInsets.Type.ime()).bottom;
                    if (v.getPaddingBottom() != ime) v.setPadding(0, 0, 0, ime);
                    /* Vuốt từ mép trên/dưới làm thanh hệ thống hiện ra: ẩn lại ngay để không kéo được thanh hệ thống */
                    if (!imeUp && (in.isVisible(WindowInsets.Type.statusBars()) || in.isVisible(WindowInsets.Type.navigationBars()))) rehideBars();
                    return WindowInsets.CONSUMED;
                }
            });
        } else {
            /* Android 10 trở xuống (vd. 7.1.1): khung nội dung mặc định của hệ thống có fitsSystemWindows=true nên tự đệm một khoảng bằng thanh trạng thái
               mỗi khi cờ toàn màn hình bị hệ thống gỡ tạm (vuốt mép, quay lại app, đóng bàn phím...) -> giao diện giật và kẹt khoảng trống ở trên cùng.
               Tắt đệm tự động đó, tự chừa chỗ chỉ cho bàn phím. */
            try {
                View content = findViewById(android.R.id.content);
                content.setFitsSystemWindows(false);
                if (content.getParent() instanceof View) ((View) content.getParent()).setFitsSystemWindows(false);
            } catch (Exception e) { }
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    /* Đáy lớn hơn thanh điều hướng = bàn phím đang mở */
                    int kb = in.getSystemWindowInsetBottom() > in.getStableInsetBottom() ? in.getSystemWindowInsetBottom() : 0;
                    if (v.getPaddingBottom() != kb) v.setPadding(0, 0, 0, kb);
                    return in.consumeSystemWindowInsets();
                }
            });
            /* Vuốt từ mép làm thanh hệ thống hiện ra: ẩn lại ngay */
            getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
                @Override public void onSystemUiVisibilityChange(int vis) {
                    if (!imeUp && (vis & (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)) != (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)) rehideBars();
                }
            });
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        /* Trang chính chạy ở https://appassets.androidplatform.net/assets/ (WebViewAssetLoader), không còn là file://:
           không cần quyền đọc tệp cục bộ, và có origin thật nên áp được chính sách bảo mật. Khóa mọi quyền file/content. */
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        web.setBackgroundColor(0x00000000);
        web.setFocusable(true); web.setFocusableInTouchMode(true); web.requestFocus();
        web.setWebViewClient(new WebViewClient() {
            /* Trang chính chỉ được ở trong APP_ORIGIN (tài nguyên của app). Mọi điều hướng khác bị chặn (link ngoài mở ở app/trình duyệt riêng, không nạp vào trang có cầu AndroidLauncher) */
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return assetLoader.shouldInterceptRequest(req.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                String u = req.getUrl().toString();
                if (u.startsWith(APP_ORIGIN) || u.startsWith("about:")) return false;
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { }
                }
                return true;
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onPermissionRequest(final android.webkit.PermissionRequest q) {
                runOnUiThread(new Runnable() { public void run() {
                    ArrayList<String> miss = new ArrayList<String>(); boolean known = false;
                    for (String rs : q.getResources()) {
                        if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(rs)) { known = true; if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.RECORD_AUDIO); }
                        else if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(rs)) { known = true; if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.CAMERA); }
                    }
                    if (!known) { q.deny(); return; }
                    if (miss.isEmpty()) { answerWeb(q); return; }
                    if (micReq != null) micReq.deny();
                    micReq = q; askingRuntime = true;
                    requestPermissions(miss.toArray(new String[0]), 77);
                } });
            }
        });
        web.addJavascriptInterface(new LauncherBridge(this), "AndroidLauncher");
        purgeOldFileOrigin();
        web.loadUrl(APP_ORIGIN + "index.html");
        prefetchApps();
        warnOldWebView();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        IntentFilter f = new IntentFilter("com.desktopmini.launcher.MEDIA");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(media, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(media, f);
        ScreenTime.start(this);
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scr, sf, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(scr, sf);
        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED); pf.addAction(Intent.ACTION_PACKAGE_REPLACED); pf.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgRx, pf, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(pkgRx, pf);
        trackIme();
        immersive();
        askPermissions();
        reaper.postDelayed(reapTask, 60000);
        reaper.postDelayed(idleTask, 5000);
    }

    /** Cắm / rút sạc và pin đổi mức: báo giao diện cập nhật icon pin (tia sét) ngay, không chờ vòng dò 10 giây. */
    private boolean powerRxOn = false;
    private final BroadcastReceiver powerRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try { if (web != null) web.evaluateJavascript("window.nativeBatt&&window.nativeBatt()", null); } catch (Throwable t) { }
        }
    };
    private void powerRxStart() {
        if (powerRxOn) return;
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_POWER_CONNECTED); f.addAction(Intent.ACTION_POWER_DISCONNECTED); f.addAction(Intent.ACTION_BATTERY_CHANGED);
            registerReceiver(powerRx, f); powerRxOn = true;
        } catch (Throwable t) { }
    }
    private void powerRxStop() {
        if (!powerRxOn) return;
        try { unregisterReceiver(powerRx); } catch (Throwable t) { }
        powerRxOn = false;
    }

    @Override protected void onPause() {
        super.onPause();
        powerRxStop();
        inFg = false; undimIdle();
        if (browserHost != null) { try { browserHost.onPause(); } catch (Exception e) { } }
        if (wordHost != null) wordHost.save();
        /* Tối ưu: rời Desktop Mini thì tạm dừng mọi trang web nhúng (YouTube, Zalo, Office...) cho đỡ tốn pin/RAM, như ứng dụng ở nền trên điện thoại */
        for (java.util.Map.Entry<String, WebView> e : ext.entrySet()) extPause(e.getKey(), e.getValue());
        for (String k : ext.keySet()) markExtHidden(k);
    }

    /** Tạm dừng trang web nhúng khi bị ẩn. Riêng YouTube đang phát video/nhạc thì KHÔNG tạm dừng: cứ phát tiếp dù cửa sổ bị thu nhỏ, bị app khác che hoặc rời khỏi Desktop Mini. */
    void extPause(String key, WebView v) {
        if (v == null) return;
        if ("youtube".equals(key)) {
            try { if (((android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive()) return; } catch (Exception e) { }
        }
        try { v.onPause(); } catch (Exception e) { }
    }

    /** Ghi lại thời điểm trang bắt đầu bị ẩn (chỉ ghi lần đầu, không đặt lại khi ẩn tiếp). */
    void markExtHidden(String key) {
        if (!extHiddenAt.containsKey(key)) extHiddenAt.put(key, android.os.SystemClock.elapsedRealtime());
    }

    /** Đóng hẳn các trang nhúng đã ẩn quá EXT_IDLE_MS. Bỏ qua trang đang hiện, "Gõ văn bản" (giữ tài liệu đang soạn) và lúc đang phát nhạc. */
    private void reapIdleExt() { reapExt(EXT_IDLE_MS); }

    /** minIdleMs = 0: đóng NGAY mọi trang nhúng đang ẩn (dùng khi máy sắp hết RAM), không chờ đủ thời gian. */
    private void reapExt(long minIdleMs) {
        if (ext.isEmpty()) return;
        if (minIdleMs == 0 && extFullView != null) return; /* đang xem toàn màn hình thì không đụng tới */
        long now = android.os.SystemClock.elapsedRealtime();
        boolean music = false;
        try { music = ((android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive(); } catch (Exception e) { }
        if (music && minIdleMs > 0) return; /* đang phát thì giữ nguyên; chỉ khi máy sắp hết RAM (minIdleMs = 0) mới đóng cả trang đang phát */
        for (String key : new ArrayList<String>(ext.keySet())) {
            Long t = extHiddenAt.get(key);
            WebView v = ext.get(key);
            if (v == null || v.getVisibility() == View.VISIBLE || "word".equals(key)) continue;
            if (minIdleMs > 0 && (t == null || now - t < minIdleMs)) continue;
            ext.remove(key); zooms.remove(key); extHiddenAt.remove(key);
            try { v.loadUrl("about:blank"); root.removeView(v); v.destroy(); } catch (Exception e) { }
        }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        CrashLog.trimEvent(this, level);
        /* Máy sắp hết RAM thật (đang chạy mà RAM tới mức "nguy cấp", hoặc app ở nền bị đẩy tới giữa/cuối danh sách bị giết): đóng ngay trang nhúng đang ẩn.
           Cố ý KHÔNG dùng mức UI_HIDDEN (20) và BACKGROUND (40): hai mức này xảy ra mỗi khi mở app khác từ launcher, chưa phải thiếu RAM, đóng thì YouTube/Zalo bị tải lại liên tục. */
        if (browserHost != null) { try { browserHost.onTrimMemory(level); } catch (Exception e) { } }
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_MODERATE) { reapExt(0); }
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            for (WebView v : ext.values()) { try { if (v.getVisibility() != View.VISIBLE) v.clearCache(false); } catch (Exception e) { } }
        }
    }

    /** Mỗi 20 giây ghi nhanh tình trạng RAM để lần sau có dữ liệu nếu app bị tắt đột ngột. */
    private final Runnable hbTask = new Runnable() { public void run() { CrashLog.heartbeat(getApplicationContext()); reaper.postDelayed(this, 20000); } };

    @Override protected void onDestroy() {
        if (browserHost != null) { try { browserHost.destroy(); } catch (Exception e) { } browserHost = null; }
        aiCancel();
        stopMediaWatch();
        reaper.removeCallbacks(reapTask); reaper.removeCallbacks(idleTask); reaper.removeCallbacks(appsRefresh);
        reaper.removeCallbacks(hbTask);
        if (isFinishing()) CrashLog.endSession(this);
        try { unregisterReceiver(pkgRx); } catch (Exception e) { }
        try { unregisterReceiver(media); } catch (Exception e) { }
        try { unregisterReceiver(scr); } catch (Exception e) { }
        if (btRecv) { try { unregisterReceiver(btRx); } catch (Exception e) { } }
        if (inst == this) inst = null;
        super.onDestroy();
    }

    /** Bàn phím đang mở? Khi mở thì không ép ẩn thanh hệ thống, vì làm vậy sẽ đóng bàn phím (ô nhập bị văng). */
    private boolean imeUp = false;
    private android.webkit.PermissionRequest micReq = null;
    /** Trả lời yêu cầu micro/camera của trang web: chỉ cho phần mà Android đã cấp. */
    private void answerWeb(android.webkit.PermissionRequest q) {
        ArrayList<String> ok = new ArrayList<String>();
        for (String rs : q.getResources()) {
            boolean g = Build.VERSION.SDK_INT < 23;
            if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(rs)) g = g || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            else if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(rs)) g = g || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (g) ok.add(rs);
        }
        if (ok.isEmpty()) q.deny(); else q.grant(ok.toArray(new String[0]));
    }
    private void trackIme() {
        final View d = getWindow().getDecorView();
        d.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                boolean up;
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsets wi = d.getRootWindowInsets();
                    up = wi != null && wi.isVisible(WindowInsets.Type.ime());
                } else {
                    android.graphics.Rect r = new android.graphics.Rect();
                    d.getWindowVisibleDisplayFrame(r);
                    int h = d.getRootView().getHeight();
                    up = h > 0 && (h - r.bottom) > h * 0.15f;
                }
                if (up != imeUp) { imeUp = up; if (!up) immersive(); }
            }
        });
    }

    private boolean rehidePending = false;
    /** Thanh hệ thống vừa hiện (do vuốt từ mép): ẩn lại ngay ở vòng lặp giao diện kế tiếp. */
    private void rehideBars() {
        if (rehidePending) return;
        rehidePending = true;
        root.post(new Runnable() { public void run() { rehidePending = false; if (inFg) immersive(); } });
    }

    private void immersive() {
        if (imeUp) return;
        if (Build.VERSION.SDK_INT >= 30) {
            /* Android 11+: cờ SYSTEM_UI_FLAG cũ hay bị bỏ qua, dùng WindowInsetsController để ẩn thanh điều hướng */
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Xin các quyền thường ngay lần mở đầu tiên sau khi cài. */
    private void askPermissions() {
        ArrayList<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.READ_MEDIA_AUDIO); need.add(Manifest.permission.READ_MEDIA_IMAGES); need.add(Manifest.permission.READ_MEDIA_VIDEO); need.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            /* Android 9 trở xuống: lưu ảnh/video vào Pictures, Movies cần quyền ghi bộ nhớ (xin lúc chạy, không chỉ khai trong manifest) */
            if (Build.VERSION.SDK_INT < 29) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 31) { need.add(Manifest.permission.BLUETOOTH_CONNECT); need.add(Manifest.permission.BLUETOOTH_SCAN); }
        need.add(Manifest.permission.RECORD_AUDIO);
        need.add(Manifest.permission.CAMERA);
        need.add(Manifest.permission.READ_CALENDAR);
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        ArrayList<String> miss = new ArrayList<String>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
        if (!miss.isEmpty()) { askingRuntime = true; requestPermissions(miss.toArray(new String[0]), 7); }
    }

    private boolean listed(String setting) {
        String v = Settings.Secure.getString(getContentResolver(), setting);
        return v != null && v.contains(getPackageName());
    }
    boolean notifAccess() { return listed("enabled_notification_listeners"); }
    boolean a11yOn() { return listed(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); }
    Uri mediaUri(String kind) {
        if ("vid".equals(kind)) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        if ("aud".equals(kind)) return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    /** Desktop Mini có đang là màn hình chính mặc định của Android không. */
    boolean isDefaultHome() {
        try {
            ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }

    boolean filesOk() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Mỗi quyền đặc biệt chỉ hỏi một lần; lần lượt từng quyền mỗi khi quay lại app. */
    private void setupNext() {
        SharedPreferences sp = getSharedPreferences("setup", 0);
        if (!Settings.System.canWrite(this) && !sp.getBoolean("ws", false)) {
            sp.edit().putBoolean("ws", true).apply();
            go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))); return;
        }
        if (!notifAccess() && !sp.getBoolean("nl", false)) {
            sp.edit().putBoolean("nl", true).apply();
            go(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); return;
        }
        if (!a11yOn() && !sp.getBoolean("ax", false)) {
            sp.edit().putBoolean("ax", true).apply();
            go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return;
        }
        if (Build.VERSION.SDK_INT <= 29 && !hasSecureWrite() && !sp.getBoolean("ss", false)) {
            sp.edit().putBoolean("ss", true).apply();
            askSecureWrite(); return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted() && !sp.getBoolean("dnd", false)) {
            sp.edit().putBoolean("dnd", true).apply();
            go(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return;
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && !sp.getBoolean("af", false)) {
            sp.edit().putBoolean("af", true).apply();
            go(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); return;
        }
    }


    /** Sau khi app bị tắt tiến trình (văng, hết RAM, "Khởi động lại launcher"), Android 7 vẫn ghi dịch vụ trợ năng là ĐÃ BẬT nhưng không nối lại nữa:
     *  Dock/thanh Desktop mất, chừa khoảng trống bằng thanh đa nhiệm, menu nguồn đòi bật trợ năng. Có quyền WRITE_SECURE_SETTINGS thì tự tắt rồi bật lại
     *  dịch vụ (đúng như người dùng làm tay) để hệ thống nối lại. Trả true nếu đã bắt đầu nối lại. */
    private long lastHeal = 0;
    boolean healA11y(boolean force) {
        try {
            if (LockService.isRunning() || !a11yOn() || !hasSecureWrite()) return false;
            long now = android.os.SystemClock.elapsedRealtime();
            if (!force && now - lastHeal < 30000) return false;
            if (now - lastHeal < 4000) return true; /* đang nối lại */
            lastHeal = now;
            final android.content.ContentResolver cr = getContentResolver();
            final String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
            final String cur = Settings.Secure.getString(cr, key);
            if (cur == null) return false;
            StringBuilder sb = new StringBuilder();
            for (String t : cur.split(":")) { if (t.length() == 0 || t.startsWith(getPackageName() + "/")) continue; if (sb.length() > 0) sb.append(':'); sb.append(t); }
            Settings.Secure.putString(cr, key, sb.toString());
            root.postDelayed(new Runnable() { public void run() {
                try { Settings.Secure.putString(cr, key, cur); Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1); } catch (Throwable t) { }
            } }, 700);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static final String SS_CMD = "pm grant com.desktopmini.tv android.permission.WRITE_SECURE_SETTINGS";
    private boolean hasSecureWrite() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }
    /** Android 10 trở xuống: hỏi quyền để ẩn hẳn thanh trạng thái / thanh điều hướng khi mở app khác. */
    private void askSecureWrite() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Ẩn thanh trạng thái và thanh điều hướng")
            .setMessage("Để ẩn hẳn thanh thông báo và thanh điều hướng của hệ thống khi mở app khác, Desktop Mini cần quyền đặc biệt WRITE_SECURE_SETTINGS.\n\n"
                + "Android không có hộp xin quyền này, nên phải cấp bằng một trong hai cách:\n"
                + "• Máy đã root: bấm \"Cấp bằng root\".\n"
                + "• Máy tính: nối adb rồi chạy\nadb shell " + SS_CMD)
            .setPositiveButton("Cấp bằng root", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { grantByRoot(); } })
            .setNeutralButton("Sao chép lệnh adb", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    try {
                        ((android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(android.content.ClipData.newPlainText("adb", "adb shell " + SS_CMD));
                        android.widget.Toast.makeText(MainActivity.this, "Đã sao chép lệnh", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { }
                } })
            .setNegativeButton("Để sau", null)
            .show();
    }
    private void grantByRoot() {
        new Thread(new Runnable() { public void run() {
            boolean ok = false;
            try {
                Process p = Runtime.getRuntime().exec(new String[] { "su", "-c", SS_CMD });
                p.waitFor();
            } catch (Exception e) { }
            ok = hasSecureWrite();
            final boolean res = ok;
            runOnUiThread(new Runnable() { public void run() {
                android.widget.Toast.makeText(MainActivity.this, res ? "Đã cấp quyền, thanh hệ thống sẽ được ẩn khi mở app khác"
                    : "Không cấp được (máy chưa root?). Hãy dùng lệnh adb.", android.widget.Toast.LENGTH_LONG).show();
            } });
        } }).start();
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        askingRuntime = false;
        if (browserHost != null && c >= 2100 && c < 2200) { browserHost.handlePermissionsResult(c, p, r); return; }
        if (c == PERM_VOICE) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) launchVoiceSearch();
            else android.widget.Toast.makeText(this, "Cần cấp quyền micro để dùng tìm kiếm bằng giọng nói", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (c == PERM_AI) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) aiListen();
            else android.widget.Toast.makeText(this, "Cần cấp quyền micro để ra lệnh bằng giọng nói", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (c == 77) {
            final android.webkit.PermissionRequest q = micReq; micReq = null;
            if (q != null) answerWeb(q);
            return;
        }
        web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null);
        root.post(new Runnable() { public void run() { setupNext(); } });
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (wordHost != null && wordHost.handleResult(req, res, data)) return;
        if (browserHost != null && browserHost.handleActivityResult(req, res, data)) return;
        if (req == REQ_EXTFILE) {
            if (extFileCb != null) { extFileCb.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(res, data)); extFileCb = null; }
            return;
        }
        if (req == REQ_VOICE) {
            if (res == RESULT_OK && data != null) {
                ArrayList<String> rs = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (rs != null && !rs.isEmpty()) {
                    String q = rs.get(0);
                    WebView tw = voiceKey == null ? null : ext.get(voiceKey);
                    if (tw != null) {   // nút mic trong cửa sổ YouTube / Duyệt web nhúng
                        String cu = tw.getUrl();
                        boolean onYt = "youtube".equals(voiceKey) || (cu != null && cu.contains("youtube.com"));
                        tw.loadUrl((onYt ? "https://www.youtube.com/results?search_query=" : "https://www.google.com/search?q=") + Uri.encode(q));
                    } else push("nativeVoiceResult", org.json.JSONObject.quote(q));
                }
            }
            return;
        }
        if (req == 91) push("nativeDelResult", res == RESULT_OK ? "1" : "0");
    }
    /** Ẩn thanh điều hướng ngay và thêm một lần sau 0,4 giây (một số máy tự hiện lại thanh khi app vừa quay về). */
    private void hideBars() {
        immersive();
        getWindow().getDecorView().postDelayed(new Runnable() { public void run() { immersive(); } }, 400);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
    /** Đo phần màn hình thật nằm dưới vùng Desktop để Dock nổi phủ kín, không để hở giữa Dock và cửa sổ app. */
    /** Dock nổi vừa đổi vị trí/cỡ: đo lại. */
    static void dockMoved() { final MainActivity a = inst; if (a == null || a.root == null) return; a.runOnUiThread(new Runnable() { public void run() { a.reportDockGap(); } }); }
    private void reportDockGap() {
        try {
            int[] loc = new int[2]; root.getLocationOnScreen(loc);
            android.graphics.Point p = new android.graphics.Point(); getWindowManager().getDefaultDisplay().getRealSize(p);
            if (root.getHeight() > 0) LockService.setDesktopGap(p.y - (loc[1] + root.getHeight()));
            push("setDeskWallBox", deskBoxJson());
        } catch (Throwable t) { }
    }
    /** Vị trí/cỡ màn hình thật so với khung giao diện (CSS px): [trái, trên, rộng, cao] của màn hình thật tính từ góc khung.
     *  Hình nền Desktop phải phủ đúng khung này thì mới khớp với lát Dock/thanh menu (được cắt theo cỡ màn hình thật). */
    String deskBoxJson() {
        try {
            int[] loc = new int[2]; root.getLocationOnScreen(loc);
            android.graphics.Point p = new android.graphics.Point(); getWindowManager().getDefaultDisplay().getRealSize(p);
            float d = getResources().getDisplayMetrics().density;
            return "[" + (-loc[0] / d) + "," + (-loc[1] / d) + "," + (p.x / d) + "," + (p.y / d) + "]";
        } catch (Throwable t) { return "[0,0,0,0]"; }
    }
    static String deskBox() { final MainActivity a = inst; return a == null || a.root == null ? "[0,0,0,0]" : a.deskBoxJson(); }
    @Override protected void onResume() {
        super.onResume();
        maybeAskCompanion();
        powerRxStart();
        inFg = true; lastTouchAt = android.os.SystemClock.elapsedRealtime();
        imeUp = false; hideBars(); LockService.launcherResumed(); root.postDelayed(new Runnable() { public void run() { healA11y(false); } }, 2500); reportDockGap(); root.postDelayed(new Runnable() { public void run() { reportDockGap(); } }, 700); prefetchApps(); scanNewMedia(); startMediaWatch();
        for (java.util.Map.Entry<String, WebView> e : ext.entrySet()) { try { if (e.getValue().getVisibility() == View.VISIBLE) { e.getValue().onResume(); extHiddenAt.remove(e.getKey()); } } catch (Exception x) { } }
        web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null);
        if (!askingRuntime) root.post(new Runnable() { public void run() { setupNext(); } });
    }

    /* Đăng ký vào thư viện (MediaStore) các ảnh/video/nhạc mới nằm trong bộ nhớ nhưng chưa được hệ thống quét (vd. ảnh chụp màn hình của trình duyệt trên Android 9 trở xuống), rồi nạp lại danh sách */
    private volatile boolean scanBusy = false, scanAgain = false;
    private static final java.util.HashSet<String> MEDIA_EXT = new java.util.HashSet<String>(java.util.Arrays.asList(
        "jpg","jpeg","png","gif","webp","bmp","heic","heif","mp4","mkv","webm","3gp","mov","avi","m4v","mp3","m4a","aac","wav","ogg","flac","opus"));

    /* ===== Theo dõi ảnh / video / tệp mới: có tệp mới là quét một lần và nạp lại danh sách ===== */
    boolean mediaWatching = false;
    private final java.util.ArrayList<android.os.FileObserver> fileObs = new java.util.ArrayList<android.os.FileObserver>();
    final Runnable doScan = new Runnable() { public void run() { scanMedia(true); } };
    private final Runnable doReload = new Runnable() { public void run() { try { web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null); } catch (Exception e) { } } };
    private final android.database.ContentObserver mediaObs = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            /* Thư viện vừa thay đổi (ảnh mới, quét xong...): chỉ nạp lại danh sách, không quét thêm để tránh lặp */
            invalidatePhotos();
            reaper.removeCallbacks(doReload); reaper.postDelayed(doReload, 800);
        }
    };
    private void startMediaWatch() {
        if (mediaWatching) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            && !(Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
            && !(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)) return;
        mediaWatching = true;
        try {
            getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObs);
            getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObs);
            getContentResolver().registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObs);
            getContentResolver().registerContentObserver(MediaStore.Files.getContentUri("external"), true, mediaObs);
        } catch (Exception e) { }
        /* Tệp mới do app khác chép/tải/chụp vào mà hệ thống chưa kịp quét: theo dõi các thư mục chính */
        String[] subs = {"", "DCIM", "DCIM/Camera", "DCIM/Screenshots", "Pictures", "Pictures/Screenshots", "Pictures/Webandr", "Pictures/DesktopMini",
            "Download", "Movies", "Music", "Documents", "Pictures/Zalo", "Pictures/Messenger", "Pictures/Facebook"};
        for (File r : storageRoots()) for (String sub : subs) {
            File d = sub.isEmpty() ? r : new File(r, sub);
            if (!d.isDirectory()) continue;
            try {
                android.os.FileObserver fo = new android.os.FileObserver(d.getAbsolutePath(),
                    android.os.FileObserver.CREATE | android.os.FileObserver.MOVED_TO | android.os.FileObserver.CLOSE_WRITE) {
                    @Override public void onEvent(int ev, String path) {
                        if (path == null || path.startsWith(".")) return;
                        /* chờ tệp ghi xong rồi mới quét (gộp nhiều sự kiện liên tiếp thành một lần) */
                        reaper.removeCallbacks(doScan); reaper.postDelayed(doScan, 1500);
                    }
                };
                fo.startWatching(); fileObs.add(fo);
            } catch (Throwable e) { }
        }
    }
    private void stopMediaWatch() {
        if (!mediaWatching) return;
        mediaWatching = false;
        try { getContentResolver().unregisterContentObserver(mediaObs); } catch (Exception e) { }
        for (android.os.FileObserver fo : fileObs) { try { fo.stopWatching(); } catch (Exception e) { } }
        fileObs.clear();
        reaper.removeCallbacks(doScan); reaper.removeCallbacks(doReload);
    }
    /* Bộ nhớ đệm ảnh nhỏ: RAM (tối đa ~10 MB) + ổ đĩa (cacheDir/thumbs, tự dọn khi quá nhiều) */
    final android.util.LruCache<String, String> thumbMem = new android.util.LruCache<String, String>(10 * 1024 * 1024) {
        @Override protected int sizeOf(String k, String v) { return v.length() * 2; }
    };
    private void trimThumbs() {
        try {
            File[] fs = new File(getCacheDir(), "thumbs").listFiles();
            if (fs == null || fs.length <= 4000) return;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() { @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); } });
            for (int i = 0; i < fs.length - 3000; i++) fs[i].delete();
        } catch (Exception e) { }
    }
    void scanNewMedia() { scanMedia(false); }
    /** Gốc các ổ lưu trữ: bộ nhớ trong + thẻ nhớ ngoài (nếu có). */
    private java.util.ArrayList<File> storageRoots() {
        java.util.ArrayList<File> roots = new java.util.ArrayList<File>();
        try { roots.add(Environment.getExternalStorageDirectory()); } catch (Exception e) { }
        try {
            File[] fs = getExternalFilesDirs(null);
            if (fs != null) for (File f : fs) {
                if (f == null) continue;
                String ap = f.getAbsolutePath(); int k = ap.indexOf("/Android/data/");
                if (k > 0) { File r = new File(ap.substring(0, k)); if (r.isDirectory() && !roots.contains(r)) roots.add(r); }
            }
        } catch (Exception e) { }
        return roots;
    }
    /** Đường dẫn mọi ảnh/video/nhạc mà thư viện (MediaStore) đã biết. */
    private java.util.HashSet<String> knownMediaPaths() {
        java.util.HashSet<String> known = new java.util.HashSet<String>();
        try {
            Cursor c = getContentResolver().query(MediaStore.Files.getContentUri("external"),
                new String[]{MediaStore.MediaColumns.DATA},
                MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1,2,3)", null, null);
            if (c != null) { try { while (c.moveToNext()) { String d = c.getString(0); if (d != null) known.add(d); } } finally { c.close(); } }
        } catch (Exception e) { }
        return known;
    }
    /* Tìm mọi tệp ảnh/video/nhạc nằm trong bộ nhớ nhưng thư viện chưa biết (so với MediaStore, không dựa vào ngày sửa tệp) rồi nhờ hệ thống quét; xong thì nạp lại danh sách. */
    volatile String photosCache = null; volatile int photosGen = 0;
    void invalidatePhotos() { photosGen++; photosCache = null; }
    private final java.util.HashSet<String> triedMedia = new java.util.HashSet<String>();
    private static final String[] PRIO_DIRS = {"DCIM", "Pictures", "Download", "Movies", "Music", "Documents"};
    private void reloadUi() {
        invalidatePhotos();
        runOnUiThread(new Runnable() { public void run() { try { web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null); } catch (Exception e) { } } });
    }
    private void newestFirst(java.util.ArrayList<File> l) {
        java.util.Collections.sort(l, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
    }
    /** Nhờ hệ thống quét một lô tệp (tối đa 5000, mới nhất trước) và chờ quét xong. */
    private void scanBatch(java.util.ArrayList<File> files, java.util.HashSet<String> done) {
        int n = Math.min(files.size(), 5000); if (n == 0) return;
        final String[] paths = new String[n];
        for (int i = 0; i < n; i++) {
            File f = files.get(i); paths[i] = f.getAbsolutePath(); done.add(paths[i]);
            synchronized (triedMedia) { triedMedia.add(paths[i] + "|" + f.lastModified()); }
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(n);
        android.media.MediaScannerConnection.scanFile(MainActivity.this, paths, null, new android.media.MediaScannerConnection.OnScanCompletedListener() {
            @Override public void onScanCompleted(String path, Uri uri) { latch.countDown(); }
        });
        try { latch.await(180, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { }
    }
    /* Tìm mọi tệp ảnh/video/nhạc nằm trong bộ nhớ nhưng thư viện chưa biết (so với MediaStore, không dựa vào ngày sửa tệp) rồi nhờ hệ thống quét.
       Hai lượt: thư mục ảnh/video chính quét trước và hiện ngay, phần còn lại của bộ nhớ quét sau. */
    void scanMedia(final boolean force) {
        if (scanBusy) { scanAgain = true; return; }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            && !(Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
            && !(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)) return;
        scanBusy = true; scanAgain = false;
        new Thread(new Runnable() { public void run() {
            try {
                trimThumbs();
                final java.util.HashSet<String> known = knownMediaPaths();
                final java.util.HashSet<String> done = new java.util.HashSet<String>();
                java.util.ArrayList<File> roots = storageRoots();
                java.util.ArrayList<File> f1 = new java.util.ArrayList<File>();
                for (File r : roots) for (String sub : PRIO_DIRS) { File d = new File(r, sub); if (d.isDirectory()) walkMedia(d, known, f1, 1); }
                newestFirst(f1);
                if (!f1.isEmpty()) { scanBatch(f1, done); reloadUi(); }
                java.util.ArrayList<File> f2 = new java.util.ArrayList<File>();
                for (File r : roots) walkMedia(r, known, f2, 0);
                java.util.Iterator<File> it = f2.iterator();
                while (it.hasNext()) if (done.contains(it.next().getAbsolutePath())) it.remove();
                newestFirst(f2);
                if (!f2.isEmpty()) { scanBatch(f2, done); reloadUi(); }
            } catch (Throwable e) { }
            finally {
                scanBusy = false;
                if (scanAgain) runOnUiThread(new Runnable() { public void run() { scanMedia(true); } });
            }
        } }).start();
    }
    private void walkMedia(File d, java.util.HashSet<String> known, java.util.ArrayList<File> out, int depth) {
        if (d == null || depth > 10 || out.size() > 20000) return;
        File[] fs = d.listFiles(); if (fs == null) return;
        for (File f : fs) { if (f.getName().equals(".nomedia")) return; }
        for (File f : fs) {
            String nm = f.getName();
            if (nm.startsWith(".")) continue;
            if (f.isDirectory()) { if (depth == 0 && nm.equals("Android")) continue; walkMedia(f, known, out, depth + 1); }
            else {
                int i = nm.lastIndexOf('.');
                if (i > 0 && f.length() > 0 && MEDIA_EXT.contains(nm.substring(i + 1).toLowerCase(java.util.Locale.ROOT)) && !known.contains(f.getAbsolutePath())) {
                    boolean tried; synchronized (triedMedia) { tried = triedMedia.contains(f.getAbsolutePath() + "|" + f.lastModified()); }
                    if (!tried) out.add(f);
                }
            }
        }
    }
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        /* Đang xem video toàn màn hình mà bấm app khác / Launchpad / thanh menu ở Dock nổi: thoát toàn màn hình trước để app mới hiện lên TRÊN CÙNG (không bị video che) */
        extHideFull();
        if (i != null && i.getBooleanExtra("openMS", false)) { web.evaluateJavascript("window.nativeMS&&window.nativeMS()", null); return; } /* vuốt lên từ thanh Desktop */
        String mc = i == null ? null : i.getStringExtra("mbClick");
        if (mc != null) {   /* chạm vào thanh menu nổi: bấm nút tương ứng của thanh menu HTML */
            final String id = mc;
            web.postDelayed(new Runnable() { public void run() { runMenuClick(id); } }, i.getBooleanExtra("mbFront", false) ? 0 : 300);
            return;
        }
        String of = i == null ? null : i.getStringExtra("openDownloaded");
        if (of != null) {   /* Trình duyệt (app phụ) nhờ mở tệp vừa tải bằng app Tải xuống */
            final String fp = of, fn = i.getStringExtra("openDownloadedName");
            web.postDelayed(new Runnable() { public void run() { web.evaluateJavascript("window.nativeOpenDownloaded&&window.nativeOpenDownloaded(" + JSONObject.quote(fp) + "," + JSONObject.quote(fn == null ? "" : fn) + ")", null); } }, 250);
            return;
        }
        String oa = i == null ? null : i.getStringExtra("openApp");
        if (oa != null) web.evaluateJavascript("window.nativeOpenApp&&window.nativeOpenApp(" + JSONObject.quote(oa) + "," + i.getBooleanExtra("openToggle", false) + ")", null);
        else web.evaluateJavascript("window.nativeHome&&window.nativeHome()", null);
    }
    void runMenuClick(String id) { web.evaluateJavascript("window.nativeMenuClick&&window.nativeMenuClick(" + JSONObject.quote(id) + ")", null); }
    @Override public void onBackPressed() { if (extFullView != null) { extHideFull(); return; } web.evaluateJavascript("window.nativeBack&&window.nativeBack()", null); }

    /** Mở trình duyệt mặc định của app ("Duyệt web"); có url thì mở thẳng trang đó trong thẻ mới. */
    void openWeb(String url) {
        Intent i = new Intent(this, WebBrowserActivity.class);
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) i.setData(Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { }
    }

    /** Cầu nối nút mic cho cửa sổ web nhúng (YouTube, Duyệt web): nhớ cửa sổ nào bấm để trả kết quả đúng nơi. */

    /** Chuẩn hóa đường dẫn từ trang web trước khi thao tác tệp: lấy đường dẫn thật (xóa ../ và liên kết tượng trưng),
     *  chỉ cho phép trong bộ nhớ ngoài (/storage, /mnt, thẻ nhớ), chặn thư mục dữ liệu riêng của app (cookie, đăng nhập, cơ sở dữ liệu). Sai thì trả null. */
    File safeFile(String path) {
        if (path == null || path.length() == 0 || path.indexOf('\0') >= 0) return null;
        try {
            File f = new File(path).getCanonicalFile();
            String c = f.getPath();
            String data = new File(getApplicationInfo().dataDir).getCanonicalPath();
            if (c.equals(data) || c.startsWith(data + "/") || c.startsWith("/data/") || c.equals("/data")) return null;
            String ext = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (c.equals(ext) || c.startsWith(ext + "/") || c.startsWith("/storage/") || c.startsWith("/mnt/")) return f;
        } catch (Exception e) { }
        return null;
    }

    private class WebVoice {
        final String k;
        WebVoice(String k) { this.k = k; }
        /** Chỉ nhận lệnh từ trang https đang hiển thị trong đúng WebView của mình (YouTube thì phải đúng tên miền YouTube), chặn trang khác lợi dụng cầu để bật mic. */
        @JavascriptInterface public void startVoiceSearch() { runOnUiThread(new Runnable() { public void run() {
            WebView wv = ext.get(k); String u = wv == null ? null : wv.getUrl();
            if (u == null || !u.startsWith("https://")) return;
            if ("youtube".equals(k) && !ytAllowed(Uri.parse(u))) return;
            voiceKey = k; launchVoiceSearch(); } }); }
    }

    /** Nút mic của thanh tìm kiếm Google trên Desktop: xin quyền micro rồi mở bộ nhận dạng giọng nói của Android; kết quả trả về JS qua nativeVoiceResult(text). */
    void launchVoiceSearch() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askingRuntime = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERM_VOICE);
            return;
        }
        Intent it = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag());
        it.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Nói để tìm kiếm trên Google");
        try { startActivityForResult(it, REQ_VOICE); }
        catch (Exception e) { android.widget.Toast.makeText(this, "Máy chưa có ứng dụng nhận dạng giọng nói (cần ứng dụng Google). Hãy cài hoặc cập nhật Google từ CH Play", android.widget.Toast.LENGTH_LONG).show(); }
    }

    /* ================= Nút AI giọng nói (góc trái dưới) ================= */
    private VoiceAi aiVoice;
    private static final int PERM_AI = 79;
    boolean aiAvailable() { if (aiVoice == null) aiVoice = new VoiceAi(this); return aiVoice.available(); }
    /** Bắt đầu nghe lệnh: xin quyền micro nếu chưa có; kết quả đẩy về giao diện qua window.aiVoice(...). */
    void aiListen() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askingRuntime = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERM_AI);
            push("aiVoice", "{\"t\":\"error\",\"code\":" + android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS + ",\"ask\":true}");
            return;
        }
        if (aiVoice == null) aiVoice = new VoiceAi(this);
        if (!aiVoice.available()) { push("aiVoice", "{\"t\":\"error\",\"code\":-1}"); return; }
        aiVoice.start();
    }
    void aiStop() { if (aiVoice != null) aiVoice.stop(); }
    void aiCancel() { if (aiVoice != null) aiVoice.cancel(); }

    /** Mở app con "YouTube". */
    void openYt() {
        Intent i = new Intent(this, YouTubeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { }
    }

    /* ================= Chụp ảnh màn hình (nút vuông góc phải dưới) ================= */
    private void shotDone(final boolean ok) {
        runOnUiThread(new Runnable() { public void run() {
            if (web != null) web.evaluateJavascript("window.shotDone&&window.shotDone(" + ok + ")", null);
        } });
    }

    /** Chụp cả cửa sổ (kể cả trang web nhúng/Gecko): PixelCopy từ Android 8, máy cũ hơn thì vẽ lại cây View. */
    void captureScreen() {
        final View dv = getWindow().getDecorView();
        final int w = dv.getWidth(), h = dv.getHeight();
        if (w <= 0 || h <= 0) { shotDone(false); return; }
        final Bitmap bm;
        try { bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); }
        catch (Throwable t) { shotDone(false); return; }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                android.view.PixelCopy.request(getWindow(), bm, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                    @Override public void onPixelCopyFinished(int r) {
                        if (r == android.view.PixelCopy.SUCCESS) saveShotAsync(bm); else shotDone(false);
                    }
                }, new android.os.Handler(android.os.Looper.getMainLooper()));
                return;
            } catch (Throwable t) { /* rơi xuống cách vẽ lại */ }
        }
        try { dv.draw(new Canvas(bm)); } catch (Throwable t) { shotDone(false); return; }
        saveShotAsync(bm);
    }

    private void saveShotAsync(final Bitmap bm) {
        new Thread(new Runnable() { public void run() {
            boolean ok = false;
            try { ok = saveShot(bm); } catch (Throwable t) { }
            try { bm.recycle(); } catch (Throwable t) { }
            shotDone(ok);
        } }).start();
    }

    /** Lưu PNG vào Pictures/Screenshots (ứng dụng Ảnh tự xếp vào mục "Ảnh chụp màn hình"). */
    private boolean saveShot(Bitmap bm) throws Exception {
        String nm = "Screenshot_" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".png";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, nm);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return false;
            OutputStream os = getContentResolver().openOutputStream(u);
            boolean ok = bm.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.close();
            v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(u, v, null, null);
            return ok;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"); dir.mkdirs();
        File f = new File(dir, nm);
        FileOutputStream os2 = new FileOutputStream(f);
        boolean ok2 = bm.compress(Bitmap.CompressFormat.PNG, 100, os2);
        os2.close();
        if (!ok2) return false;
        try {
            ContentValues v2 = new ContentValues();
            v2.put(MediaStore.Images.Media.DISPLAY_NAME, nm);
            v2.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v2.put(MediaStore.Images.Media.DATA, f.getAbsolutePath());
            getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v2);
        } catch (Exception e) { sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f))); }
        return true;
    }

    /* ================= Tìm tệp trong máy (Spotlight) ================= */
    JSONArray findFiles(String q) {
        JSONArray arr = new JSONArray();
        q = q == null ? "" : q.trim();
        if (q.length() < 2) return arr;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            && !(Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
            && !(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)) return arr;
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        try {
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            Cursor c = getContentResolver().query(MediaStore.Files.getContentUri("external"),
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? ESCAPE '\\' AND " + MediaStore.MediaColumns.MIME_TYPE + " IS NOT NULL",
                new String[]{like}, MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c != null) {
                try {
                    while (c.moveToNext() && arr.length() < 30) {
                        String path = c.getString(1);
                        if (path == null || path.contains("/.") || path.contains("/Android/data/") || path.contains("/Android/obb/") || !seen.add(path)) continue;
                        File f = new File(path);
                        if (!f.isFile()) continue;
                        JSONObject o = new JSONObject();
                        o.put("n", c.getString(0)); o.put("p", path); o.put("s", c.getLong(2)); o.put("t", c.getLong(3));
                        arr.put(o);
                    }
                } finally { c.close(); }
            }
        } catch (Exception e) { }
        /* MediaStore chưa lập chỉ mục tệp nào khớp: duyệt thư mục (chỉ khi được cấp quyền mọi tệp) */
        if (arr.length() == 0 && (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())) {
            try {
                final String lq = q.toLowerCase(java.util.Locale.ROOT);
                java.util.ArrayDeque<File> dq = new java.util.ArrayDeque<File>();
                java.util.ArrayDeque<Integer> dp = new java.util.ArrayDeque<Integer>();
                dq.add(Environment.getExternalStorageDirectory()); dp.add(0);
                int visited = 0; long end = System.currentTimeMillis() + 3500;
                while (!dq.isEmpty() && arr.length() < 30 && visited < 15000 && System.currentTimeMillis() < end) {
                    File d = dq.poll(); int depth = dp.poll();
                    File[] l = d.listFiles(); if (l == null) continue;
                    for (File f : l) {
                        visited++;
                        String nm = f.getName();
                        if (nm.startsWith(".")) continue;
                        if (f.isDirectory()) { if (depth < 6 && !nm.equals("Android")) { dq.add(f); dp.add(depth + 1); } }
                        else if (nm.toLowerCase(java.util.Locale.ROOT).contains(lq) && arr.length() < 30) {
                            JSONObject o = new JSONObject();
                            o.put("n", nm); o.put("p", f.getAbsolutePath()); o.put("s", f.length()); o.put("t", f.lastModified() / 1000);
                            arr.put(o);
                        }
                    }
                }
            } catch (Exception e) { }
        }
        return arr;
    }

    /* ================= App phụ "Trình duyệt + Ổ mềm" (Gecko), tách riêng khỏi WebView giao diện =================
     * Hai app này dùng lõi Gecko (Firefox) nên chạy trong app riêng (com.desktopmini.web); app chính chỉ dùng WebView hệ thống.
     * APK app phụ đóng gói sẵn trong assets/web/web.apk, app chính hỏi cài (hoặc cập nhật) ngay lần mở đầu tiên. Biểu tượng vẫn nằm trên Dock/Launchpad như cũ. */
    static final String WEB_PKG = "com.desktopmini.tvweb";
    static final String WEB_CLS = "com.desktopmini.web"; // tên lớp (namespace) giữ nguyên, chỉ applicationId đổi
    private boolean companionAsked;
    volatile boolean htmlEditing = false;

    void toastLong(final String msg) {
        runOnUiThread(new Runnable() { public void run() { android.widget.Toast.makeText(MainActivity.this, msg, android.widget.Toast.LENGTH_LONG).show(); } });
    }
    private int myVersion() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Throwable t) { return 0; } }
    /** Số phiên bản app phụ đã cài; -1 nếu chưa cài. */
    int companionVersion() { try { return getPackageManager().getPackageInfo(WEB_PKG, 0).versionCode; } catch (Throwable t) { return -1; } }
    private boolean hasBundledCompanion() {
        try { String[] l = getAssets().list("web"); if (l != null) for (String n : l) if (n.equals("web.apk")) return true; } catch (Throwable t) { }
        return false;
    }

    /** Mở "Trình duyệt" (which = "browser", arg = địa chỉ) hoặc "Ổ mềm" (which = "omem", arg = hồ sơ anh/video/tep). Chưa cài app phụ thì hỏi cài. */
    void openCompanion(String which, String arg, boolean upload) {
        if (companionVersion() < 0) { toastLong("Chưa cài app phụ Trình duyệt + Ổ mềm. Hãy cài file web-release.apk trong cùng bản build."); return; }
        boolean om = "omem".equals(which);
        Intent i = new Intent();
        i.setClassName(WEB_PKG, WEB_CLS + (om ? ".OmemActivity" : ".BrowserActivity"));
        if (om) { if (arg != null && arg.length() > 0) i.putExtra("profile", arg); i.putExtra("upload", upload); }
        else if (arg != null && (arg.startsWith("http://") || arg.startsWith("https://"))) i.putExtra("url", arg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Throwable e) { toastLong("Không mở được app phụ (" + e.getClass().getSimpleName() + "), hãy cài lại web-release.apk."); }
    }

    /** Lần mở đầu của mỗi bản app: chưa cài hoặc app phụ cũ hơn thì hỏi cài (mỗi bản chỉ hỏi một lần; bấm biểu tượng sẽ hỏi lại). */
    private void maybeAskCompanion() {
        if (companionAsked || !hasBundledCompanion()) return;
        companionAsked = true;
        if (companionVersion() >= myVersion()) return;
        android.content.SharedPreferences sp = getSharedPreferences("dm_companion", MODE_PRIVATE);
        if (sp.getInt("asked", 0) == myVersion()) return;
        sp.edit().putInt("asked", myVersion()).apply();
        root.postDelayed(new Runnable() { public void run() { askInstallCompanion(); } }, 3500);
    }

    private void askInstallCompanion() {
        if (!hasBundledCompanion()) { toastLong("Bản này chưa kèm app phụ Trình duyệt / Ổ mềm."); return; }
        final boolean update = companionVersion() >= 0;
        try {
            new android.app.AlertDialog.Builder(this)
                .setTitle(update ? "Cập nhật app phụ" : "Cài app phụ Trình duyệt + Ổ mềm")
                .setMessage("\"Trình duyệt\" và \"Ổ mềm\" chạy trong một app phụ riêng, không dùng chung WebView với giao diện chính nên hết xung đột/văng. App phụ đi kèm sẵn trong Desktop Mini.\n\nBấm \"Cài ngay\" rồi xác nhận cài đặt của Android (cần cho phép cài từ nguồn này nếu máy hỏi). Biểu tượng vẫn nằm trên Dock/Launchpad như cũ.")
                .setPositiveButton("Cài ngay", new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int w) { installCompanion(); } })
                .setNegativeButton("Để sau", null)
                .show();
        } catch (Throwable e) { }
    }

    private void installCompanion() {
        final java.io.File dir = new java.io.File(getCacheDir(), "shared"); dir.mkdirs();
        final java.io.File f = new java.io.File(dir, "web.apk");
        if (dir.getUsableSpace() < 300L * 1024 * 1024) { toastLong("Không đủ bộ nhớ trống (cần khoảng 300 MB) để cài app phụ. Hãy dọn bớt rồi thử lại."); return; }
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("Đang chuẩn bị app phụ, vui lòng chờ…");
        pd.setCancelable(false);
        try { pd.show(); } catch (Throwable t) { }
        new Thread(new Runnable() { public void run() {
            boolean ok = false;
            java.io.File tmp = new java.io.File(dir, "web.apk.tmp");
            try {
                java.io.InputStream in = getAssets().open("web/web.apk");
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                try { byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); out.flush(); ok = tmp.length() > 1024 * 1024; }
                finally { try { out.close(); } catch (Throwable t) { } try { in.close(); } catch (Throwable t) { } }
                if (ok) { f.delete(); ok = tmp.renameTo(f); }
            } catch (Throwable e) { ok = false; }
            if (!ok) { try { tmp.delete(); } catch (Throwable t) { } }
            final boolean done = ok;
            runOnUiThread(new Runnable() { public void run() {
                try { pd.dismiss(); } catch (Throwable t) { }
                if (done) launchApkInstall(f, false); else toastLong("Không chuẩn bị được app phụ.");
            } });
        } }, "companion-install").start();
    }

    /** Mở app con "Gõ văn bản". */
    void openTy() {
        Intent i = new Intent(this, TypingActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { }
    }

    void go(Intent i) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(i); } catch (Exception e) { } }

    /* ================= Trình duyệt nhúng trong cửa sổ: YouTube, Duyệt web, Gmail, Drive, Zalo, Facebook ================= */
    private static final String[] AD_HOSTS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
        "propellerads.com", "adsterra.com", "exoclick.com", "pubmatic.com", "rubiconproject.com",
        "criteo.com", "scorecardresearch.com", "moatads.com", "amazon-adsystem.com"
    };
    WordHost wordHost;
    private static final int REQ_WORD = 1002;
    private static final int REQ_EXTFILE = 1003;
    private static final int REQ_VOICE = 1004;
    private static final int PERM_VOICE = 78;
    /** Cửa sổ web nhúng (khóa trong ext) đang chờ kết quả giọng nói; null = thanh tìm kiếm Google trên Desktop. */
    String voiceKey = null;
    private android.webkit.ValueCallback<Uri[]> extFileCb;
    private static final String[] MS_HOSTS = {
        "office.com", "office365.com", "microsoft365.com", "microsoft.com", "microsoftonline.com", "microsoftonline-p.com",
        "live.com", "sharepoint.com", "onedrive.com", "1drv.ms", "office.net", "msauth.net", "msftauth.net", "msocdn.com",
        "windows.net", "azureedge.net", "sway.com", "officeapps.live.com", "cloud.microsoft", "msn.com", "bing.com"
    };
    /** Khóa các app "Office online": chỉ cho vào các trang của Microsoft (Office, OneDrive, SharePoint, đăng nhập). */
    private static boolean msAllowed(Uri u) {
        String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (sc.equals("about") || sc.equals("blob") || sc.equals("data")) return true;
        if (!sc.equals("https") && !sc.equals("http")) return false;
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (h.length() == 0) return false;
        for (String d : MS_HOSTS) if (hostIs(h, d)) return true;
        return false;
    }
    private View extFullView;
    private android.webkit.WebChromeClient.CustomViewCallback extFullCb;

    private static boolean hostIs(String h, String d) { return h.equals(d) || h.endsWith("." + d); }
    private static boolean isAdHost(String h) { for (String d : AD_HOSTS) if (hostIs(h, d)) return true; return false; }
    /** Khóa "youtube": chỉ cho vào youtube.com / youtu.be và trang đăng nhập/đồng ý của Google. */
    private static boolean ytAllowed(Uri u) {
        String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (sc.equals("about")) return true;
        if (!sc.equals("https") && !sc.equals("http")) return false;
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (h.length() == 0) return false;
        if (hostIs(h, "youtube.com") || h.equals("youtu.be") || hostIs(h, "youtube-nocookie.com")) {
            String p = u.getPath() == null ? "" : u.getPath();
            return !p.startsWith("/redirect");   // cổng đưa người xem ra web ngoài
        }
        return h.startsWith("accounts.google.") || h.startsWith("consent.google.");
    }
    private long extToastAt = 0;
    private void extToast(String m) {
        long now = System.currentTimeMillis();
        if (now - extToastAt < 1500) return;
        extToastAt = now;
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show();
    }
    /** Báo cho giao diện HTML địa chỉ/trạng thái hiện tại của WebView nhúng (thanh địa chỉ, nút lùi/tiến). */
    private void extNav(String key, WebView v, int progress) {
        if (web == null || v == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("url", v.getUrl() == null ? "" : v.getUrl()); o.put("back", v.canGoBack()); o.put("fwd", v.canGoForward()); o.put("p", progress);
            web.evaluateJavascript("window.nativeExtNav&&window.nativeExtNav(" + JSONObject.quote(key) + "," + o.toString() + ")", null);
        } catch (Exception e) { }
    }
    /** Dock nổi / thanh menu nổi gọi TRƯỚC khi chuyển app: thoát video toàn màn hình ngay (không chờ onNewIntent). */
    static void leaveFullscreen() {
        final MainActivity a = inst; if (a == null) return;
        a.runOnUiThread(new Runnable() { public void run() { try { a.extHideFull(); } catch (Throwable t) { } try { if (a.web != null) a.web.evaluateJavascript("document.fullscreenElement&&document.exitFullscreen&&document.exitFullscreen()", null); } catch (Throwable t) { } } });
    }
    void extHideFull() {
        if (extFullView == null) return;
        try { root.removeView(extFullView); } catch (Exception e) { }
        extFullView = null; LockService.setVideoFull(false);
        if (extFullCb != null) { try { extFullCb.onCustomViewHidden(); } catch (Exception e) { } extFullCb = null; }
    }

    /** Tối đa số trang web nhúng (Zalo, Facebook, Drive, YouTube, Office...) sống cùng lúc, không tính "Gõ văn bản". Mở thêm thì trang ẩn lâu nhất bị đóng trước. */
    private static final int EXT_MAX = 3;
    private void capExt(String keep) {
        if (extFullView != null) return;
        try { if (((android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive()) return; } catch (Exception e) { }
        while (true) {
            int n = 0; String oldest = null; long ot = Long.MAX_VALUE;
            for (java.util.Map.Entry<String, WebView> e : ext.entrySet()) {
                String k = e.getKey(); if ("word".equals(k)) continue;
                n++;
                if (k.equals(keep) || e.getValue().getVisibility() == View.VISIBLE) continue;
                Long t = extHiddenAt.get(k); long tt = t == null ? 0L : t;
                if (tt < ot) { ot = tt; oldest = k; }
            }
            if (n < EXT_MAX || oldest == null) return;
            WebView v = ext.remove(oldest); zooms.remove(oldest); extHiddenAt.remove(oldest);
            if (v != null) { try { v.loadUrl("about:blank"); root.removeView(v); v.destroy(); } catch (Exception e) { } }
        }
    }

    BrowserHost browserGet(String url) {
        if (browserHost == null) {
            browserHost = new BrowserHost(this, new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                @Override public kotlin.Unit invoke() {
                    if (web != null) web.evaluateJavascript("window.nativeCloseApp&&window.nativeCloseApp('webbrowser')", null);
                    return kotlin.Unit.INSTANCE;
                }
            });
            browserHost.start(url != null && url.startsWith("http") ? url : null);
            View v = browserHost.getRoot();
            v.setVisibility(View.GONE);
            root.addView(v, new FrameLayout.LayoutParams(1, 1));
        }
        return browserHost;
    }
    void browserHide() { if (browserHost != null) { try { browserHost.getRoot().setVisibility(View.GONE); } catch (Exception e) { } } }

    private boolean gmailFixed = false;
    /** Xoá cookie của các miền Google (không đụng Zalo/Facebook dùng chung kho cookie). */
    private void clearGoogleCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            String[] urls = { "https://mail.google.com/", "https://accounts.google.com/", "https://www.google.com/", "https://google.com/", "https://myaccount.google.com/" };
            String[] doms = { "", "; Domain=.google.com", "; Domain=google.com", "; Domain=mail.google.com", "; Domain=accounts.google.com" };
            for (String u : urls) {
                String c = cm.getCookie(u); if (c == null) continue;
                for (String part : c.split(";")) {
                    int i = part.indexOf('='); String n = (i > 0 ? part.substring(0, i) : part).trim(); if (n.isEmpty()) continue;
                    for (String d : doms) cm.setCookie(u, n + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/" + d);
                }
            }
            cm.flush();
        } catch (Throwable t) { }
    }
    WebView extGet(final String key, String url) {
        WebView v = ext.get(key);
        if (v == null && !"word".equals(key)) capExt(key);
        if (v == null && "word".equals(key)) {
            /* "Gõ văn bản": trình soạn thảo kiểu Word chạy offline trong cửa sổ (phần lõi dùng chung ở WordHost) */
            v = new WebView(this);
            wordHost = new WordHost(this, v, REQ_WORD, new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                @Override public kotlin.Unit invoke() {
                    if (web != null) web.evaluateJavascript("window.nativeCloseApp&&window.nativeCloseApp('typing')", null);
                    return kotlin.Unit.INSTANCE;
                }
            });
            ZoomHelper zw = ZoomHelper.attach(this, v, key);
            zooms.put(key, zw);
            wordHost.setZoom(zw);
            wordHost.setup();
            v.setVisibility(View.GONE);
            root.addView(v, new FrameLayout.LayoutParams(1, 1));
            v.loadUrl(url);
            ext.put(key, v);
            return v;
        }
        if (v == null) {
            final boolean yt = "youtube".equals(key), br = "web".equals(key), ms = key.startsWith("ms-");
            v = new WebView(this);
            final WebView wv = v;
            final ZoomHelper zm = ZoomHelper.attach(this, v, key);
            zooms.put(key, zm);
            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
            if (yt || br || ms) {
                s.setDatabaseEnabled(true);
                s.setMediaPlaybackRequiresUserGesture(false);
                s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false);
                s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            }
            if (yt) {
                /* UA như Chrome thật (bỏ "; wv" và "Version/4.0") + không gửi X-Requested-With: đăng nhập Google dễ hơn */
                s.setUserAgentString(WebSettings.getDefaultUserAgent(this).replace("; wv)", ")").replace(" Version/4.0", ""));
                try {
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                        androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, new java.util.HashSet<String>());
                } catch (Exception e) { }
                try {
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        java.util.HashSet<String> origins = new java.util.HashSet<String>();
                        origins.add("https://*.youtube.com"); origins.add("https://youtube.com");
                        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(v, "window.__wbAdSkip=true;" + YT_KEEP_JS + YouTubeActivityKt.YT_ONLY_AD_JS, origins);
                    }
                } catch (Exception e) { }
            } else {
                s.setUserAgentString(("gmail".equals(key) || "drive".equals(key) || "zalo".equals(key) || "facebook".equals(key) || br || ms) ? UA_DESK : UA);
                if ("gmail".equals(key)) {
                    /* Gmail: UA máy tính nhưng đúng phiên bản Chrome thật của máy (UA giả Chrome 124 làm Google chuyển hướng vòng lặp), bỏ X-Requested-With */
                    try {
                        String du = WebSettings.getDefaultUserAgent(this);
                        du = du.replaceAll("\\(Linux; Android[^)]*\\)", "(X11; Linux x86_64)").replace(" Mobile", "").replace(" Version/4.0", "");
                        if (du.contains("Chrome/")) s.setUserAgentString(du);
                    } catch (Exception e) { }
                    try {
                        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                            androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, new java.util.HashSet<String>());
                    } catch (Exception e) { }
                }
                if (ms) { /* Microsoft: bỏ X-Requested-With để trang đăng nhập không coi là "ứng dụng không an toàn" */
                    s.setJavaScriptCanOpenWindowsAutomatically(true);   // đăng nhập Microsoft/Google có thể mở cửa sổ phụ
                    try {
                        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                            androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, new java.util.HashSet<String>());
                    } catch (Exception e) { }
                }
            }
            CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(v, true);
            if (yt || br) v.addJavascriptInterface(new WebVoice(key), "AndroidVoice");
            v.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    if (!(yt || br || ms) || !req.isForMainFrame()) return false;
                    Uri u = req.getUrl();
                    if (yt) {
                        if (ytAllowed(u)) return false;
                        extToast("Ứng dụng này chỉ mở được YouTube"); return true;   // không mở link ngoài
                    }
                    if (ms) {
                        if (msAllowed(u)) return false;
                        extToast("Ứng dụng này chỉ mở được Microsoft Office"); return true;   // không mở link ngoài
                    }
                    String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                    if (sc.equals("http") || sc.equals("https") || sc.equals("about") || sc.equals("file")) return false;
                    if (sc.equals("intent") || sc.equals("market")) { extToast("Đã chặn liên kết mở ứng dụng khác"); return true; }
                    return false;
                }
                @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    if (!(yt || br || ms) || req.getUrl() == null || req.getUrl().getHost() == null) return null;
                    String h = req.getUrl().getHost().toLowerCase();
                    if (isAdHost(h)) return new android.webkit.WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
                    if (yt && req.isForMainFrame() && !ytAllowed(req.getUrl())) {   // chuyển hướng từ máy chủ ra ngoài YouTube
                        runOnUiThread(new Runnable() { public void run() { extToast("Ứng dụng này chỉ mở được YouTube"); } });
                        String html = "<meta name=viewport content='width=device-width,initial-scale=1'><body style='background:#111;color:#eee;font:16px sans-serif;text-align:center;padding:48px 20px'>Ứng dụng này chỉ mở được YouTube.<br><br>Bấm Quay lại để trở về.</body>";
                        try { return new android.webkit.WebResourceResponse("text/html", "utf-8", new java.io.ByteArrayInputStream(html.getBytes("UTF-8"))); } catch (Exception e) { return null; }
                    }
                    if (ms && req.isForMainFrame() && !msAllowed(req.getUrl())) {   // chuyển hướng từ máy chủ ra ngoài Office
                        runOnUiThread(new Runnable() { public void run() { extToast("Ứng dụng này chỉ mở được Microsoft Office"); } });
                        String html = "<meta name=viewport content='width=device-width,initial-scale=1'><body style='font:16px sans-serif;text-align:center;padding:48px 20px'>Ứng dụng này chỉ mở được Microsoft Office.<br><br>Bấm Quay lại để trở về.</body>";
                        try { return new android.webkit.WebResourceResponse("text/html", "utf-8", new java.io.ByteArrayInputStream(html.getBytes("UTF-8"))); } catch (Exception e) { return null; }
                    }
                    return null;
                }
                @Override public void onScaleChanged(WebView view, float oldScale, float newScale) { zm.onScaleChanged(oldScale, newScale); }
                @Override public void onPageStarted(WebView view, String u, android.graphics.Bitmap f) { zm.onPageStarted(); if (yt || br || ms) extNav(key, view, 10); }
                @Override public void doUpdateVisitedHistory(WebView view, String u, boolean r) { if (yt || br || ms) extNav(key, view, 50); }
                @Override public void onPageFinished(WebView view, String u) {
                    zm.onPageFinished(view);
                    if (yt || br || ms) {
                        /* WebView cũ không hỗ trợ script lúc bắt đầu tải: chèn sau khi tải xong (script tự chống chạy 2 lần) */
                        try { String h = Uri.parse(u == null ? "" : u).getHost(); if (h != null && hostIs(h.toLowerCase(), "youtube.com")) view.evaluateJavascript("window.__wbAdSkip=true;" + YT_KEEP_JS + YouTubeActivityKt.YT_ONLY_AD_JS, null); } catch (Exception e) { }
                        if (u != null && u.startsWith("http") && (yt || br)) view.evaluateJavascript(YouTubeActivityKt.VOICE_HOOK_JS, null);   // nối nút mic
                        extNav(key, view, 100);
                    }
                    CookieManager.getInstance().flush();
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                    if ("gmail".equals(key) && req.isForMainFrame() && !gmailFixed && err != null
                        && (err.getErrorCode() == WebViewClient.ERROR_REDIRECT_LOOP || String.valueOf(err.getDescription()).contains("TOO_MANY_REDIRECTS"))) {
                        /* Vòng lặp chuyển hướng của Google (cookie đăng nhập hỏng): xoá riêng cookie Google rồi tải lại một lần */
                        gmailFixed = true; clearGoogleCookies(); view.loadUrl("https://mail.google.com/mail/u/0/"); return;
                    }
                    if (req.isForMainFrame() && web != null) web.evaluateJavascript("window.nativeExtError&&window.nativeExtError(" + JSONObject.quote(key) + ")", null);
                }
            });
            if (true) { /* mọi web nhúng (Zalo, Facebook, Gmail...) đều được chọn tệp để tải lên */
                v.setWebChromeClient(new android.webkit.WebChromeClient() {
                    @Override public void onProgressChanged(WebView view, int p) { extNav(key, view, p); }
                    /* xem video toàn màn hình */
                    @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                        if (view == null) return;
                        if (extFullView != null) { cb.onCustomViewHidden(); return; }
                        extFullView = view; extFullCb = cb; LockService.setVideoFull(true);
                        view.setBackgroundColor(0xFF000000);
                        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                    }
                    @Override public void onHideCustomView() { extHideFull(); }
                    /* chọn tệp từ máy để mở trong Word/Excel/PowerPoint Online, Duyệt web */
                    @Override public boolean onShowFileChooser(WebView w, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams params) {
                        if (extFileCb != null) extFileCb.onReceiveValue(null);
                        extFileCb = cb;
                        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                        it.addCategory(Intent.CATEGORY_OPENABLE); it.setType("*/*");
                        if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        try { startActivityForResult(Intent.createChooser(it, "Chọn tệp"), REQ_EXTFILE); return true; }
                        catch (Exception e) { extFileCb = null; return false; }
                    }
                });
            }
            if (true) { /* mọi web nhúng đều tải xuống được vào thư mục Download */
                v.setDownloadListener(new android.webkit.DownloadListener() {
                    @Override public void onDownloadStart(String dUrl, String ua, String cd, String mime, long len) {
                        try {
                            android.app.DownloadManager.Request rq = new android.app.DownloadManager.Request(Uri.parse(dUrl));
                            String nm = android.webkit.URLUtil.guessFileName(dUrl, cd, mime);
                            rq.setMimeType(mime); rq.addRequestHeader("User-Agent", ua);
                            String ck = CookieManager.getInstance().getCookie(dUrl); if (ck != null) rq.addRequestHeader("Cookie", ck);
                            rq.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                            rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nm);
                            ((android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(rq);
                            android.widget.Toast.makeText(MainActivity.this, "Đang tải: " + nm, android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Exception e) { android.widget.Toast.makeText(MainActivity.this, "Không tải được tệp", android.widget.Toast.LENGTH_SHORT).show(); }
                    }
                });
            }
            v.setVisibility(View.GONE);
            root.addView(v, new FrameLayout.LayoutParams(1, 1));
            v.loadUrl(url);
            ext.put(key, v);
        }
        return v;
    }


    /** Giao diện cần WebView (Chrome) từ bản 105 trở lên; máy Android 7 thường phải cập nhật Android System WebView.
     *  WebView quá cũ (vd sau khi gỡ cập nhật về bản gốc của máy): hiện hộp thoại gốc của Android (không phụ thuộc giao diện HTML đang lỗi)
     *  với nút cài thẳng bản WebView đi kèm trong app (assets/webview/*.apk); chưa đóng gói thì mở trang cập nhật trên Google Play. */
    private void warnOldWebView() {
        try {
            String ua = web.getSettings().getUserAgentString();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(ua == null ? "" : ua);
            if (!m.find()) return;
            int v = Integer.parseInt(m.group(1));
            if (v < 105) showOldWebViewDialog(v);
        } catch (Throwable e) { }
    }
    /** Các mảnh (webview-1.part, webview-2.part...) của tệp zip chứa APK WebView, đóng gói sẵn trong assets/webview/. Rỗng nếu chưa có.
     *  Chia mảnh vì GitHub không nhận tệp trên 100 MB; app ghép lại rồi giải nén ra APK khi cài. */
    private java.util.List<String> bundledWebViewParts() {
        java.util.ArrayList<String> r = new java.util.ArrayList<String>();
        try {
            String[] l = getAssets().list("webview");
            if (l != null) for (String n : l) if (n.endsWith(".part")) r.add(n);
            java.util.Collections.sort(r, new java.util.Comparator<String>() {
                @Override public int compare(String x, String y) { return partNo(x) - partNo(y); }
            });
        } catch (Throwable t) { }
        return r;
    }
    private static int partNo(String n) {
        try { return Integer.parseInt(n.replaceAll("\\D+", "")); } catch (Throwable t) { return 0; }
    }
    private void showOldWebViewDialog(int v) {
        final java.util.List<String> parts = bundledWebViewParts();
        final boolean has = !parts.isEmpty();
        String msg = "Giao diện Desktop Mini chạy trên \"Android System WebView\". Máy đang dùng bản Chrome " + v
            + " (cần từ 105 trở lên) nên giao diện có thể bị lỗi, văng hoặc trắng màn hình.\n\n"
            + (has
                ? "Bấm \"Cài WebView ngay\" để cài bản WebView đi kèm trong app, xong mở lại Desktop Mini.\n\nNếu máy báo không cho cài từ nguồn này, hãy bật quyền cho Desktop Mini rồi bấm lại."
                : "Bấm \"Mở trang cập nhật\" để cập nhật \"Android System WebView\" trên Google Play, xong mở lại Desktop Mini.");
        try {
            new android.app.AlertDialog.Builder(this)
                .setTitle("WebView của máy quá cũ")
                .setMessage(msg)
                .setPositiveButton(has ? "Cài WebView ngay" : "Mở trang cập nhật", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { installWebView(parts); }
                })
                .setNegativeButton("Để sau", null)
                .show();
        } catch (Throwable e) { }
    }
    private void installWebView(final java.util.List<String> parts) {
        if (parts == null || parts.isEmpty()) { openWebViewStore(); return; }
        final java.io.File dir = new java.io.File(getCacheDir(), "shared"); dir.mkdirs();
        final java.io.File f = new java.io.File(dir, "webview.apk");
        if (f.exists() && f.length() > 200L * 1024 * 1024) { launchApkInstall(f, true); return; }   /* đã giải nén lần trước */
        if (dir.getUsableSpace() < 330L * 1024 * 1024) {
            android.widget.Toast.makeText(this, "Không đủ bộ nhớ trống (cần khoảng 330 MB) để giải nén WebView. Hãy dọn bớt rồi thử lại.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("Đang chuẩn bị WebView (khoảng 260 MB), vui lòng chờ…");
        pd.setCancelable(false);
        try { pd.show(); } catch (Throwable t) { }
        new Thread(new Runnable() { public void run() {
            boolean ok = false;
            java.io.File tmp = new java.io.File(dir, "webview.apk.tmp");
            try {
                java.util.Vector<java.io.InputStream> v = new java.util.Vector<java.io.InputStream>();
                for (String n : parts) v.add(getAssets().open("webview/" + n));
                java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new java.io.SequenceInputStream(v.elements()), 1 << 16));
                java.io.FileOutputStream out = null;
                try {
                    java.util.zip.ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        if (e.isDirectory() || !e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
                        out = new java.io.FileOutputStream(tmp);
                        byte[] buf = new byte[1 << 16]; int n;
                        while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                        out.flush(); out.close(); out = null;
                        ok = tmp.length() > 100L * 1024 * 1024;
                        break;
                    }
                } finally {
                    try { if (out != null) out.close(); } catch (Throwable t) { }
                    try { zin.close(); } catch (Throwable t) { }
                }
                if (ok) { f.delete(); ok = tmp.renameTo(f); }
            } catch (Throwable e) { ok = false; }
            if (!ok) { try { tmp.delete(); } catch (Throwable t) { } }
            final boolean done = ok;
            runOnUiThread(new Runnable() { public void run() {
                try { pd.dismiss(); } catch (Throwable t) { }
                if (done) launchApkInstall(f, true);
                else {
                    android.widget.Toast.makeText(MainActivity.this, "Không giải nén được WebView đi kèm, mở trang cập nhật trên Google Play.", android.widget.Toast.LENGTH_LONG).show();
                    openWebViewStore();
                }
            } });
        } }, "webview-install").start();
    }
    private void launchApkInstall(java.io.File f, boolean webview) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                android.widget.Toast.makeText(this, "Hãy bật \"Cho phép từ nguồn này\" cho Desktop Mini rồi thử cài lại.", android.widget.Toast.LENGTH_LONG).show();
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) {
            if (webview) {
                android.widget.Toast.makeText(this, "Không mở được trình cài đặt, mở trang cập nhật trên Google Play.", android.widget.Toast.LENGTH_LONG).show();
                openWebViewStore();
            } else android.widget.Toast.makeText(this, "Không mở được trình cài đặt của Android.", android.widget.Toast.LENGTH_LONG).show();
        }
    }
    private void openWebViewStore() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.webview")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Throwable e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable t) { }
        }
    }

    volatile String appsCache;
    /* Mốc thời gian lần dựng danh sách app gần nhất: giao diện so sánh số này để biết có cần nạp lại danh sách không */
    volatile long appsVer = 0;
    /** Dựng sẵn danh sách app cài đặt (kèm icon) ở luồng nền để Launchpad mở ngay, không khựng. */
    private long appsAt;
    private void prefetchApps() {
        long now = System.currentTimeMillis();
        if (appsCache != null && now - appsAt < 30000) return;
        appsAt = now;
        new Thread(new Runnable() { public void run() {
            try { LauncherBridge b = new LauncherBridge(MainActivity.this); String r = b.buildAppsNow(); appsCache = r; appsVer = System.currentTimeMillis(); } catch (Throwable e) { }
        } }, "apps-prefetch").start();
    }

    static String icon(Drawable d, int n) {
        Bitmap bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm); d.setBounds(0, 0, n, n); d.draw(c);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 90, os);
        return "data:image/png;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
    }

}
