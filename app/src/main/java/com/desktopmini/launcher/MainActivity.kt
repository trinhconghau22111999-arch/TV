package com.desktopmini.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

private const val REQ_FILE = 3001
private const val REQ_STORAGE = 3002
private const val REQ_VOICE = 3003
private const val REQ_VOICE_PERM = 3004
private const val REQ_ROLE = 3005

/** Biểu tượng pin tự vẽ: khung + mức pin + số %. */
private class BatteryView(ctx: Context) : View(ctx) {
    var pct = 100
    var charging = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bw = w * 0.72f; val bh = h * 0.62f
        val top = (h - bh) / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.WHITE
        c.drawRoundRect(RectF(1f, top, bw, top + bh), 4f, 4f, p)
        p.style = Paint.Style.FILL
        c.drawRect(bw, top + bh * 0.3f, bw + 4f, top + bh * 0.7f, p)
        p.color = if (charging) 0xFF3DDC84.toInt() else if (pct <= 15) 0xFFFF5A5A.toInt() else Color.WHITE
        val fw = (bw - 5f) * (pct.coerceIn(0, 100) / 100f)
        c.drawRect(3.5f, top + 2.5f, 3.5f + fw, top + bh - 2.5f, p)
        p.color = Color.WHITE
    }
}

class MainActivity : Activity(), FullscreenHost {
    private lateinit var content: FrameLayout
    private lateinit var barView: LinearLayout
    private lateinit var panels: QuickPanels
    private lateinit var floatBack: TextView
    private var barH = 0
    private lateinit var fullLayer: FrameLayout
    private lateinit var hidden: FrameLayout
    private lateinit var btnTv: TextView
    private lateinit var btnYt: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvLunar: TextView
    private lateinit var tvPct: TextView
    private lateinit var tvWifi: TextView
    private lateinit var tvBt: TextView
    private lateinit var battery: BatteryView
    private lateinit var remote: RemoteController
    private var connWake: PowerManager.WakeLock? = null

