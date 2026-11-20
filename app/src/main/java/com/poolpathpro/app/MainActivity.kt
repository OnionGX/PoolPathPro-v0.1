package com.poolpathpro.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Contrato compartilhado entre a MainActivity e o OverlayService (Etapa 3):
 * ações, extras, chaves de preferências e flags de estado.
 */
object OverlayContract {
    const val SERVICE_CLASS = "com.poolpathpro.app.overlay.OverlayService"

    const val ACTION_START = "com.poolpathpro.app.action.START_OVERLAY"
    const val ACTION_STOP = "com.poolpathpro.app.action.STOP_OVERLAY"
    const val ACTION_START_CAPTURE = "com.poolpathpro.app.action.START_CAPTURE"
    const val ACTION_STOP_CAPTURE = "com.poolpathpro.app.action.STOP_CAPTURE"

    const val EXTRA_RESULT_CODE = "extra_result_code"
    const val EXTRA_RESULT_DATA = "extra_result_data"
    const val EXTRA_REQUEST_CAPTURE = "extra_request_capture"

    const val PREFS_NAME = "poolpathpro_prefs"
    const val KEY_MAX_BOUNCES = "max_bounces"
    const val KEY_CUSHION_ANGLE = "cushion_angle_deg"
    const val KEY_CUSHION_RESTITUTION = "cushion_restitution_pct"
    const val KEY_PATH_LENGTH = "path_length_tables"
    const val KEY_BALL_SCALE = "ball_scale_pct"
    const val KEY_CAPTURE_FPS = "capture_fps"

    const val DEF_MAX_BOUNCES = 3
    const val DEF_CUSHION_ANGLE = 0
    const val DEF_CUSHION_RESTITUTION = 90
    const val DEF_PATH_LENGTH = 4
    const val DEF_BALL_SCALE = 100
    const val DEF_CAPTURE_FPS = 6

    @Volatile
    var overlayRunning: Boolean = false

    @Volatile
    var captureRunning: Boolean = false
}

