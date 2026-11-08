package com.miauto.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), LocationListener {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var tvSpeed: TextView
    private lateinit var tvAlert: TextView
    private lateinit var btnMode: TextView
    private lateinit var tvOil: TextView
    private lateinit var tvCoolant: TextView
    private lateinit var tvCvt: TextView
    private lateinit var tvElm: TextView
    private lateinit var tvBt: TextView
    private lateinit var btnPlay: ImageView

    private val tick = object : Runnable {
        override fun run() { applyMode(); h.postDelayed(this, 60_000) }
    }
    private val canListener: () -> Unit = { runOnUiThread { refreshCan() } }
    private val obdListener: () -> Unit = { runOnUiThread { refreshObd() } }
    private val mediaListener: () -> Unit = { runOnUiThread { refreshMedia() } }

    private fun applyMode() = AppCompatDelegate.setDefaultNightMode(Prefs.appCompatMode(this))

    override fun onCreate(savedInstanceState: Bundle?) {
        applyMode()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvAlert = findViewById(R.id.tvAlert)
        btnMode = findViewById(R.id.btnMode)
        tvOil = findViewById(R.id.tvOil)
        tvCoolant = findViewById(R.id.tvCoolant)
        tvCvt = findViewById(R.id.tvCvt)
        tvElm = findViewById(R.id.tvElm)
        tvBt = findViewById(R.id.tvBt)
        btnPlay = findViewById(R.id.btnPlay)

        updateModeIcon()
        btnMode.setOnClickListener {
            Prefs.setMode(this, (Prefs.mode(this) + 1) % 3)
            updateModeIcon(); applyMode()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener { go(DiagActivity::class.java) }
        findViewById<View>(R.id.btnPrev).setOnClickListener { MediaBridge.prev(this) }
        btnPlay.setOnClickListener { MediaBridge.playPause(this) }
        findViewById<View>(R.id.btnNext).setOnClickListener { MediaBridge.next(this) }
        findViewById<View>(R.id.panelBt).setOnClickListener {
            if (!MediaBridge.hasAccess(this)) {
                try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
                catch (e: Exception) { toast("Activa el acceso a notificaciones para Mi Launcher en Ajustes") }
            } else {
                val bp = Prefs.btPkg(this)
                if (bp.isNotBlank()) try { openPkg(bp, null) } catch (e: Exception) {}
            }
        }
        buildTiles()

        val need = ArrayList<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31 && !granted("android.permission.BLUETOOTH_CONNECT"))
            need.add("android.permission.BLUETOOTH_CONNECT")
        if (need.isEmpty()) startGps()
        else ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    override fun onStart() {
        super.onStart()
        CanbusBridge.listeners.add(canListener)
        Obd.listeners.add(obdListener)
        MediaBridge.listeners.add(mediaListener)
        refreshCan()
        refreshObd()
    }

    override fun onStop() {
        super.onStop()
        CanbusBridge.listeners.remove(canListener)
        Obd.listeners.remove(obdListener)
        MediaBridge.listeners.remove(mediaListener)
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
        MediaBridge.start(this)
        refreshMedia()
    }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refreshMedia() {
        val title = MediaBridge.title()
        tvBt.text = when {
            MediaBridge.controller == null && !MediaBridge.hasAccess(this) -> "Toca aquí para activar el control"
            title.isBlank() -> "Sin reproducción"
            else -> title
        }
        btnPlay.setImageResource(if (MediaBridge.playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun fmt(k: String) = Obd.values[k]?.let { "${it.roundToInt()} °C" } ?: "-- °C"

    private fun refreshObd() {
        tvOil.text = fmt("OIL")
        tvCoolant.text = fmt("COOLANT")
        tvCvt.text = fmt("CVT")
        tvElm.text = Obd.status
    }

    private fun updateModeIcon() {
        btnMode.text = when (Prefs.mode(this)) { 1 -> "☀️"; 2 -> "🌙"; else -> "🌓" }
    }

    private fun refreshCan() {
        val names = linkedMapOf(
            "DOOR_FL" to "Del. izq.", "DOOR_FR" to "Del. der.",
            "DOOR_RL" to "Tras. izq.", "DOOR_RR" to "Tras. der.", "TRUNK" to "Cajuela"
        )
        val open = names.filter { CanbusBridge.state[it.key] == true }.values
        val parts = ArrayList<String>()
        if (CanbusBridge.state["REVERSA"] == true) parts.add("R · REVERSA")
        if (open.isNotEmpty()) parts.add("⚠ Abierto: " + open.joinToString(", "))
        tvAlert.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        tvAlert.text = parts.joinToString("    ")
        applyMode()
    }

    private data class Def(val icon: String, val label: String, val action: () -> Unit)

    private fun buildTiles() {
        val box = findViewById<LinearLayout>(R.id.tiles)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val m = (4 * d).toInt()
        val defs = listOf(
            Def("🗺️", "MAPS", { openPkg("com.google.android.apps.maps", Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))) }),
            Def("🎵", "MUSIC", { startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)) }),
            Def("📞", "PHONE", { startActivity(Intent(Intent.ACTION_DIAL)) }),
            Def("📻", "RADIO", {
                val p = Prefs.radioPkg(this)
                if (p.isBlank()) go(AppsActivity::class.java) else openPkg(p, null)
            }),
            Def("🚗", "VEHICLE", { go(DiagActivity::class.java) }),
            Def("⠿", "APPS", { go(AppsActivity::class.java) })
        )
        defs.forEachIndexed { idx, def ->
            val pkg = Prefs.tile(this, idx)
            var appInfo: android.content.pm.ApplicationInfo? = null
            if (pkg.isNotBlank()) {
                try { appInfo = packageManager.getApplicationInfo(pkg, 0) }
                catch (e: Exception) { Prefs.setTile(this, idx, "") }
            }
            val t = TextView(this)
            t.gravity = Gravity.CENTER
            t.textSize = 21f
            t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            t.setTextColor(ContextCompat.getColor(this, R.color.text))
            t.setBackgroundResource(R.drawable.bg_panel)
            t.maxLines = 2
            val action: () -> Unit
            if (appInfo != null) {
                val ic = packageManager.getApplicationIcon(appInfo)
                val sz = (46 * d).toInt()
                ic.setBounds(0, 0, sz, sz)
                t.setCompoundDrawables(null, ic, null, null)
                t.compoundDrawablePadding = (4 * d).toInt()
                t.text = packageManager.getApplicationLabel(appInfo).toString().uppercase()
                t.maxLines = 1
                t.ellipsize = android.text.TextUtils.TruncateAt.END
                action = { openPkg(pkg, null) }
            } else {
                t.text = "${def.icon}\n${def.label}"
                action = def.action
            }
            t.setOnClickListener {
                try { action() } catch (e: Exception) { toast("No se pudo abrir") }
            }
            t.setOnLongClickListener { chooseApp(idx); true }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(m, 0, m, 0)
            box.addView(t, lp)
        }
    }

    private fun chooseApp(idx: Int) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).sortedBy { it.loadLabel(pm).toString().lowercase() }
        val names = arrayOf("↺  Restaurar predeterminado") + apps.map { it.loadLabel(pm).toString() }
        AlertDialog.Builder(this)
            .setTitle("Elegir app para este acceso")
            .setItems(names) { _, which ->
                Prefs.setTile(this, idx, if (which == 0) "" else apps[which - 1].activityInfo.packageName)
                buildTiles()
            }
            .show()
    }

    private fun openPkg(pkg: String, fallback: Intent?) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i)
        else if (fallback != null) startActivity(fallback)
        else toast("App no encontrada: $pkg")
    }

    private fun go(cls: Class<*>) = startActivity(Intent(this, cls))
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @SuppressLint("MissingPermission")
    private fun startGps() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) startGps()
        Obd.start(this)
    }

    override fun onLocationChanged(l: Location) {
        val kmh = l.speed * 3.6f
        tvSpeed.text = (if (kmh < 3f) 0 else kmh.roundToInt()).toString()
    }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
