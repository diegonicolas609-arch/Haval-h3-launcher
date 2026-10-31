package com.example.milauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Calendar
import kotlin.concurrent.thread

data class AppItem(val label: String, val pkg: String, val icon: Drawable)

class MainActivity : Activity(), LocationListener {

    // ---- Tema (cambia con la hora o manualmente) ----
    private var BG_TOP = 0; private var BG_BOT = 0
    private var DARK = 0; private var GRAY = 0
    private var CARD = 0; private var NAV = 0
    private var MAPBG = 0; private var ROAD = 0; private var DRAWER = 0
    private val ACCENT = Color.parseColor("#2F6BFF")
    private val LIME = Color.parseColor("#B7D63A")
    private var themeKey = ""

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val obd = ObdClient()
    private val allApps = mutableListOf<AppItem>()
    private val speedViews = mutableListOf<TextView>()
    private val rpmViews = mutableListOf<TextView>()
    private val tempViews = mutableListOf<TextView>()
    private val tabViews = mutableListOf<LinearLayout>()
    private var lastSpeed = 0
    private var currentTab = 0

    private lateinit var content: FrameLayout
    private lateinit var drawer: FrameLayout
    private lateinit var drawerAdapter: BaseAdapter
    private var dock: LinearLayout? = null
    private var obdStatusTv: TextView? = null
    private var resultTv: TextView? = null

    private var titleTv: TextView? = null
    private var artistTv: TextView? = null
    private var artIv: ImageView? = null
    private var playBtn: TextView? = null
    private var controller: MediaController? = null

    private val defaultFavs = "com.google.android.youtube,com.android.settings,com.google.android.apps.maps"

