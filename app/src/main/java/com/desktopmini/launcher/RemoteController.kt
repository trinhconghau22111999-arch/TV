package com.desktopmini.launcher

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject

/**
 * Nhan lenh tu app DK_TV (dien thoai) qua kenh truyen tep: {"type":"remote","action":...}
 *   key (up/down/left/right/ok), move (dx,dy), click, scroll (dy), screen (on), voice (q).
 * Con tro chuot la 1 cham tron ve de len tren cung [root]; nhap = tiep xuc gia lap gui vao [root].
 */
class RemoteController(
    private val act: Activity,
    private val root: FrameLayout,
    private val activeWeb: () -> WebView?,
    private val onVoice: (String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private var cx = 0f
    private var cy = 0f
    private var placed = false
    private val size = (28 * act.resources.displayMetrics.density).toInt()

    private val cursor = object : View(act) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.style = Paint.Style.FILL; p.color = 0xCCFFFFFF.toInt()
            c.drawCircle(r, r, r - 3f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.BLACK
            c.drawCircle(r, r, r - 3f, p)
        }
    }.apply { visibility = View.GONE }

    private val hideRunnable = Runnable { cursor.visibility = View.GONE }

    init { root.addView(cursor, FrameLayout.LayoutParams(size, size)) }

    private fun showCursor() {
        val w = root.width.toFloat().coerceAtLeast(1f)
        val h = root.height.toFloat().coerceAtLeast(1f)
        if (!placed) { cx = w / 2f; cy = h / 2f; placed = true }
        cursor.visibility = View.VISIBLE
        cursor.bringToFront()
        cursor.translationX = cx - size / 2f
        cursor.translationY = cy - size / 2f
        ui.removeCallbacks(hideRunnable)
        ui.postDelayed(hideRunnable, 6000)
    }

    fun handle(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        when (o.optString("action")) {
            "move" -> {
                val w = root.width.toFloat(); val h = root.height.toFloat()
                if (w <= 0f || h <= 0f) return
                if (!placed) { cx = w / 2f; cy = h / 2f; placed = true }
                cx = (cx + o.optInt("dx") * GAIN).coerceIn(0f, w - 1f)
                cy = (cy + o.optInt("dy") * GAIN).coerceIn(0f, h - 1f)
                showCursor()
            }
            "click" -> { showCursor(); tap() }
            "scroll" -> {
                val dy = o.optInt("dy")
                activeWeb()?.evaluateJavascript("window.scrollBy(0," + (-dy * 2) + ");", null)
            }
            "key" -> key(o.optString("key"))
            "screen" -> if (o.optBoolean("on", true)) wake() else lock()
            "voice" -> { val q = o.optString("q"); if (q.isNotBlank()) onVoice(q) }
        }
    }

    private fun tap() {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cx, cy, 0)
        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(down)
        ui.postDelayed({ root.dispatchTouchEvent(up); up.recycle() }, 60)
        down.recycle()
    }

    private fun key(k: String) {
        val code = when (k) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> return
        }
        activeWeb()?.requestFocus()
        act.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        act.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Tat man hinh that bang Device Admin lockNow(); chua cap quyen thi mo hop thoai xin quyen (xac nhan 1 lan tren TV). */
    private fun lock() {
        val dpm = act.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val cn = ComponentName(act, AdminReceiver::class.java)
        if (dpm.isAdminActive(cn)) {
            dpm.lockNow()
        } else {
            Toast.makeText(act, "Cần cấp quyền Quản trị thiết bị để tắt màn hình - bấm Kích hoạt trên TV", Toast.LENGTH_LONG).show()
            try {
                act.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Để điện thoại tắt màn hình TV từ xa"))
            } catch (e: Exception) { }
        }
    }

    @Suppress("DEPRECATION")
    private fun wake() {
        val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "tv:remote-wake")
        wl.acquire(3000)
    }

    companion object { private const val GAIN = 1.6f }
}