    private var mode = 0                  // 0 = trang trống, 1 = TV, 2 = YouTube
    private var tv: TvPane? = null
    private var yt: YouTubePane? = null
    private var transfer: TransferHost? = null
    private var fullView: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val tick = object : Runnable {
        override fun run() { refreshStatus(); ui.postDelayed(this, 15000) }
    }

    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0 && scale > 0) {
                battery.pct = level * 100 / scale
                battery.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                battery.invalidate()
                tvPct.text = battery.pct.toString() + "%"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        // Giu CPU/mang song khi man hinh bi tat tu xa de remote van ket noi va bat lai duoc
        try {
            connWake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tv:connection").also { it.setReferenceCounted(false); it.acquire() }
        } catch (e: Exception) { }
        buildUi()
        hideSystemBars()

        registerReceiver(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        refreshStatus()
        ui.postDelayed(tick, 15000)

        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)

        // Truyền tệp chạy ngầm: khởi động trễ vài giây để giao diện hiện ra trước cho nhanh
        ui.postDelayed({
            if (isFinishing || transfer != null) return@postDelayed
            transfer = TransferHost(this,
                onTextEdit = { del, ins -> applyTextEdit(del, ins) },
                onKeepAwake = { },
                onRemote = { json -> remote.handle(json) },
                pickFile = { cb, params -> openFileChooser(cb, params) })
            transfer?.start(hidden)
        }, 2500)
        ui.postDelayed({ maybeAskDefaultHome() }, 600)
    }

    // ---------------- Đặt làm giao diện (màn hình chính) mặc định ----------------

    private fun isDefaultHome(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun maybeAskDefaultHome() {
        if (isFinishing || isDefaultHome()) return
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        if (sp.getBoolean("noAskHome", false)) return
        AlertDialog.Builder(this)
            .setTitle("Đặt TV làm giao diện mặc định?")
            .setMessage("Khi bấm nút Home, máy sẽ vào thẳng giao diện TV.")
            .setPositiveButton("Đặt mặc định") { _, _ -> requestDefaultHome() }
            .setNegativeButton("Để sau", null)
            .setNeutralButton("Không hỏi lại") { _, _ -> sp.edit().putBoolean("noAskHome", true).apply() }
            .show()
    }

    private fun requestDefaultHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
                    return
                }
            }
            // Android cũ: mở lại màn hình chính để hệ thống hỏi chọn ứng dụng, chọn TV rồi bấm "Luôn luôn"
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
            catch (e2: Exception) { Toast.makeText(this, "Hãy vào Cài đặt > Ứng dụng > Ứng dụng mặc định > Màn hình chính", Toast.LENGTH_LONG).show() }
        }
    }

    // ---------------- Giao diện ----------------

    private fun barBtn(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setBackgroundColor(0xFF33334A.toInt())
        minWidth = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun small(t: String, sp: Float = 11f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        barH = dp(40)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF15151F.toInt())
            setPadding(dp(4), dp(3), dp(6), dp(3))
        }
        btnTv = barBtn("TV").apply { setOnClickListener { onModeButton(1) } }
        btnYt = barBtn("YouTube").apply { setOnClickListener { onModeButton(2) } }
        val btnBack = barBtn("Thoát").apply {
            textSize = 16f
            setOnClickListener { goBack() }
        }
        val gap = { w: Int -> View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(w), 1) } }
        bar.addView(btnTv, LinearLayout.LayoutParams(-2, -1))
        bar.addView(gap(4))
        bar.addView(btnYt, LinearLayout.LayoutParams(-2, -1))
        bar.addView(gap(6))
        bar.addView(btnBack, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(gap(10))

        // Góc phải: pin, wifi, bluetooth, giờ, ngày, âm lịch
        battery = BatteryView(this)
        bar.addView(battery, LinearLayout.LayoutParams(dp(24), dp(18)))
        tvPct = small("--%").apply { setPadding(dp(3), 0, dp(8), 0) }
        bar.addView(tvPct, LinearLayout.LayoutParams(-2, -1))
        tvWifi = small("WiFi", 12f, true).apply { setPadding(dp(8), 0, dp(8), 0) }
        bar.addView(tvWifi, LinearLayout.LayoutParams(-2, -1))
        tvBt = small("BT", 12f, true).apply { setPadding(dp(8), 0, dp(10), 0) }
        bar.addView(tvBt, LinearLayout.LayoutParams(-2, -1))
        tvTime = small("--:--", 18f, true).apply { setPadding(0, 0, dp(8), 0) }
        bar.addView(tvTime, LinearLayout.LayoutParams(-2, -1))
        val dates = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        tvDate = small("", 10f)
        tvLunar = small("", 10f).apply { setTextColor(0xFFFFD27A.toInt()) }
        dates.addView(tvDate); dates.addView(tvLunar)
        bar.addView(dates, LinearLayout.LayoutParams(-2, -1))

        barView = bar
        col.addView(bar, LinearLayout.LayoutParams(-1, barH))
        content = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // Hình nền trang chủ (hiện khi chưa mở TV/YouTube); các khung TV/YouTube nằm phủ lên trên
        content.addView(android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.wallpaper)
        }, FrameLayout.LayoutParams(-1, -1))
        col.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        fullLayer = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        val lp = FrameLayout.LayoutParams(-1, -1); lp.topMargin = barH
        root.addView(fullLayer, lp)
        floatBack = TextView(this).apply {
            text = "Thoát"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundColor(0x99000000.toInt()); setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
            setOnClickListener { goBack() }
        }
        root.addView(floatBack, FrameLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8); it.topMargin = dp(8) })
        panels = QuickPanels(this, root, barH)
        remote = RemoteController(this, root, { if (mode == 1) tv?.activeWeb else if (mode == 2) yt?.web else null }, { remoteVoice(it) })
        tvWifi.setOnClickListener { panels.toggle(1) }
        tvBt.setOnClickListener { panels.toggle(2) }
        hidden = FrameLayout(this)
        root.addView(hidden, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        updateButtons()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun updateButtons() {
        btnTv.setBackgroundColor(if (mode == 1) 0xFF3A7BFF.toInt() else 0xFF33334A.toInt())
        btnYt.setBackgroundColor(if (mode == 2) 0xFFE02B2B.toInt() else 0xFF33334A.toInt())
    }

    // ---------------- Chế độ TV / YouTube ----------------

    private fun onModeButton(m: Int) {
        if (mode == m) {                      // bấm lại nút đang mở: về trang chủ của chế độ đó
            if (m == 1) tv?.home() else yt?.home()
            return
        }
        hideFull()
        setMode(m)
    }

    override fun setBarVisible(v: Boolean) {
        runOnUiThread {
            barView.visibility = if (v) View.VISIBLE else View.GONE
            floatBack.visibility = if (v) View.GONE else View.VISIBLE
            (fullLayer.layoutParams as FrameLayout.LayoutParams).topMargin = if (v) barH else 0
            fullLayer.requestLayout()
        }
    }

    private fun setMode(m: Int) {
        setBarVisible(true)
        tv?.let { content.removeView(it.view); it.onPause() }
        yt?.let { content.removeView(it.view); it.onPause() }
        mode = m
        when (m) {
            1 -> {
                val p = tv ?: TvPane(this, this).also { tv = it }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
            2 -> {
                val p = yt ?: YouTubePane(this, this).also { yt = it }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
        }
        updateButtons()
    }

    private fun goBack() {
        if (panels.isOpen()) { panels.hide(); return }
        if (fullView != null) { hideFull(); return }
        when (mode) {
            1 -> tv?.back()
            2 -> yt?.back()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goBack() }     // luôn ở lại màn hình chính, không thoát app

    // ---------------- Video toàn màn hình ----------------

    override fun showFull(v: View, cb: WebChromeClient.CustomViewCallback?) {
        if (fullView != null) { cb?.onCustomViewHidden(); return }
        fullView = v; fullCb = cb
        v.setBackgroundColor(Color.BLACK)
        fullLayer.addView(v, FrameLayout.LayoutParams(-1, -1))
        fullLayer.visibility = View.VISIBLE
    }

    override fun hideFull() {
        val v = fullView ?: return
        fullLayer.removeView(v)
        fullLayer.visibility = View.GONE
        fullView = null
        fullCb?.onCustomViewHidden(); fullCb = null
    }

    // ---------------- Giọng nói (YouTube) ----------------

    override fun launchVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE_PERM); return
        }
        val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói để tìm kiếm trên YouTube")
        }
        try { startActivityForResult(it, REQ_VOICE) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Máy chưa có ứng dụng nhận dạng giọng nói", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Truyền tệp (ngầm) ----------------

    private fun openFileChooser(cb: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
        fileCb?.onReceiveValue(null)
        fileCb = cb
        return try { startActivityForResult(params!!.createIntent(), REQ_FILE); true }
        catch (e: Exception) { fileCb?.onReceiveValue(null); fileCb = null; false }
    }

    // Remote giong noi, tuy che do dang chon san tren TV:
    //  - dang o TV (mode 1): noi ten kenh/dai -> mo kenh do
    //  - con lai (YouTube hoac trang trong): noi ten video -> tim tren YouTube va tu mo video dau tien
    private fun remoteVoice(q: String) {
        if (mode == 1) {
            val ok = tv?.playByName(q) ?: false
            if (!ok) Toast.makeText(this, "Không tìm thấy kênh: $q", Toast.LENGTH_LONG).show()
            return
        }
        if (mode != 2) { hideFull(); setMode(2) }
        yt?.search(q)
        val js = "(function(){var a=document.querySelector('a[href*=\\"/watch?v=\\"]');if(a){a.click();return 1}return 0})()"
        fun tryOpen(n: Int) {
            ui.postDelayed({
                if (isFinishing || mode != 2) return@postDelayed
                yt?.web?.evaluateJavascript(js) { r -> if (r != "1" && n < 4) tryOpen(n + 1) }
            }, 3000)
        }
        tryOpen(0)
    }

    // Điện thoại gửi {del, ins}: xoá `del` ký tự trước con trỏ rồi chèn `ins` vào ô nhập đang chọn của TV/YouTube.
    private fun applyTextEdit(delRaw: Int, ins: String) {
        val wv: WebView = (if (mode == 1) tv?.activeWeb else if (mode == 2) yt?.web else null) ?: return
        val del = delRaw.coerceIn(0, 1000000)
        if (del == 0 && ins.isEmpty()) return
        if (ins == "\n" && del == 0) {
            wv.requestFocus()
            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            return
        }
        wv.evaluateJavascript("(" + TEXT_EDIT_FN + ")(" + del + "," + JSONObject.quote(ins) + ");", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_FILE -> { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCb = null }
            REQ_VOICE -> if (resultCode == RESULT_OK) {
                val q = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!q.isNullOrBlank()) { if (mode != 2) setMode(2); yt?.search(q) }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3010) panels.refresh(true)
        if (requestCode == REQ_VOICE_PERM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) launchVoice()
    }

    // ---------------- Thanh trạng thái ----------------

    private fun refreshStatus() {
        val cal = Calendar.getInstance()
        tvTime.text = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val dow = when (cal.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> "CN"; else -> "T" + cal.get(Calendar.DAY_OF_WEEK) }
        val d = cal.get(Calendar.DAY_OF_MONTH); val m = cal.get(Calendar.MONTH) + 1; val y = cal.get(Calendar.YEAR)
        tvDate.text = "$dow $d/$m/$y"
        val l = LunarCalendar.solarToLunar(d, m, y)
        tvLunar.text = "ÂL " + l[0] + "/" + l[1] + (if (l[3] == 1) " nhuận" else "")

        // WiFi: trắng = đã kết nối, vàng = bật nhưng chưa kết nối, xám = tắt
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            tvWifi.setTextColor(if (onWifi) Color.WHITE else if (wm.isWifiEnabled) 0xFFFFC857.toInt() else 0xFF777788.toInt())
        } catch (e: Exception) { tvWifi.setTextColor(0xFF777788.toInt()) }
        // Bluetooth: xanh = bật, xám = tắt
        try {
            val on = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
            tvBt.setTextColor(if (on) 0xFF5AA9FF.toInt() else 0xFF777788.toInt())
        } catch (e: Exception) { tvBt.setTextColor(0xFF777788.toInt()) }
    }

    override fun onResume() { super.onResume(); hideSystemBars(); refreshStatus(); if (mode == 1) tv?.onResume() else if (mode == 2) yt?.onResume() }
    override fun onPause() { super.onPause(); tv?.onPause(); yt?.onPause() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { unregisterReceiver(batteryRx) } catch (e: Exception) { }
        panels.hide()
        transfer?.destroy()
        try { connWake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        tv?.destroy(); yt?.destroy()
        super.onDestroy()
    }
}
