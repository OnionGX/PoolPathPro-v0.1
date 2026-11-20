package com.poolpathpro.app.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.poolpathpro.app.MainActivity
import com.poolpathpro.app.OverlayContract
import com.poolpathpro.app.R
import com.poolpathpro.app.vision.ScreenBallDetector
import kotlin.math.abs
import kotlin.math.max

/**
 * Serviço em primeiro plano que hospeda:
 *  - o overlay de tela cheia ([PoolOverlayView]);
 *  - o botão flutuante arrastável com o menu minimizável;
 *  - a captura de tela opcional (MediaProjection, 5-8 FPS) com o detector de bolas.
 *
 * Android 14+: o serviço roda como `specialUse` e passa a `specialUse|mediaProjection`
 * só depois da permissão de captura. Android 10-13: tipo `mediaProjection` ao capturar.
 */
class OverlayService : Service() {

    private companion object {
        const val CHANNEL_ID = "poolpathpro_overlay"
        const val NOTIFICATION_ID = 4711
        const val KEY_MENU_X = "menu_x"
        const val KEY_MENU_Y = "menu_y"
        const val CAPTURE_SCALE = 0.5f

        /** Janelas com alpha <= 0.8 deixam o toque passar no Android 12+ (modo Bloqueio). */
        const val OVERLAY_ALPHA = 0.75f
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private val metrics = DisplayMetrics()
    private var screenW = 0
    private var screenH = 0

    // Janelas e menu
    private var overlayView: PoolOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var menuRoot: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var groupMulti: LinearLayout? = null
    private var btnEdit: TextView? = null
    private var btnMode: TextView? = null
    private var btnCapture: TextView? = null

    private var editMode = true
    private var multiMode = false

    // Captura de tela
    private val captureLock = Any()
    private val detector = ScreenBallDetector()
    private val uiResult = ScreenBallDetector.Result()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureBitmap: Bitmap? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    @Volatile
    private var capturing = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
        when (key) {
            OverlayContract.KEY_MAX_BOUNCES,
            OverlayContract.KEY_CUSHION_ANGLE,
            OverlayContract.KEY_CUSHION_RESTITUTION,
            OverlayContract.KEY_PATH_LENGTH,
            OverlayContract.KEY_BALL_SCALE -> overlayView?.applyPhysicsSettings(sharedPrefs)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture()
        }
    }

    private val deliverRunnable = Runnable {
        val view = overlayView
        if (view != null && capturing) {
            detector.copyLatestInto(uiResult)
            view.applyDetection(uiResult, 1f / CAPTURE_SCALE)
        }
    }