    // ---------------------------------------------------------------- ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        buildUi()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        startLocation()
        autoConnectObd()
        handler.post(poll); handler.post(obdTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll); handler.removeCallbacks(obdTick)
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
    }

    override fun onDestroy() { super.onDestroy(); obd.disconnect() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) drawer.visibility = View.GONE
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == 1) startLocation()
        if (c == 2 && r.firstOrNull() == PackageManager.PERMISSION_GRANTED) pickObd()
    }

    // ---------------------------------------------------------------- tema por hora
    /** Devuelve true si el tema cambió y hay que reconstruir la interfaz. */
    private fun applyTheme(): Boolean {
        val mode = prefs.getInt("mode", 0) // 0 auto, 1 claro, 2 oscuro
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        var period = when (h) { in 6..8 -> "amanecer"; in 9..16 -> "dia"; in 17..19 -> "atardecer"; else -> "noche" }
        if (mode == 1 && period == "noche") period = "dia"
        if (mode == 2) period = "noche"
        val key = "$mode-$period"
        if (key == themeKey) return false
        themeKey = key

        val dark = period == "noche"
        when (period) {
            "amanecer" -> { BG_TOP = c("#FFE8D1"); BG_BOT = c("#EAF1FF") }
            "dia" -> { BG_TOP = c("#F4F6F9"); BG_BOT = c("#E6EAF0") }
            "atardecer" -> { BG_TOP = c("#FFD9C2"); BG_BOT = c("#E9DDF2") }
            else -> { BG_TOP = c("#10141B"); BG_BOT = c("#1B2232") }
        }
        DARK = if (dark) c("#F1F3F6") else c("#1B1E23")
        GRAY = if (dark) c("#9AA3AF") else c("#6B7280")
        CARD = if (dark) c("#1F2531") else Color.WHITE
        NAV = if (dark) c("#151A23") else Color.WHITE
        MAPBG = if (dark) c("#2A3342") else c("#DDE3EA")
        ROAD = if (dark) c("#445066") else Color.WHITE
        DRAWER = if (dark) c("#12161D") else c("#F5F6F8")
        return true
    }

    private fun c(s: String) = Color.parseColor(s)

    private fun cycleMode() {
        val m = (prefs.getInt("mode", 0) + 1) % 3
        prefs.edit().putInt("mode", m).apply()
        toast(listOf("Tema automático (según la hora)", "Tema claro", "Tema oscuro")[m])
        themeKey = ""; applyTheme(); buildUi()
    }

    // ---------------------------------------------------------------- UI base
    private fun buildUi() {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOT))
        }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        main.addView(content, lp(-1, 0, 1f))
        main.addView(navBar(), lp(-1, dp(64), 0f))
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        drawer = buildDrawer()
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        showTab(currentTab)
    }

    private fun navBar(): View {
        tabViews.clear()
        val bar = LinearLayout(this).apply { setBackgroundColor(NAV) }
        listOf("🏠" to "Inicio", "🎛" to "Tablero", "🔗" to "Conexión", "🚗" to "Garaje", "⚠" to "Fallas")
            .forEachIndexed { i, (icon, name) ->
                val tab = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setOnClickListener { showTab(i) }
                    addView(tv(icon, 18f, DARK)); addView(tv(name, 11f, DARK))
                }
                tabViews.add(tab)
                bar.addView(tab, lp(0, -1, 1f))
            }
        return bar
    }

    private fun showTab(i: Int) {
        currentTab = i
        speedViews.clear(); rpmViews.clear(); tempViews.clear()
        titleTv = null; obdStatusTv = null; resultTv = null
        content.removeAllViews()
        content.addView(
            when (i) {
                0 -> homeView()
                1 -> dashPage()
                2 -> simplePage("Conexión", "Bluetooth y Wi-Fi").also { addConnButtons(it as LinearLayout) }
                3 -> simplePage("Garaje", "Haval H3 2011")
                else -> faultsPage()
            }
        )
        tabViews.forEachIndexed { idx, t -> t.alpha = if (idx == i) 1f else 0.45f }
        if (i == 0) { refreshDock(); updateMusic() }
    }

    // ---------------------------------------------------------------- inicio
    private fun homeView(): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        left.addView(tv(lastSpeed.toString(), 34f, DARK, true).also { speedViews.add(it) })
        left.addView(tv(" km/h", 14f, GRAY))
        left.addView(View(this), lp(dp(28), 1, 0f))
        left.addView(tv(obd.rpm.toString(), 34f, DARK, true).also { rpmViews.add(it) })
        left.addView(tv(" rpm", 14f, GRAY))
        header.addView(left, lp(0, -2, 1f))
        header.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 24f; setTextColor(DARK); gravity = Gravity.CENTER
        }, lp(0, -2, 1f))
        val right0 = FrameLayout(this)
        val icon = listOf("🌗", "☀", "🌙")[prefs.getInt("mode", 0)]
        right0.addView(tv(icon, 24f, DARK).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4)); setOnClickListener { cycleMode() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.END))
        header.addView(right0, lp(0, -2, 1f))
        v.addView(header, lp(-1, -2, 0f))

        val body = LinearLayout(this)
        val carBox = FrameLayout(this)
        carBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.haval_h3)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(40))
        }, FrameLayout.LayoutParams(-1, -1))
        carBox.addView(tv("Haval H3 2011", 13f, GRAY).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(8) })
        body.addView(carBox, lp(0, -1, 1.5f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(mapCard(), lp(-1, 0, 1f).apply { bottomMargin = dp(12) })
        right.addView(musicCard(), lp(-1, dp(120), 0f))
        body.addView(right, lp(0, -1, 1f).apply { leftMargin = dp(12) })
        v.addView(body, lp(-1, 0, 1f))

        dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = round(CARD, 40)
        }
        v.addView(dock, lp(-2, dp(64), 0f).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        return v
    }

    private fun mapCard(): View {
        val card = FrameLayout(this).apply {
            background = round(MAPBG, 20); clipToOutline = true
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))) }
                catch (_: Exception) { toast("No hay app de mapas") }
            }
        }
        val road = ROAD
        card.addView(object : View(this) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = road; strokeWidth = 10f; style = Paint.Style.STROKE }
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
            override fun onDraw(cv: Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                cv.drawLine(0f, h * .3f, w, h * .55f, p); cv.drawLine(w * .25f, 0f, w * .45f, h, p)
                cv.drawLine(w * .75f, 0f, w * .6f, h, p); cv.drawLine(0f, h * .8f, w, h * .75f, p)
                cv.drawCircle(w * .5f, h * .5f, 14f, dot)
            }
        }, FrameLayout.LayoutParams(-1, -1))
        card.addView(tv("Toca para abrir el mapa", 12f, DARK).apply { setPadding(dp(10), dp(8), dp(10), dp(8)) },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        return card
    }

    private fun musicCard(): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(CARD, 20)
            setOnClickListener {
                if (controller == null) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        artIv = ImageView(this).apply {
            background = round(if (CARD == Color.WHITE) c("#D5D9DF") else c("#2E3646"), 14)
            clipToOutline = true; scaleType = ImageView.ScaleType.CENTER_CROP
        }
        card.addView(artIv, lp(dp(84), dp(84), 0f))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleTv = tv("Sin reproducción", 16f, DARK, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        artistTv = tv("", 13f, GRAY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        col.addView(titleTv); col.addView(artistTv)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        playBtn = tv("▶", 20f, Color.BLACK).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(LIME) }
            setOnClickListener { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        }
        controls.addView(ctl("⏮", KeyEvent.KEYCODE_MEDIA_PREVIOUS), lp(dp(40), dp(40), 0f))
        controls.addView(playBtn, lp(dp(46), dp(46), 0f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        controls.addView(ctl("⏭", KeyEvent.KEYCODE_MEDIA_NEXT), lp(dp(40), dp(40), 0f))
        col.addView(controls, lp(-1, -2, 0f).apply { topMargin = dp(6) })
        card.addView(col, lp(0, -2, 1f).apply { leftMargin = dp(12) })
        return card
    }

    private fun ctl(sym: String, key: Int) = tv(sym, 18f, DARK).apply {
        gravity = Gravity.CENTER; setOnClickListener { media(key) }
    }

    private fun media(key: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        handler.postDelayed({ updateMusic() }, 300)
    }

    // ---------------------------------------------------------------- bucles periódicos
    private val poll = object : Runnable {
        override fun run() {
            if (applyTheme()) buildUi()
            updateMusic()
            handler.postDelayed(this, 1000)
        }
    }

    private val obdTick = object : Runnable {
        override fun run() {
            if (obd.connected) {
                if (obd.speed >= 0) setSpeed(obd.speed)
                rpmViews.forEach { it.text = obd.rpm.toString() }
                tempViews.forEach { it.text = if (obd.coolant >= -40 && obd.coolant != -1) "${obd.coolant} °C" else "-- °C" }
            }
            obdStatusTv?.text = obd.status
            handler.postDelayed(this, 400)
        }
    }

    private fun setSpeed(v: Int) { lastSpeed = v; speedViews.forEach { it.text = v.toString() } }

    private fun updateMusic() {
        val t = titleTv ?: return
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            controller = msm.getActiveSessions(ComponentName(this, MediaListenerService::class.java)).firstOrNull()
            val md = controller?.metadata
            t.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Sin reproducción"
            artistTv?.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            artIv?.setImageBitmap(md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART))
            playBtn?.text = if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) "⏸" else "▶"
        } catch (_: SecurityException) {
            t.text = "Activar música"
            artistTv?.text = "Toca y da acceso a notificaciones"
        }
    }

    // ---------------------------------------------------------------- velocidad GPS
    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (_: Exception) {}
    }

    override fun onLocationChanged(loc: Location) {
        if (!obd.connected) setSpeed(if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0)
    }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ---------------------------------------------------------------- OBD2
    private fun hasBtPerm() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun autoConnectObd() {
        val addr = prefs.getString("obd", null) ?: return
        if (obd.connected || obd.busy || !hasBtPerm()) return
        try {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(addr)?.let { obd.connect(it) }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun pickObd() {
        if (!hasBtPerm()) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2); return }
        val devs = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
        if (devs.isEmpty()) {
            toast("Primero empareja tu adaptador OBD2 (clave 1234 o 0000)")
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return
        }
        AlertDialog.Builder(this).setTitle("Elige tu adaptador OBD2")
            .setItems(devs.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                prefs.edit().putString("obd", devs[i].address).apply()
                obd.connect(devs[i])
            }.show()
    }

    private fun readFaults() {
        if (!obd.connected) { toast("Conecta el OBD2 primero"); return }
        resultTv?.text = "Leyendo…"
        thread {
            val r = try { obd.readDtcs() } catch (e: Exception) { null }
            runOnUiThread {
                resultTv?.text = when {
                    r == null -> "No se pudo leer"
                    r.isEmpty() -> "Sin fallas ✔"
                    else -> r.joinToString("   ")
                }
            }
        }
    }

    private fun clearFaults() {
        if (!obd.connected) { toast("Conecta el OBD2 primero"); return }
        AlertDialog.Builder(this).setTitle("Borrar fallas")
            .setMessage("Se borrarán los códigos y se apagará la luz Check Engine. Si la falla sigue, volverá a aparecer.")
            .setPositiveButton("Borrar") { _, _ ->
                thread { try { obd.clearDtcs() } catch (_: Exception) {}
                    runOnUiThread { resultTv?.text = "Fallas borradas" } }
            }.setNegativeButton("Cancelar", null).show()
    }

    // ---------------------------------------------------------------- otras pestañas
    private fun simplePage(title: String, sub: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(tv(title, 30f, DARK, true).apply { gravity = Gravity.CENTER })
        addView(tv(sub, 16f, GRAY).apply { gravity = Gravity.CENTER })
    }

    private fun dashPage(): View {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        p.addView(tv(lastSpeed.toString(), 96f, DARK, true).apply { gravity = Gravity.CENTER }.also { speedViews.add(it) })
        p.addView(tv("km/h", 18f, GRAY).apply { gravity = Gravity.CENTER })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row.addView(tv(obd.rpm.toString(), 40f, DARK, true).also { rpmViews.add(it) })
        row.addView(tv(" rpm", 16f, GRAY))
        row.addView(View(this), lp(dp(40), 1, 0f))
        row.addView(tv("-- °C", 40f, DARK, true).also { tempViews.add(it) })
        row.addView(tv(" motor", 16f, GRAY))
        p.addView(row, lp(-1, -2, 0f).apply { topMargin = dp(16) })
        return p
    }

    private fun faultsPage(): View {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        p.addView(tv("Fallas (OBD2)", 30f, DARK, true).apply { gravity = Gravity.CENTER })
        obdStatusTv = tv(obd.status, 14f, GRAY).apply { gravity = Gravity.CENTER }
        p.addView(obdStatusTv)
        resultTv = tv("", 20f, DARK, true).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(12), dp(24), dp(4)) }
        p.addView(resultTv)
        p.addView(btn("Conectar OBD2") { pickObd() })
        p.addView(btn("Leer fallas") { readFaults() })
        p.addView(btn("Borrar fallas") { clearFaults() })
        return p
    }

    private fun addConnButtons(p: LinearLayout) {
        p.addView(btn("Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        p.addView(btn("Wi-Fi") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
    }

    private fun btn(text: String, onClick: () -> Unit) = tv(text, 18f, Color.WHITE).apply {
        gravity = Gravity.CENTER; background = round(ACCENT, 30)
        setOnClickListener { onClick() }
        layoutParams = lp(dp(240), dp(52), 0f).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL }
    }

    // ---------------------------------------------------------------- dock y cajón
    private fun favorites() = (prefs.getString("favs", defaultFavs) ?: defaultFavs).split(",").filter { it.isNotEmpty() }

    private fun refreshDock() {
        val d = dock ?: return
        d.removeAllViews()
        d.addView(dockBtn("⠿") { drawer.visibility = View.VISIBLE })
        favorites().mapNotNull { pkg -> allApps.find { it.pkg == pkg } }.forEach { app ->
            d.addView(ImageView(this).apply {
                setImageDrawable(app.icon); setOnClickListener { launch(app.pkg) }
            }, lp(dp(44), dp(44), 0f).apply { leftMargin = dp(10) })
        }
        d.addView(dockBtn("+") { toast("Mantén pulsada una app en el cajón para fijarla"); drawer.visibility = View.VISIBLE },
            lp(dp(40), dp(40), 0f).apply { leftMargin = dp(10) })
    }

    private fun dockBtn(sym: String, onClick: () -> Unit) = tv(sym, 22f, DARK).apply {
        gravity = Gravity.CENTER; setOnClickListener { onClick() }
        layoutParams = lp(dp(40), dp(40), 0f)
    }

    private fun buildDrawer(): FrameLayout {
        val f = FrameLayout(this).apply { setBackgroundColor(DRAWER) }
        drawerAdapter = object : BaseAdapter() {
            override fun getCount() = allApps.size
            override fun getItem(p: Int) = allApps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val a = allApps[p]
                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    addView(ImageView(context).apply { setImageDrawable(a.icon) }, lp(dp(52), dp(52), 0f))
                    addView(tv(a.label, 12f, DARK).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                    })
                }
            }
        }
        val grid = GridView(this).apply {
            numColumns = 6; verticalSpacing = dp(16)
            setPadding(dp(16), dp(24), dp(16), dp(24)); clipToPadding = false
            adapter = drawerAdapter
            setOnItemClickListener { _, _, i, _ -> drawer.visibility = View.GONE; launch(allApps[i].pkg) }
            setOnItemLongClickListener { _, _, i, _ ->
                val pkg = allApps[i].pkg
                val favs = favorites().toMutableList()
                if (favs.remove(pkg)) toast("Quitada del dock") else { favs.add(pkg); toast("Fijada en el dock") }
                prefs.edit().putString("favs", favs.joinToString(",")).apply()
                refreshDock(); true
            }
        }
        f.addView(grid, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = packageManager.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { AppItem(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, it.loadIcon(packageManager)) }
            .sortedBy { it.label.lowercase() }
        allApps.clear(); allApps.addAll(list)
        drawerAdapter.notifyDataSetChanged()
        refreshDock()
    }

    private fun launch(pkg: String) { packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }

    // ---------------------------------------------------------------- utilidades
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun lp(w: Int, h: Int, weight: Float) = LinearLayout.LayoutParams(w, h, weight)
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
}
