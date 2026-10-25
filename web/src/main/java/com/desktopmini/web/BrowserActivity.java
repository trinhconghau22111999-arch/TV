package com.desktopmini.web;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * "Trình duyệt" của Desktop Mini: trình duyệt Gecko (Firefox) chạy trong app phụ riêng, không dùng chung tiến trình với WebView hệ thống của app chính.
 * Có thẻ (tab), thanh địa chỉ, dấu trang, lịch sử, tải xuống.
 */
public class BrowserActivity extends Activity implements Downloads.Listener {
    static final class Tab { GeckoSession s; String url = "", title = ""; boolean back, fwd; int prog; }

    private final ArrayList<Tab> tabs = new ArrayList<Tab>();
    private int cur = -1;
    private GeckoView gv;
    private FrameLayout content;
    private LinearLayout tabStrip;
    private HorizontalScrollView tabScroll;
    private EditText addr;
    private Ic icBack, icFwd, icStar, icDown, icStars;
    private ProgressBar bar;
    private ScrollView panelScroll;
    private LinearLayout panel;
    private String panelKind = "";
    private boolean safe;
    private SharedPreferences st;

    private CloseRx closeRx;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        closeRx = CloseRx.reg(this);
        st = getSharedPreferences("web_state", MODE_PRIVATE);
        /* Lần mở trước chưa tải xong trang nào mà app đã chết (lỗi mã gốc Gecko): lần 1 về trang chủ, từ lần 2 dùng chế độ nhẹ (SurfaceView). App phụ chết không kéo theo app chính. */
        int deaths = st.getInt("deaths", 0);
        if (st.getBoolean("opening", false)) { deaths++; st.edit().putInt("deaths", deaths).remove("last").apply(); }
        safe = deaths >= 2;
        st.edit().putBoolean("opening", true).apply();

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
            && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7); } catch (Throwable t) { }
        }
        GeckoCore.rt(this);
        buildUi();
        Downloads.listen(this);
        String start = startUrl(getIntent());
        newTab(start, true);
    }

    private String startUrl(Intent it) {
        String u = null;
        if (it != null) {
            if (it.getDataString() != null) u = it.getDataString();
            else if (it.getStringExtra("url") != null) u = it.getStringExtra("url");
        }
        if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) return u;
        /* Mở lại trình duyệt: không giữ thẻ cũ, chỉ mở 1 thẻ mới là dấu trang gần nhất (dấu trang mới thêm nằm đầu danh sách). Không có dấu trang thì về trang chủ. */
        if (st.getInt("deaths", 0) == 0) {
            String bm = latestBookmark();
            if (bm != null) return bm;
        }
        return GeckoCore.HOME;
    }

    /** Địa chỉ dấu trang được thêm gần nhất, hoặc null nếu chưa có / không dùng được. */
    private String latestBookmark() {
        try {
            JSONArray b = getList("bm");
            for (int i = 0; i < b.length(); i++) {
                JSONObject o = b.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("u");
                if ((u.startsWith("http://") || u.startsWith("https://")) && GeckoCore.restorable(u)) return u;
            }
        } catch (Throwable e) { }
        return null;
    }

    @Override protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        String u = it == null ? null : (it.getDataString() != null ? it.getDataString() : it.getStringExtra("url"));
        if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) { closePanel(); newTab(u, true); }
    }

    /* ---------------- Giao diện ---------------- */
    private int dp(int v) { return GeckoCore.dp(this, v); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF2B1B4A);
        /* Thanh menu nổi của Desktop Mini luôn nằm trên cùng: chừa đúng chiều cao của nó, không để thanh công cụ trình duyệt chui xuống dưới */
        root.setPadding(0, GeckoCore.menuBarInset(this), 0, 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), 0);

        final Ic menu = Ic.mkW(this, Ic.MENU, null);
        menu.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showMenu(menu); } });
        icBack = Ic.mkW(this, Ic.BACK, new View.OnClickListener() { public void onClick(View v) { if (session() != null) session().goBack(); } });
        icFwd = Ic.mkW(this, Ic.FWD, new View.OnClickListener() { public void onClick(View v) { if (session() != null) session().goForward(); } });
        Ic icReload = Ic.mkW(this, Ic.RELOAD, new View.OnClickListener() { public void onClick(View v) { if (session() != null) session().reload(); } });
        addr = new EditText(this);
        addr.setSingleLine(true);
        addr.setTextSize(14);
        addr.setHint("Tìm kiếm hoặc nhập địa chỉ");
        addr.setSelectAllOnFocus(true);
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addr.setImeOptions(EditorInfo.IME_ACTION_GO);
        addr.setPadding(dp(12), 0, dp(12), 0);
        addr.setTextColor(0xFF1E1633); addr.setHintTextColor(0xFF8A80A6);
        GradientDrawable ab = new GradientDrawable(); ab.setColor(0xFFF2EEFA); ab.setCornerRadius(dp(19));
        addr.setBackground(ab);
        addr.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_GO || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN)) { go(); return true; }
                return false;
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        alp.leftMargin = dp(4); alp.rightMargin = dp(4);
        icStar = Ic.mkW(this, Ic.STAR, new View.OnClickListener() { public void onClick(View v) { toggleBookmark(); } });
        icDown = Ic.mkW(this, Ic.DOWN, new View.OnClickListener() { public void onClick(View v) { if ("dl".equals(panelKind)) closePanel(); else openPanel("dl"); } });
        icStars = Ic.mkW(this, Ic.STARS, new View.OnClickListener() { public void onClick(View v) { if ("bm".equals(panelKind)) closePanel(); else openPanel("bm"); } });

        top.addView(menu); top.addView(icBack); top.addView(icFwd); top.addView(icReload);
        top.addView(addr, alp);
        Ic icClose = Ic.mkW(this, Ic.CLOSE, new View.OnClickListener() { public void onClick(View v) { finish(); } });   /* đóng hẳn trình duyệt */
        top.addView(icStar); top.addView(icDown); top.addView(icStars); top.addView(icClose);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.INVISIBLE);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(dp(4), dp(2), dp(4), dp(2));
        tabScroll.addView(tabStrip, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabScroll.setBackgroundColor(0xFFE9E3F5);
        root.addView(tabScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        content = new FrameLayout(this);
        content.setBackgroundColor(Color.WHITE);
        gv = new GeckoView(this);
        gv.setViewBackend(safe ? GeckoView.BACKEND_SURFACE_VIEW : GeckoView.BACKEND_TEXTURE_VIEW);
        gv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GeckoCore.attachZoomKeys(this, gv);
        content.addView(gv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panelScroll = new ScrollView(this);
        panelScroll.setBackgroundColor(0xFFF7F7FA);
        panelScroll.setVisibility(View.GONE);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panelScroll.addView(panel, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(panelScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        hideBars();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override public void onSystemUiVisibilityChange(int vis) { if ((vis & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) hideBars(); }
        });
    }

    private GeckoSession session() { return cur >= 0 && cur < tabs.size() ? tabs.get(cur).s : null; }
    private Tab curTab() { return cur >= 0 && cur < tabs.size() ? tabs.get(cur) : null; }

    private void go() {
        String t = addr.getText().toString().trim();
        if (t.length() == 0 || session() == null) return;
        closePanel();
        session().loadUri(GeckoCore.toUrl(t));
        hideKeyboard();
        gv.requestFocus();
    }

    private void hideKeyboard() {
        try { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(addr.getWindowToken(), 0); } catch (Throwable t) { }
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Lịch sử");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem it) {
                switch (it.getItemId()) {
                    case 1: openPanel("hist"); break;
                    case 2: openPanel("bm"); break;
                    case 3: openPanel("dl"); break;
                    case 4: finish(); break;
                    default: break;
                }
                return true;
            }
        });
        m.show();
    }

    /* ---------------- Thẻ (tab) ---------------- */
    private void makeSession(final Tab t) {
        final GeckoSession se = new GeckoSession();
        t.s = se;
        se.setContentDelegate(new GeckoSession.ContentDelegate() {
            public void onTitleChange(GeckoSession s, String title) { t.title = title == null ? "" : title; refresh(); }
            @Override public void onExternalResponse(GeckoSession s, WebResponse response) { download(response); }
            @Override public void onCrash(GeckoSession s) { toast("Trang web bị văng, hãy tải lại hoặc mở thẻ mới."); }
            @Override public void onKill(GeckoSession s) { toast("Trang web bị tắt (có thể thiếu RAM), hãy tải lại."); }
        });
        se.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession s, String u) { if (u != null) t.url = u; t.prog = 5; refresh(); }
            @Override public void onProgressChange(GeckoSession s, int p) { t.prog = p; refresh(); }
            @Override public void onPageStop(GeckoSession s, boolean success) {
                t.prog = 0;
                if (success) {
                    String u = t.url;
                    if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                        st.edit().putInt("deaths", 0).putBoolean("opening", false).remove("last").apply();   /* không nhớ thẻ/trang cũ: lần sau mở lại là dấu trang gần nhất */
                        recordHistory(t);
                    }
                }
                refresh();
            }
        });
        se.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            /* Không gắn @Override: tùy bản Gecko là chữ ký 2 hay 3 tham số, cái nào có thì được gọi; kiểu List thô để khớp mọi bản */
            public void onLocationChange(GeckoSession s, String url, java.util.List perms) { loc(t, url); }
            public void onLocationChange(GeckoSession s, String url) { loc(t, url); }
            @Override public void onCanGoBack(GeckoSession s, boolean can) { t.back = can; refresh(); }
            @Override public void onCanGoForward(GeckoSession s, boolean can) { t.fwd = can; refresh(); }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                newTab(uri, true); return null;   /* link mở thẻ mới -> mở thẻ mới của mình */
            }
        });
        se.open(GeckoCore.rt(this));
    }

    private void newTab(String url, boolean select) {
        final Tab t = new Tab(); t.url = url == null ? "" : url;
        makeSession(t);
        tabs.add(t);
        if (select) selectTab(tabs.size() - 1);
        if (t.url.length() > 0 && t.s != null) t.s.loadUri(t.url);
        refresh();
    }

    /** Thiếu RAM: đóng phiên Gecko của các thẻ nền (giữ lại địa chỉ + tiêu đề), chọn lại thẻ nào thì nạp lại đúng trang đó. */
    private void trimTabs() {
        for (int i = 0; i < tabs.size(); i++) {
            if (i == cur) continue;
            Tab t = tabs.get(i);
            if (t.s == null) continue;
            try { t.s.close(); } catch (Throwable e) { }
            t.s = null; t.prog = 0; t.back = false; t.fwd = false;
        }
        refresh();
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == 10 || level == 15 || level >= 40) trimTabs();
    }

    private void loc(Tab t, String url) {
        if (url == null || url.length() == 0 || url.equals("about:blank")) return;
        t.url = url; refresh();
    }

    private void selectTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        cur = i;
        Tab t = tabs.get(i);
        boolean re = t.s == null;
        if (re) makeSession(t);   /* thẻ đã được giải phóng do thiếu RAM: dựng lại phiên */
        try { gv.releaseSession(); } catch (Throwable e) { }
        try { gv.setSession(t.s); } catch (Throwable e) { toast("Không chuyển được thẻ: " + e); }
        if (re && t.url != null && t.url.length() > 0) t.s.loadUri(t.url);
        refresh();
    }

    private void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        boolean wasCur = (i == cur);
        Tab t = tabs.get(i);
        if (wasCur) { try { gv.releaseSession(); } catch (Throwable e) { } }
        tabs.remove(i);
        try { t.s.close(); } catch (Throwable e) { }
        if (tabs.isEmpty()) { cur = -1; newTab(GeckoCore.HOME, true); return; }
        if (wasCur) selectTab(Math.min(i, tabs.size() - 1));
        else { if (i < cur) cur--; refresh(); }
    }

    private String hostOf(String u) {
        try { String h = Uri.parse(u).getHost(); return h == null ? u : h.replaceFirst("^www\\.", ""); } catch (Throwable e) { return u; }
    }

    /** Cập nhật thanh địa chỉ, nút, thanh tiến độ, dải thẻ theo thẻ đang xem. */
    private void refresh() {
        Tab t = curTab();
        if (t == null) return;
        icBack.setEnabled(t.back); icFwd.setEnabled(t.fwd);
        if (t.prog > 0 && t.prog < 100) { bar.setVisibility(View.VISIBLE); bar.setProgress(t.prog); } else bar.setVisibility(View.INVISIBLE);
        if (!addr.hasFocus() && t.url != null && t.url.length() > 0) addr.setText(t.url);
        icStar.setOn(t.url != null && isBookmarked(t.url));
        icDown.setOn(Downloads.anyRunning());
        tabStrip.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i; Tab x = tabs.get(i);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(10), 0, dp(2), 0);
            GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(8)); bg.setColor(i == cur ? Color.WHITE : 0x00000000);
            chip.setBackground(bg);
            TextView tv = new TextView(this);
            tv.setText(x.title.length() > 0 ? x.title : (x.url.length() > 0 ? hostOf(x.url) : "Thẻ mới"));
            tv.setSingleLine(true); tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setMaxWidth(dp(150)); tv.setTextSize(12.5f);
            tv.setTextColor(i == cur ? 0xFF2B1B4A : 0xFF5A4A7A);
            if (i == cur) tv.setTypeface(Typeface.DEFAULT_BOLD);
            TextView cx = new TextView(this);
            cx.setText("\u00D7"); cx.setTextSize(18); cx.setGravity(Gravity.CENTER); cx.setTextColor(0xFF666670);
            cx.setPadding(dp(8), 0, dp(8), 0);
            cx.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeTab(idx); } });
            chip.addView(tv); chip.addView(cx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            chip.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closePanel(); selectTab(idx); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            lp.rightMargin = dp(4);
            tabStrip.addView(chip, lp);
            if (i == cur) {
                /* dấu + mở thẻ mới nằm ngay bên phải thẻ đang xem */
                Ic plus = Ic.make(this, Ic.PLUS, new View.OnClickListener() { public void onClick(View v) { closePanel(); newTab("", true); addr.requestFocus(); } });
                plus.tint = 0xFF5A4A7A;
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(32), dp(30));
                plp.rightMargin = dp(4);
                plus.setLayoutParams(plp);
                tabStrip.addView(plus);
            }
        }
        if ("dl".equals(panelKind) || "bm".equals(panelKind)) renderPanel();
    }

    /* ---------------- Lịch sử / Dấu trang (lưu trong máy) ---------------- */
    private JSONArray getList(String key) {
        try { return new JSONArray(getSharedPreferences("web_data", MODE_PRIVATE).getString(key, "[]")); } catch (Throwable e) { return new JSONArray(); }
    }
    private void putList(String key, JSONArray a) { getSharedPreferences("web_data", MODE_PRIVATE).edit().putString(key, a.toString()).apply(); }

    private void recordHistory(Tab t) {
        try {
            JSONArray h = getList("hist");
            if (h.length() > 0 && t.url.equals(h.getJSONObject(0).optString("u"))) {
                JSONObject f = h.getJSONObject(0);
                if (f.optString("t").length() == 0 && t.title.length() > 0) { f.put("t", t.title); putList("hist", h); }
                return;
            }
            JSONArray n = new JSONArray();
            JSONObject o = new JSONObject(); o.put("t", t.title); o.put("u", t.url); o.put("d", System.currentTimeMillis());
            n.put(o);
            for (int i = 0; i < h.length() && n.length() < 300; i++) n.put(h.get(i));
            putList("hist", n);
        } catch (Throwable e) { }
    }

    private boolean isBookmarked(String url) {
        JSONArray b = getList("bm");
        for (int i = 0; i < b.length(); i++) if (url.equals(b.optJSONObject(i).optString("u"))) return true;
        return false;
    }

    private void toggleBookmark() {
        Tab t = curTab();
        if (t == null || t.url == null || !t.url.startsWith("http")) return;
        try {
            JSONArray b = getList("bm"), n = new JSONArray(); boolean had = false;
            for (int i = 0; i < b.length(); i++) {
                JSONObject o = b.getJSONObject(i);
                if (t.url.equals(o.optString("u"))) had = true; else n.put(o);
            }
            if (!had) {
                JSONArray n2 = new JSONArray();
                JSONObject o = new JSONObject(); o.put("t", t.title.length() > 0 ? t.title : hostOf(t.url)); o.put("u", t.url);
                n2.put(o);
                for (int i = 0; i < n.length(); i++) n2.put(n.get(i));
                n = n2;
            }
            putList("bm", n);
        } catch (Throwable e) { }
        refresh();
        if ("bm".equals(panelKind)) renderPanel();
    }

    /* ---------------- Bảng Lịch sử / Dấu trang / Tải xuống ---------------- */
    private void openPanel(String kind) { panelKind = kind; hideKeyboard(); panelScroll.setVisibility(View.VISIBLE); renderPanel(); }
    private void closePanel() { panelKind = ""; panelScroll.setVisibility(View.GONE); }

    private TextView txt(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView btn(String s, View.OnClickListener l, boolean enabled) {
        TextView b = txt(s, 13, enabled ? 0xFF0A60D8 : 0xFFAAAAB2, true);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setEnabled(enabled);
        if (enabled) b.setOnClickListener(l);
        return b;
    }

    private void header(String title, TextView action) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(btn("\u2039 Trang web", new View.OnClickListener() { public void onClick(View v) { closePanel(); } }, true));
        TextView t = txt(title, 16, 0xFF1C1C1E, true); t.setGravity(Gravity.CENTER);
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) h.addView(action); else h.addView(new View(this), new LinearLayout.LayoutParams(dp(80), 1));
        panel.addView(h);
    }

    private String size(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024L * 1024) return Math.round(n / 1024f) + " KB";
        if (n < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", n / 1048576f);
        return String.format(java.util.Locale.US, "%.2f GB", n / 1073741824f);
    }

    private void renderPanel() {
        if (panelKind.length() == 0) return;
        panel.removeAllViews();
        if ("dl".equals(panelKind)) renderDownloads(); else renderList("hist".equals(panelKind));
    }

    private void renderList(final boolean hist) {
        final String key = hist ? "hist" : "bm";
        final JSONArray l = getList(key);
        header(hist ? "Lịch sử" : "Dấu trang", hist ? btn("Xoá hết", new View.OnClickListener() { public void onClick(View v) { putList("hist", new JSONArray()); renderPanel(); } }, l.length() > 0) : null);
        if (l.length() == 0) { TextView e = txt(hist ? "Chưa có lịch sử duyệt web" : "Chưa có dấu trang nào", 14, 0xFF77777F, false); e.setPadding(0, dp(24), 0, 0); e.setGravity(Gravity.CENTER); panel.addView(e); return; }
        for (int i = 0; i < l.length(); i++) {
            final int idx = i; final JSONObject o = l.optJSONObject(i); if (o == null) continue;
            final String u = o.optString("u");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            TextView a = txt(o.optString("t").length() > 0 ? o.optString("t") : hostOf(u), 14, 0xFF1C1C1E, true); a.setSingleLine(true); a.setEllipsize(TextUtils.TruncateAt.END);
            TextView b = txt(u, 11.5f, 0xFF77777F, false); b.setSingleLine(true); b.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(a); col.addView(b);
            col.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closePanel(); if (session() != null) session().loadUri(u); } });
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView del = txt("\u00D7", 20, 0xFF77777F, false); del.setPadding(dp(14), dp(4), dp(10), dp(4));
            del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                try { JSONArray n = new JSONArray(); for (int j = 0; j < l.length(); j++) if (j != idx) n.put(l.get(j)); putList(key, n); } catch (Throwable e) { }
                renderPanel(); refresh();
            } });
            row.addView(del);
            panel.addView(row);
        }
    }

    private void renderDownloads() {
        final List<Downloads.Item> l = Downloads.list(this);
        boolean anyDone = false; for (Downloads.Item it : l) if (!"run".equals(it.state)) anyDone = true;
        header("Tải xuống", btn("Xoá lịch sử", new View.OnClickListener() { public void onClick(View v) { Downloads.clearFinished(BrowserActivity.this); } }, anyDone));
        if (l.isEmpty()) { TextView e = txt("Chưa có tệp nào được tải xuống", 14, 0xFF77777F, false); e.setPadding(0, dp(24), 0, 0); e.setGravity(Gravity.CENTER); panel.addView(e); return; }
        for (final Downloads.Item it : l) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0, dp(8), 0, dp(8));
            TextView nm = txt(it.name, 14, 0xFF1C1C1E, true); nm.setSingleLine(true); nm.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(nm);
            int pct = it.total > 0 ? (int) Math.min(100, it.got * 100 / it.total) : ("ok".equals(it.state) ? 100 : 0);
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100); pb.setProgress("err".equals(it.state) ? 100 : pct);
            row.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
            String s = "run".equals(it.state) ? (it.total > 0 ? size(it.got) + " / " + size(it.total) + " \u00B7 " + pct + "%" : size(it.got) + " \u00B7 đang tải…")
                : "ok".equals(it.state) ? "Đã tải xong \u00B7 " + size(it.got) : "Lỗi: " + (it.msg.length() > 0 ? it.msg : "không tải được");
            row.addView(txt(s, 12, 0xFF77777F, false));
            LinearLayout acts = new LinearLayout(this); acts.setOrientation(LinearLayout.HORIZONTAL);
            if ("ok".equals(it.state)) acts.addView(btn("Mở", new View.OnClickListener() { public void onClick(View v) { openDownloaded(it); } }, it.uri.length() > 0));
            if ("run".equals(it.state)) acts.addView(btn("Huỷ tải", new View.OnClickListener() { public void onClick(View v) { Downloads.cancel(it.id); } }, true));
            row.addView(acts);
            panel.addView(row);
        }
    }

    /** Đường dẫn thật của tệp đã tải (Android 10+ lưu qua MediaStore nên phải tra từ Uri). */
    private String pathOfDownload(Downloads.Item it) {
        if (it.uri.startsWith("file:")) return it.uri.substring(5);
        try {
            android.database.Cursor c = getContentResolver().query(Uri.parse(it.uri), new String[]{"_data"}, null, null, null);
            if (c != null) { try { if (c.moveToFirst() && c.getString(0) != null) return c.getString(0); } finally { c.close(); } }
        } catch (Throwable e) { }
        return new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), it.name).getAbsolutePath();
    }

    /** "Mở": nhờ app Tải xuống của Desktop Mini mở tệp (như bấm tệp trong app đó). Không có Desktop Mini thì mở bằng app khác như cũ. */
    private void openDownloaded(Downloads.Item it) {
        try {
            Intent i = new Intent().setClassName("com.desktopmini.tv", "com.desktopmini.launcher.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("openDownloaded", pathOfDownload(it)).putExtra("openDownloadedName", it.name);
            startActivity(i);
            return;
        } catch (Throwable e) { }
        openDownloadedExternal(it);
    }

    private void openDownloadedExternal(Downloads.Item it) {
        try {
            Uri u = it.uri.startsWith("file:") ? androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", new java.io.File(it.uri.substring(5))) : Uri.parse(it.uri);
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(android.webkit.MimeTypeMap.getFileExtensionFromUrl(it.name.replace(" ", "_")));
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime == null ? "*/*" : mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Throwable e) { toast("Không có app nào mở được tệp này"); }
    }

    @Override public void onChanged() { if (curTab() != null) refresh(); }

    /** Cảnh báo khi tải trùng một tệp đang tải: hỏi xác nhận rồi mới tải thêm. */
    private void download(final WebResponse r) {
        final String nm = Downloads.nameOf(r);
        if (!Downloads.isRunningName(nm)) { Downloads.start(this, r); return; }
        try {
            new AlertDialog.Builder(this)
                .setTitle("Tệp đang được tải")
                .setMessage("\"" + nm + "\" đang tải xuống. Bạn có muốn tải thêm một lần nữa không?")
                .setPositiveButton("Tải thêm", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { Downloads.start(BrowserActivity.this, r); } })
                .setNegativeButton("Huỷ", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { try { if (r.body != null) r.body.close(); } catch (Exception e) { } } })
                .setOnCancelListener(new DialogInterface.OnCancelListener() { public void onCancel(DialogInterface d) { try { if (r.body != null) r.body.close(); } catch (Exception e) { } } })
                .show();
        } catch (Throwable e) { Downloads.start(this, r); }
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    /* ---------------- Vòng đời ---------------- */
    @Override public void onBackPressed() {
        if (panelKind.length() > 0) { closePanel(); return; }
        Tab t = curTab();
        if (t != null && t.back && t.s != null) { t.s.goBack(); return; }
        if (tabs.size() > 1) { closeTab(cur); return; }
        finish();
    }

    /** Ẩn thanh trạng thái và thanh điều hướng như app chính, để thanh đa nhiệm hệ thống không hiện đè lên trình duyệt. */
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    private void hideBars() {
        immersive();
        getWindow().getDecorView().postDelayed(new Runnable() { public void run() { immersive(); } }, 400);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
    @Override protected void onResume() { super.onResume(); hideBars(); }

    @Override protected void onStart() { super.onStart(); GeckoSession s = session(); if (s != null) { try { s.setActive(true); } catch (Throwable e) { } } }

    @Override protected void onStop() {
        super.onStop();
        if (!Downloads.anyRunning()) { GeckoSession s = session(); if (s != null) { try { s.setActive(false); } catch (Throwable e) { } } }
    }

    @Override protected void onDestroy() {
        CloseRx.unreg(this, closeRx);
        Downloads.unlisten(this);
        if (isFinishing()) st.edit().putBoolean("opening", false).apply();
        try { gv.releaseSession(); } catch (Throwable e) { }
        for (Tab t : tabs) { try { if (t.s != null) t.s.close(); } catch (Throwable e) { } }
        tabs.clear();
        super.onDestroy();
    }
}