class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 1001
        const val REQ_NOTIFICATIONS = 1002
    }

    private val colorFelt = 0xFF0B6B3A.toInt()
    private val colorAccent = 0xFFFFD54F.toInt()
    private val colorBody = 0xFFCCCCCC.toInt()
    private val colorOk = 0xFF4CAF50.toInt()
    private val colorBad = 0xFFEF5350.toInt()

    private lateinit var prefs: SharedPreferences

    private lateinit var tvOverlayPermission: TextView
    private lateinit var tvNotificationPermission: TextView
    private lateinit var tvOverlayState: TextView
    private lateinit var tvCaptureState: TextView
    private lateinit var btnOverlayPermission: Button
    private lateinit var btnNotificationPermission: Button
    private lateinit var btnToggleOverlay: Button
    private lateinit var btnToggleCapture: Button

    private var captureRequestedFromOverlay = false

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(OverlayContract.PREFS_NAME, Context.MODE_PRIVATE)
        setContentView(buildContent())
        if (savedInstanceState == null) handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    /** O OverlayService pode abrir esta tela só para pedir a permissão de captura. */
    private fun handleLaunchIntent(launch: Intent?) {
        val received = launch ?: return
        if (!received.getBooleanExtra(OverlayContract.EXTRA_REQUEST_CAPTURE, false)) return
        received.removeExtra(OverlayContract.EXTRA_REQUEST_CAPTURE)
        captureRequestedFromOverlay = true
        if (!Settings.canDrawOverlays(this)) {
            toast("Conceda primeiro a permissão de sobreposição à tela.")
            captureRequestedFromOverlay = false
            return
        }
        requestScreenCapture()
    }

    // ------------------------------------------------------------------
    // Interface (construída em código: sem XML e sem AppCompat)
    // ------------------------------------------------------------------

    private fun buildContent(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(32))

        root.addView(makeTitle("PoolPathPro"))
        root.addView(makeBody("Overlay de estudo de ângulos e física de sinuca. Sem anúncios."))

        root.addView(makeSection("Permissões"))
        tvOverlayPermission = makeBody("")
        root.addView(tvOverlayPermission)
        btnOverlayPermission = makeButton("Permitir sobreposição à tela", true) { openOverlaySettings() }
        root.addView(btnOverlayPermission)
        tvNotificationPermission = makeBody("")
        root.addView(tvNotificationPermission)
        btnNotificationPermission = makeButton("Permitir notificações", true) { requestNotificationPermission() }
        root.addView(btnNotificationPermission)

        root.addView(makeSection("Overlay"))
        tvOverlayState = makeBody("")
        root.addView(tvOverlayState)
        btnToggleOverlay = makeButton("Iniciar overlay", true) { toggleOverlay() }
        root.addView(btnToggleOverlay)

        root.addView(makeSection("Modo Multi-bolas (captura de tela)"))
        root.addView(
            makeBody(
                "A captura detecta as bolas na mesa calibrada com poucos quadros por segundo " +
                    "para poupar RAM e CPU. Nada é gravado nem enviado."
            )
        )
        addSeek(
            root, "Taxa de captura", OverlayContract.KEY_CAPTURE_FPS,
            5, 8, OverlayContract.DEF_CAPTURE_FPS
        ) { "$it FPS" }
        tvCaptureState = makeBody("")
        root.addView(tvCaptureState)
        btnToggleCapture = makeButton("Ativar captura de tela", false) { toggleCapture() }
        root.addView(btnToggleCapture)

        root.addView(makeSection("Física das tabelas e bolas"))
        addSeek(
            root, "Rebotes por bola", OverlayContract.KEY_MAX_BOUNCES,
            0, 8, OverlayContract.DEF_MAX_BOUNCES
        ) { "$it" }
        addSeek(
            root, "Ângulo extra nas tabelas", OverlayContract.KEY_CUSHION_ANGLE,
            -20, 20, OverlayContract.DEF_CUSHION_ANGLE
        ) { if (it > 0) "+$it°" else "$it°" }
        addSeek(
            root, "Elasticidade das tabelas", OverlayContract.KEY_CUSHION_RESTITUTION,
            50, 100, OverlayContract.DEF_CUSHION_RESTITUTION
        ) { "$it%" }
        addSeek(
            root, "Alcance da tacada", OverlayContract.KEY_PATH_LENGTH,
            1, 10, OverlayContract.DEF_PATH_LENGTH
        ) { "$it comprimentos de mesa" }
        addSeek(
            root, "Tamanho das bolas", OverlayContract.KEY_BALL_SCALE,
            50, 200, OverlayContract.DEF_BALL_SCALE
        ) { "$it%" }

        root.addView(makeSection("Como usar"))
        root.addView(
            makeBody(
                "1. Conceda a sobreposição à tela.\n" +
                    "2. Toque em Iniciar overlay e abra o jogo.\n" +
                    "3. No botão flutuante, use o Modo Edição para posicionar a mesa e os " +
                    "marcadores. O Modo Bloqueio deixa os toques passarem para o jogo.\n" +
                    "4. No Modo Multi-bolas, adicione bolas virtuais ou ative a captura de tela."
            )
        )

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(root)
        return scroll
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun makeTitle(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 28f
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setTextColor(Color.WHITE)
        return tv
    }

    private fun makeSection(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 17f
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setTextColor(colorAccent)
        tv.setPadding(0, dp(26), 0, dp(4))
        return tv
    }

    private fun makeBody(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setTextColor(colorBody)
        tv.setPadding(0, dp(6), 0, dp(2))
        return tv
    }

    private fun makeButton(label: String, filled: Boolean, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.isAllCaps = false
        button.textSize = 15f
        button.setTextColor(Color.WHITE)

        val background = GradientDrawable()
        background.cornerRadius = dp(12).toFloat()
        if (filled) {
            background.setColor(colorFelt)
        } else {
            background.setColor(0xFF1E1E1E.toInt())
            background.setStroke(dp(1), colorFelt)
        }
        button.background = background
        button.setOnClickListener { onClick() }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(10)
        button.layoutParams = params
        return button
    }

    private fun addSeek(
        parent: LinearLayout,
        title: String,
        key: String,
        min: Int,
        max: Int,
        default: Int,
        format: (Int) -> String
    ) {
        val label = TextView(this)
        label.textSize = 14f
        label.setTextColor(Color.WHITE)
        label.setPadding(0, dp(14), 0, 0)

        val seek = SeekBar(this)
        seek.max = max - min
        val current = prefs.getInt(key, default).coerceIn(min, max)
        seek.progress = current - min
        label.text = "$title: ${format(current)}"

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + min
                label.text = "$title: ${format(value)}"
                if (fromUser) prefs.edit().putInt(key, value).apply()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                // Sem ação: o valor é salvo enquanto o dedo arrasta.
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                prefs.edit().putInt(key, seek.progress + min).apply()
            }
        })

        parent.addView(label)
        parent.addView(seek)
    }

    private fun refreshUi() {
        val canDraw = Settings.canDrawOverlays(this)
        tvOverlayPermission.text =
            if (canDraw) "✔ Sobreposição à tela: permitida" else "✖ Sobreposição à tela: não permitida"
        tvOverlayPermission.setTextColor(if (canDraw) colorOk else colorBad)
        btnOverlayPermission.visibility = if (canDraw) View.GONE else View.VISIBLE

        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tvNotificationPermission.visibility = View.VISIBLE
            tvNotificationPermission.text =
                if (needsNotification) "✖ Notificações: não permitidas" else "✔ Notificações: permitidas"
            tvNotificationPermission.setTextColor(if (needsNotification) colorBad else colorOk)
        } else {
            tvNotificationPermission.visibility = View.GONE
        }
        btnNotificationPermission.visibility = if (needsNotification) View.VISIBLE else View.GONE

        val overlayOn = OverlayContract.overlayRunning
        tvOverlayState.text = if (overlayOn) "Overlay em execução" else "Overlay parado"
        tvOverlayState.setTextColor(if (overlayOn) colorOk else colorBody)
        btnToggleOverlay.text = if (overlayOn) "Parar overlay" else "Iniciar overlay"
        btnToggleOverlay.isEnabled = canDraw || overlayOn

        val captureOn = OverlayContract.captureRunning
        tvCaptureState.text = if (captureOn) "Captura de tela ativa" else "Captura de tela inativa"
        tvCaptureState.setTextColor(if (captureOn) colorOk else colorBody)
        btnToggleCapture.text = if (captureOn) "Desativar captura de tela" else "Ativar captura de tela"
        btnToggleCapture.isEnabled = canDraw || captureOn
    }

    // ------------------------------------------------------------------
    // Ações
    // ------------------------------------------------------------------

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private fun serviceIntent(action: String): Intent =
        Intent().setClassName(packageName, OverlayContract.SERVICE_CLASS).setAction(action)

    /** Evita falha caso o serviço de overlay não exista nesta versão do app. */
    private fun isServiceInstalled(): Boolean =
        try {
            Class.forName(OverlayContract.SERVICE_CLASS)
            true
        } catch (e: ClassNotFoundException) {
            false
        }

    private fun toggleOverlay() {
        if (OverlayContract.overlayRunning) {
            startService(serviceIntent(OverlayContract.ACTION_STOP))
            OverlayContract.overlayRunning = false
            OverlayContract.captureRunning = false
            refreshUi()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Conceda primeiro a permissão de sobreposição à tela.")
            return
        }
        if (!isServiceInstalled()) {
            toast("Serviço de overlay indisponível nesta versão.")
            return
        }
        ContextCompat.startForegroundService(this, serviceIntent(OverlayContract.ACTION_START))
        OverlayContract.overlayRunning = true
        refreshUi()
        moveTaskToBack(true)
    }

    private fun toggleCapture() {
        if (OverlayContract.captureRunning) {
            startService(serviceIntent(OverlayContract.ACTION_STOP_CAPTURE))
            OverlayContract.captureRunning = false
            refreshUi()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Conceda primeiro a permissão de sobreposição à tela.")
            return
        }
        if (!isServiceInstalled()) {
            toast("Serviço de overlay indisponível nesta versão.")
            return
        }
        requestScreenCapture()
    }

    @Suppress("DEPRECATION")
    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return

        val fromOverlay = captureRequestedFromOverlay
        captureRequestedFromOverlay = false

        val granted = resultCode == Activity.RESULT_OK && data != null
        if (granted && data != null) {
            val start = serviceIntent(OverlayContract.ACTION_START_CAPTURE)
                .putExtra(OverlayContract.EXTRA_RESULT_CODE, resultCode)
                .putExtra(OverlayContract.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(this, start)
            OverlayContract.overlayRunning = true
            OverlayContract.captureRunning = true
            toast("Captura de tela ativada.")
        } else {
            toast("Permissão de captura de tela negada.")
        }

        refreshUi()
        if (fromOverlay) {
            finish()
        } else if (granted) {
            moveTaskToBack(true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) refreshUi()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