    private val captureTick = object : Runnable {
        override fun run() {
            if (!capturing) return
            val started = SystemClock.elapsedRealtime()
            processFrame()
            val interval = frameIntervalMs()
            val delay = max(10L, interval - (SystemClock.elapsedRealtime() - started))
            captureHandler?.postDelayed(this, delay)
        }
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(OverlayContract.PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        readScreenSize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OverlayContract.ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            OverlayContract.ACTION_STOP_CAPTURE -> stopCapture()
            OverlayContract.ACTION_START_CAPTURE -> {
                if (ensureStarted()) {
                    startCapture(
                        intent.getIntExtra(OverlayContract.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED),
                        intent.getParcelableExtra(OverlayContract.EXTRA_RESULT_DATA)
                    )
                }
            }
            else -> ensureStarted()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        readScreenSize()
        val view = overlayView
        val params = overlayParams
        if (view != null && params != null) {
            params.width = screenW
            params.height = screenH
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: RuntimeException) {
                // janela já removida
            }
        }
        clampMenuToScreen()
        if (capturing) {
            synchronized(captureLock) { buildCaptureSurface() }
        }
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    /** Garante foreground + janelas. Retorna false se o serviço foi encerrado. */
    private fun ensureStarted(): Boolean {
        // startForeground primeiro: o sistema exige em até 5 s após startForegroundService
        if (!startForegroundCompat(capturing)) {
            toast("Não foi possível iniciar o serviço em primeiro plano.")
            shutdown()
            return false
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Permissão de sobreposição à tela não concedida.")
            shutdown()
            return false
        }
        if (overlayView == null) {
            createOverlay()
            createMenu()
        }
        OverlayContract.overlayRunning = overlayView != null
        return overlayView != null
    }

    private fun shutdown() {
        releaseAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseAll() {
        stopCapture()
        removeWindows()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        OverlayContract.overlayRunning = false
        OverlayContract.captureRunning = false
    }

    // ------------------------------------------------------------------
    // Notificação e Foreground Service
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, OverlayService::class.java).setAction(OverlayContract.ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Parar",
            stopPending
        ).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (capturing) "Overlay ativo com captura de tela" else "Overlay ativo")
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(stopAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /**
     * Android 14+: specialUse (+ mediaProjection quando há captura).
     * Android 10-13: mediaProjection só durante a captura; caso contrário, tipos do manifest.
     */
    private fun startForegroundCompat(withProjection: Boolean): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(NOTIFICATION_ID, notification, type)
            } else if (withProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Janelas: overlay de tela cheia e menu flutuante
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun readScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenW = bounds.width()
            screenH = bounds.height()
        } else {
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun overlayFlags(edit: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!edit) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun createOverlay() {
        if (overlayView != null) return
        val view = PoolOverlayView(this)
        view.onStateChanged = { view.saveState(prefs) }
        view.loadState(prefs)
        view.applyPhysicsSettings(prefs)
        view.editMode = editMode
        view.multiMode = multiMode

        val params = WindowManager.LayoutParams(
            screenW,
            screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(editMode),
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        params.alpha = OVERLAY_ALPHA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            overlayParams = params
        } catch (e: RuntimeException) {
            toast("Não foi possível exibir o overlay.")
        }
    }

    private fun menuButton(label: String, onClick: () -> Unit): TextView {
        val button = TextView(this)
        button.text = label
        button.gravity = Gravity.CENTER
        button.textSize = 14f
        button.setTextColor(Color.WHITE)
        button.setPadding(dp(10), dp(10), dp(10), dp(10))
        val background = GradientDrawable()
        background.cornerRadius = dp(10).toFloat()
        background.setColor(0xFF0B6B3A.toInt())
        button.background = background
        button.setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(6)
        button.layoutParams = params
        return button
    }

    private fun createMenu() {
        if (menuRoot != null) return
        val bubbleSize = dp(48)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val bubble = TextView(this)
        bubble.text = "8"
        bubble.gravity = Gravity.CENTER
        bubble.textSize = 20f
        bubble.setTypeface(bubble.typeface, Typeface.BOLD)
        bubble.setTextColor(Color.BLACK)
        val bubbleBg = GradientDrawable()
        bubbleBg.shape = GradientDrawable.OVAL
        bubbleBg.setColor(0xFFFFD54F.toInt())
        bubbleBg.setStroke(dp(2), Color.WHITE)
        bubble.background = bubbleBg
        bubble.layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)
        bubble.setOnTouchListener(BubbleTouchListener())
        root.addView(bubble)

        val panelLayout = LinearLayout(this)
        panelLayout.orientation = LinearLayout.VERTICAL
        val panelBg = GradientDrawable()
        panelBg.cornerRadius = dp(14).toFloat()
        panelBg.setColor(0xEE1E1E1E.toInt())
        panelLayout.background = panelBg
        panelLayout.setPadding(dp(8), dp(4), dp(8), dp(10))
        panelLayout.visibility = View.GONE
        val panelParams = LinearLayout.LayoutParams(dp(176), LinearLayout.LayoutParams.WRAP_CONTENT)
        panelParams.topMargin = dp(6)
        panelLayout.layoutParams = panelParams

        val edit = menuButton("") { setEditMode(!editMode) }
        val mode = menuButton("") { setMultiMode(!multiMode) }
        panelLayout.addView(edit)
        panelLayout.addView(mode)

        val multi = LinearLayout(this)
        multi.orientation = LinearLayout.VERTICAL
        multi.visibility = View.GONE
        multi.addView(menuButton("+ Bola") {
            val view = overlayView
            if (capturing) {
                toast("Desligue a captura para adicionar bolas virtuais.")
            } else if (view != null && !view.addVirtualBall()) {
                toast("Limite de bolas atingido.")
            }
        })
        multi.addView(menuButton("− Bola") { overlayView?.removeVirtualBall() })
        val capture = menuButton("") { requestCaptureFromMenu() }
        multi.addView(capture)
        panelLayout.addView(multi)

        panelLayout.addView(menuButton("Resetar mesa") { overlayView?.resetTable() })
        panelLayout.addView(menuButton("Fechar overlay") { shutdown() })
        root.addView(panelLayout)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = prefs.getInt(KEY_MENU_X, dp(8))
        params.y = prefs.getInt(KEY_MENU_Y, dp(140))

        try {
            windowManager.addView(root, params)
            menuRoot = root
            menuParams = params
            panel = panelLayout
            groupMulti = multi
            btnEdit = edit
            btnMode = mode
            btnCapture = capture
            clampMenuToScreen()
            refreshMenu()
        } catch (e: RuntimeException) {
            toast("Não foi possível exibir o menu flutuante.")
        }
    }

