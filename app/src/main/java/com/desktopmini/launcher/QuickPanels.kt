package com.desktopmini.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Bảng nhanh WiFi / Bluetooth ngay dưới thanh menu: bấm biểu tượng để mở, bấm lại để đóng.
 * Công tắc chỉ đổi trạng thái khi người dùng gạt; đóng bảng không làm bật/tắt gì.
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission", "NewApi")
class QuickPanels(private val act: Activity, host: FrameLayout, topPx: Int) {
    private val density = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val ui = Handler(Looper.getMainLooper())

    private val panel = LinearLayout(act)
    private val title = TextView(act)
    private val sw = Switch(act)
    private val status = TextView(act)
    private val list = LinearLayout(act)

    private var kind = 0              // 0 = đóng, 1 = WiFi, 2 = Bluetooth
    private var updating = false
    private var registered = false
    private val found = LinkedHashMap<String, BluetoothDevice>()

    private val wm: WifiManager get() = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val bt: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            if (i.action == BluetoothDevice.ACTION_FOUND) {
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (d != null) { found[d.address] = d; if (kind == 2) refresh(false) }
            } else {
                refresh(i.action == WifiManager.WIFI_STATE_CHANGED_ACTION || i.action == BluetoothAdapter.ACTION_STATE_CHANGED)
            }
        }
    }

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xF2181826.toInt())
        panel.setPadding(dp(12), dp(10), dp(12), dp(10))
        panel.visibility = View.GONE
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        title.setTextColor(Color.WHITE); title.textSize = 18f; title.typeface = Typeface.DEFAULT_BOLD
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(sw, LinearLayout.LayoutParams(-2, -2))
        panel.addView(head)
        status.setTextColor(0xFFBBBBCC.toInt()); status.textSize = 13f; status.setPadding(0, dp(4), 0, dp(6))
        panel.addView(status)
        val sv = ScrollView(act)
        list.orientation = LinearLayout.VERTICAL
        sv.addView(list)
        panel.addView(sv, LinearLayout.LayoutParams(-1, dp(240)))
        panel.addView(row("Mở cài đặt hệ thống") { openSystem() })
        val lp = FrameLayout.LayoutParams(dp(340), -2, Gravity.TOP or Gravity.END)
        lp.topMargin = topPx; lp.rightMargin = dp(4)
        host.addView(panel, lp)
        sw.setOnCheckedChangeListener { _, on -> if (!updating) onSwitch(on) }
    }

    // ---------------- Mở / đóng ----------------

    fun isOpen(): Boolean = panel.visibility == View.VISIBLE

    fun toggle(k: Int) { if (isOpen() && kind == k) hide() else show(k) }

    fun hide() {
        panel.visibility = View.GONE
        kind = 0
        unregister()
        try { bt?.cancelDiscovery() } catch (e: Exception) { }
    }

    private fun show(k: Int) {
        try { bt?.cancelDiscovery() } catch (e: Exception) { }
        unregister()
        kind = k
        found.clear()
        title.text = if (k == 1) "WiFi" else "Bluetooth"
        panel.visibility = View.VISIBLE
        register()
        refresh(true)
    }

    private fun register() {
        if (registered) return
        val f = IntentFilter()
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        f.addAction(BluetoothDevice.ACTION_FOUND)
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        act.registerReceiver(rx, f)
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        try { act.unregisterReceiver(rx) } catch (e: Exception) { }
        registered = false
    }

    fun refresh(scan: Boolean = true) {
        if (kind == 1) wifiRefresh(scan) else if (kind == 2) btRefresh(scan)
    }

    // ---------------- Tiện ích giao diện ----------------

    private fun row(t: String, onClick: () -> Unit): TextView {
        val v = TextView(act)
        v.text = t; v.setTextColor(Color.WHITE); v.textSize = 15f
        v.setBackgroundColor(0xFF2B2B3A.toInt())
        v.setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(4)
        v.layoutParams = lp
        v.setOnClickListener { onClick() }
        return v
    }

    private fun note(t: String) {
        val v = TextView(act)
        v.text = t; v.setTextColor(0xFF9999AA.toInt()); v.textSize = 13f
        v.setPadding(0, dp(8), 0, dp(4))
        list.addView(v)
    }

    private fun setSwitch(on: Boolean) { updating = true; sw.isChecked = on; updating = false }

    private fun toast(t: String) { Toast.makeText(act, t, Toast.LENGTH_SHORT).show() }

    private fun openSystem() {
        try {
            act.startActivity(Intent(if (kind == 1) Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) { toast("Không mở được cài đặt hệ thống") }
    }

    private fun has(p: String) = Build.VERSION.SDK_INT < 23 || act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Xin các quyền còn thiếu; trả về true nếu đã đủ. */
    private fun ensure(vararg perms: String): Boolean {
        val miss = perms.filter { !has(it) }
        if (miss.isEmpty()) return true
        if (Build.VERSION.SDK_INT >= 23) act.requestPermissions(miss.toTypedArray(), 3010)
        return false
    }

    // ---------------- Công tắc ----------------

    private fun onSwitch(on: Boolean) {
        if (kind == 1) {
            if (Build.VERSION.SDK_INT >= 29) {
                toast("Android này không cho app tự bật/tắt WiFi, đang mở cài đặt hệ thống")
                openSystem(); refresh(false); return
            }
            try { wm.isWifiEnabled = on } catch (e: Exception) { }
        } else if (kind == 2) {
            val a = bt
            if (a == null) { toast("Máy không có Bluetooth"); setSwitch(false); return }
            if (Build.VERSION.SDK_INT >= 33) {
                toast("Android này không cho app tự bật/tắt Bluetooth, đang mở cài đặt hệ thống")
                openSystem(); refresh(false); return
            }
            if (Build.VERSION.SDK_INT >= 31 && !ensure(Manifest.permission.BLUETOOTH_CONNECT)) { setSwitch(a.isEnabled); return }
            try { if (on) a.enable() else a.disable() } catch (e: Exception) { }
        }
        ui.postDelayed({ refresh(on) }, 800)
    }

    // ---------------- WiFi ----------------

    private fun wifiRefresh(scan: Boolean) {
        list.removeAllViews()
        val on = try { wm.isWifiEnabled } catch (e: Exception) { false }
        setSwitch(on)
        if (!on) { status.text = "WiFi đang tắt"; return }
        val info = try { wm.connectionInfo } catch (e: Exception) { null }
        val cur = if (info != null && info.networkId != -1) (info.ssid ?: "").trim('"') else ""
        status.text = if (cur.isNotEmpty() && cur != "<unknown ssid>") "Đang kết nối: " + cur else "Chưa kết nối"
        if (!ensure(Manifest.permission.ACCESS_FINE_LOCATION)) note("Cấp quyền vị trí để quét danh sách WiFi, rồi mở lại bảng này")
        if (scan) { try { wm.startScan() } catch (e: Exception) { } }
        val res: List<ScanResult> = try { wm.scanResults ?: emptyList() } catch (e: Exception) { emptyList() }
        val best = LinkedHashMap<String, ScanResult>()
        for (r in res.sortedByDescending { it.level }) {
            val s = r.SSID ?: ""
            if (s.isNotEmpty() && !best.containsKey(s)) best[s] = r
        }
        if (best.isEmpty()) { note("Chưa thấy mạng nào (đang quét...)"); return }
        for ((ssid, r) in best) {
            val secure = r.capabilities.contains("WEP") || r.capabilities.contains("WPA") || r.capabilities.contains("PSK") || r.capabilities.contains("EAP")
            val mark = if (ssid == cur) "✓ " else ""
            list.addView(row(mark + ssid + (if (secure) "   (có mật khẩu)" else "")) { connectWifi(ssid, secure) })
        }
    }

    private fun connectWifi(ssid: String, secure: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) { toast("Android này cần kết nối WiFi trong cài đặt hệ thống"); openSystem(); return }
        val saved = try { wm.configuredNetworks } catch (e: Exception) { null }
        val cfg = saved?.firstOrNull { it.SSID == "\"" + ssid + "\"" }
        if (cfg != null) { joinWifi(cfg.networkId, ssid); return }
        if (!secure) {
            val c = WifiConfiguration()
            c.SSID = "\"" + ssid + "\""
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            joinWifi(wm.addNetwork(c), ssid)
            return
        }
        val input = EditText(act)
        input.hint = "Mật khẩu WiFi"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        AlertDialog.Builder(act)
            .setTitle(ssid)
            .setView(input)
            .setPositiveButton("Kết nối") { _, _ ->
                val c = WifiConfiguration()
                c.SSID = "\"" + ssid + "\""
                c.preSharedKey = "\"" + input.text.toString() + "\""
                joinWifi(wm.addNetwork(c), ssid)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun joinWifi(id: Int, ssid: String) {
        if (id < 0) { toast("Không kết nối được " + ssid); return }
        try { wm.enableNetwork(id, true); wm.reconnect(); toast("Đang kết nối " + ssid) } catch (e: Exception) { toast("Không kết nối được " + ssid) }
    }

    // ---------------- Bluetooth ----------------

    private fun devName(d: BluetoothDevice): String =
        (try { d.name } catch (e: Exception) { null }) ?: d.address

    private fun btRefresh(scan: Boolean) {
        list.removeAllViews()
        val a = bt
        if (a == null) { setSwitch(false); status.text = "Máy không có Bluetooth"; return }
        if (Build.VERSION.SDK_INT >= 31 && !ensure(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
            status.text = "Cấp quyền Bluetooth rồi mở lại bảng này"; return
        }
        val on = try { a.isEnabled } catch (e: Exception) { false }
        setSwitch(on)
        if (!on) { status.text = "Bluetooth đang tắt"; return }
        status.text = "Bấm thiết bị để kết nối"
        if (scan) {
            if (Build.VERSION.SDK_INT < 31) ensure(Manifest.permission.ACCESS_FINE_LOCATION)
            try { if (a.isDiscovering) a.cancelDiscovery(); a.startDiscovery() } catch (e: Exception) { }
        }
        val bonded = try { a.bondedDevices ?: emptySet<BluetoothDevice>() } catch (e: Exception) { emptySet<BluetoothDevice>() }
        if (bonded.isNotEmpty()) {
            note("Đã ghép đôi")
            for (d in bonded) list.addView(row(devName(d)) { connectBt(d) })
        }
        val others = found.values.filter { !bonded.contains(it) }
        if (others.isNotEmpty()) {
            note("Thiết bị xung quanh")
            for (d in others) list.addView(row(devName(d)) { pairBt(d) })
        } else if (bonded.isEmpty()) note("Đang tìm thiết bị...")
    }

    private fun pairBt(d: BluetoothDevice) {
        try { d.createBond(); toast("Đang ghép đôi " + devName(d)) } catch (e: Exception) { toast("Không ghép đôi được") }
    }

    /** Kết nối thiết bị đã ghép: thử các hồ sơ âm thanh/tai nghe/bàn phím-chuột bằng reflection. */
    private fun connectBt(d: BluetoothDevice) {
        val a = bt ?: return
        for (profile in intArrayOf(2, 1, 4)) {
            try {
                a.getProfileProxy(act, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        try {
                            val m = proxy.javaClass.getDeclaredMethod("connect", BluetoothDevice::class.java)
                            m.isAccessible = true
                            m.invoke(proxy, d)
                        } catch (e: Exception) { }
                        try { a.closeProfileProxy(p, proxy) } catch (e: Exception) { }
                    }
                    override fun onServiceDisconnected(p: Int) { }
                }, profile)
            } catch (e: Exception) { }
        }
        toast("Đang kết nối " + devName(d))
        ui.postDelayed({ refresh(false) }, 2500)
    }
}