    private fun refreshMenu() {
        btnEdit?.text = if (editMode) "Modo: Edição" else "Modo: Bloqueio"
        btnMode?.text = if (multiMode) "Operação: Multi-bolas" else "Operação: Manual"
        groupMulti?.visibility = if (multiMode) View.VISIBLE else View.GONE
        btnCapture?.text = if (capturing) "Captura: ligada" else "Captura: desligada"
    }

    private fun setEditMode(value: Boolean) {
        editMode = value
        val view = overlayView
        val params = overlayParams
        if (view != null && params != null) {
            view.editMode = value
            params.flags = overlayFlags(value)
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: RuntimeException) {
                // janela já removida
            }
        }
        refreshMenu()
    }

    private fun setMultiMode(value: Boolean) {
        multiMode = value
        overlayView?.multiMode = value
        if (!value && capturing) stopCapture()
        refreshMenu()
    }

    private fun clampMenuToScreen() {
        val root = menuRoot ?: return
        val params = menuParams ?: return
        val bubbleSize = dp(48)
        params.x = params.x.coerceIn(0, max(0, screenW - bubbleSize))
        params.y = params.y.coerceIn(0, max(0, screenH - bubbleSize))
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: RuntimeException) {
            // janela já removida
        }
    }

    private fun removeWindows() {
        menuRoot?.let {
            try {
                windowManager.removeView(it)
            } catch (e: RuntimeException) {
                // já removida
            }
        }
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: RuntimeException) {
                // já removida
            }
        }
        menuRoot = null
        menuParams = null
        panel = null
        groupMulti = null
        btnEdit = null
        btnMode = null
        btnCapture = null
        overlayView = null
        overlayParams = null
    }

    /** Arrastar move o botão; um toque simples abre/fecha o menu. */
    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private val slop = dp(8).toFloat()

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = menuParams ?: return false
            val root = menuRoot ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clampMenuToScreen()
                        windowManager.updateViewLayout(root, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt(KEY_MENU_X, params.x).putInt(KEY_MENU_Y, params.y).apply()
                    } else {
                        val p = panel
                        if (p != null) {
                            p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        }
                    }
                    return true
                }
            }
            return false
        }
    }

    // ------------------------------------------------------------------
    // Captura de tela (MediaProjection, 5-8 FPS) e detecção
    // ------------------------------------------------------------------

    /** Só uma Activity pode pedir a permissão de captura: abre a MainActivity para isso. */
    private fun requestCaptureFromMenu() {
        if (capturing) {
            stopCapture()
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(OverlayContract.EXTRA_REQUEST_CAPTURE, true)
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            toast("Não foi possível abrir o pedido de captura de tela.")
        }
    }

    private fun frameIntervalMs(): Long {
        val fps = prefs.getInt(OverlayContract.KEY_CAPTURE_FPS, OverlayContract.DEF_CAPTURE_FPS)
            .coerceIn(5, 8)
        return 1000L / fps
    }

    private fun startCapture(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            toast("Permissão de captura de tela negada.")
            return
        }
        stopCapture()

        // O tipo mediaProjection precisa estar ativo ANTES de getMediaProjection()
        if (!startForegroundCompat(true)) {
            startForegroundCompat(false)
            toast("Falha ao iniciar a captura de tela.")
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: RuntimeException) {
            null
        }
        if (newProjection == null) {
            startForegroundCompat(false)
            toast("Falha ao iniciar a captura de tela.")
            return
        }

        projection = newProjection
        // Android 14+: o callback precisa ser registrado antes de createVirtualDisplay()
        newProjection.registerCallback(projectionCallback, mainHandler)

        val thread = HandlerThread("PoolCapture", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        captureThread = thread
        captureHandler = Handler(thread.looper)

        synchronized(captureLock) { buildCaptureSurface() }

        capturing = true
        OverlayContract.captureRunning = true
        multiMode = true
        overlayView?.multiMode = true
        overlayView?.captureActive = true
        startForegroundCompat(true)
        refreshMenu()
        captureHandler?.postDelayed(captureTick, frameIntervalMs())
    }

    /** Cria (ou redimensiona após girar a tela) o VirtualDisplay e o ImageReader em escala reduzida. */
    private fun buildCaptureSurface() {
        val current = projection ?: return
        readScreenSize()
        val capW = max(16, (screenW * CAPTURE_SCALE).toInt())
        val capH = max(16, (screenH * CAPTURE_SCALE).toInt())
        val dpi = resources.displayMetrics.densityDpi

        val previousReader = imageReader
        val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val display = virtualDisplay
        if (display == null) {
            virtualDisplay = current.createVirtualDisplay(
                "PoolPathCapture", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        } else {
            display.surface = reader.surface
            display.resize(capW, capH, dpi)
        }

        previousReader?.close()
        captureBitmap?.recycle()
        captureBitmap = null
    }

    private fun processFrame() {
        synchronized(captureLock) {
            val reader = imageReader ?: return
            val view = overlayView ?: return
            val image = try {
                reader.acquireLatestImage()
            } catch (e: RuntimeException) {
                null
            } ?: return

            var frame: Bitmap? = null
            try {
                val plane = image.planes[0]
                val bitmapWidth = plane.rowStride / plane.pixelStride
                var bitmap = captureBitmap
                if (bitmap == null || bitmap.isRecycled ||
                    bitmap.width != bitmapWidth || bitmap.height != image.height
                ) {
                    bitmap?.recycle()
                    bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
                    captureBitmap = bitmap
                }
                val buffer = plane.buffer
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
                frame = bitmap
            } catch (e: RuntimeException) {
                frame = null
            } finally {
                image.close()
            }

            val ready = frame ?: return
            val offX = view.screenOffsetX
            val offY = view.screenOffsetY
            detector.detect(
                ready,
                ((view.tableLeftPx + offX) * CAPTURE_SCALE).toInt(),
                ((view.tableTopPx + offY) * CAPTURE_SCALE).toInt(),
                ((view.tableRightPx + offX) * CAPTURE_SCALE).toInt(),
                ((view.tableBottomPx + offY) * CAPTURE_SCALE).toInt(),
                view.ballRadiusPx * CAPTURE_SCALE
            )
            mainHandler.post(deliverRunnable)
        }
    }

    private fun stopCapture() {
        if (!capturing && projection == null) return
        capturing = false

        captureHandler?.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacks(deliverRunnable)

        synchronized(captureLock) {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            captureBitmap?.recycle()
            captureBitmap = null
        }

        val stopped = projection
        projection = null
        if (stopped != null) {
            stopped.unregisterCallback(projectionCallback)
            stopped.stop()
        }

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        detector.release()

        overlayView?.captureActive = false
        OverlayContract.captureRunning = false
        if (overlayView != null) startForegroundCompat(false)
        refreshMenu()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
