package com.poolpathpro.app.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.poolpathpro.app.OverlayContract
import com.poolpathpro.app.physics.PoolPhysicsEngine
import com.poolpathpro.app.vision.ScreenBallDetector
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tela cheia do overlay. Desenha a mesa calibrável, as bolas, a mira e as trajetórias
 * calculadas pelo [PoolPhysicsEngine].
 *
 * Marcadores (bolas e mira) são guardados em coordenadas relativas à mesa (0..1),
 * então acompanham a mesa ao girar a tela. A mesa tem uma calibração salva por
 * orientação (retrato/paisagem), em frações do tamanho da tela.
 *
 * Nada é alocado dentro de onDraw(): tintas, textos e arrays são pré-alocados.
 * Com a captura ativa, tudo é desenhado só com contornos finos, para não
 * poluir a detecção (o overlay aparece no próprio quadro capturado).
 */
class PoolOverlayView(context: Context) : View(context) {

    companion object {
        private const val MAX_MARKERS = PoolPhysicsEngine.MAX_BALLS
        private const val DRAG_NONE = 0
        private const val DRAG_AIM = 1
        private const val DRAG_BALL = 2
        private const val DRAG_CORNER = 3
        private const val MIN_TABLE_DP = 120f

        private val VIRTUAL_COLORS = intArrayOf(
            0xFFFFEB3B.toInt(), 0xFF2196F3.toInt(), 0xFFF44336.toInt(), 0xFF9C27B0.toInt(),
            0xFFFF9800.toInt(), 0xFF4CAF50.toInt(), 0xFF8D3A2B.toInt(), 0xFF212121.toInt()
        )
        private val PATH_COLORS = intArrayOf(
            0xFF00E5FF.toInt(), 0xFF69F0AE.toInt(), 0xFFFF80AB.toInt(),
            0xFFB388FF.toInt(), 0xFFFFAB40.toInt(), 0xFFEEFF41.toInt()
        )
        private val CUE_RING_COLOR = 0xFF555555.toInt()
    }

    private val density = resources.displayMetrics.density

    /** Motor de física (também usado pelo serviço para ler o raio das bolas). */
    val engine = PoolPhysicsEngine()

    // ------------------------------------------------------------------
    // Estado público
    // ------------------------------------------------------------------

    /** true = marcadores e mesa podem ser movidos; false = toques passam para o app abaixo. */
    var editMode: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** false = Modo Manual (branca + alvo); true = Multi-bolas. */
    var multiMode: Boolean = false
        set(value) {
            field = value
            if (!value) ensureTwoMarkers()
            recompute()
            invalidate()
        }

    /** true enquanto a captura de tela alimenta as posições das bolas. */
    var captureActive: Boolean = false
        set(value) {
            field = value
            if (!value) hasDetectedColors = false
            invalidate()
        }

    var onStateChanged: (() -> Unit)? = null

    val tableLeftPx: Float get() = tableL
    val tableTopPx: Float get() = tableT
    val tableRightPx: Float get() = tableR
    val tableBottomPx: Float get() = tableB
    val ballRadiusPx: Float get() = engine.ballRadius

    /** Deslocamento da View na tela (para alinhar com o quadro capturado). */
    @Volatile
    var screenOffsetX = 0
        private set

    @Volatile
    var screenOffsetY = 0
        private set

    // ------------------------------------------------------------------
    // Estado interno
    // ------------------------------------------------------------------

    private var prefsRef: SharedPreferences? = null

    private var tableL = 0f
    private var tableT = 0f
    private var tableR = 0f
    private var tableB = 0f

    private val markU = FloatArray(MAX_MARKERS)
    private val markV = FloatArray(MAX_MARKERS)
    private var markerCount = 2
    private var aimU = 0.78f
    private var aimV = 0.30f

    private val ballPx = FloatArray(MAX_MARKERS)
    private val ballPy = FloatArray(MAX_MARKERS)
    private val ballColors = IntArray(MAX_MARKERS)
    private var hasDetectedColors = false
    private var visibleBalls = 0
    private var aimPx = 0f
    private var aimPy = 0f

    private var bMinX = 0f
    private var bMaxX = 0f
    private var bMinY = 0f
    private var bMaxY = 0f

    private var dragKind = DRAG_NONE
    private var dragIndex = 0
    private var grabDx = 0f
    private var grabDy = 0f
    private val snapX = FloatArray(MAX_MARKERS)
    private val snapY = FloatArray(MAX_MARKERS)
    private var snapAimX = 0f
    private var snapAimY = 0f

    private val loc = IntArray(2)
    private val infoText = StringBuilder(48)
    private val labels = Array(MAX_MARKERS) { it.toString() }

    // ------------------------------------------------------------------
    // Tintas (pré-alocadas)
    // ------------------------------------------------------------------

    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xDD4CAF50.toInt()
    }
    private val boundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x884CAF50.toInt()
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }
    private val aimLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val tangentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFC107.toInt()
    }
    private val chainPaints = Array(PATH_COLORS.size) { idx ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2.5f)
            strokeCap = Paint.Cap.ROUND
            color = PATH_COLORS[idx]
        }
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xFFFFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val aimMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFFFF5252.toInt()
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFD54F.toInt()
    }
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFFFFD54F.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f)
        color = Color.WHITE
    }

    init {
        for (i in 0 until 2) initMarker(i)
    }

    private fun dp(value: Float): Float = value * density

    private fun sq(value: Float): Float = value * value

    // ------------------------------------------------------------------
    // Marcadores padrão
    // ------------------------------------------------------------------

    private fun defaultU(i: Int): Float = when (i) {
        0 -> 0.20f
        1 -> 0.55f
        else -> 0.35f + 0.06f * (i % 5)
    }

    private fun defaultV(i: Int): Float = when (i) {
        0 -> 0.50f
        1 -> 0.50f
        else -> 0.20f + 0.12f * ((i / 5) % 4)
    }

    private fun initMarker(i: Int) {
        markU[i] = defaultU(i)
        markV[i] = defaultV(i)
        ballColors[i] = 0
    }

    private fun ensureTwoMarkers() {
        while (markerCount < 2) {
            initMarker(markerCount)
            markerCount++
        }
    }

    // ------------------------------------------------------------------
    // Persistência
    // ------------------------------------------------------------------

    fun loadState(prefs: SharedPreferences) {
        prefsRef = prefs
        markerCount = prefs.getInt("mk_n", 2).coerceIn(1, MAX_MARKERS)
        for (i in 0 until MAX_MARKERS) {
            markU[i] = prefs.getFloat("mk_u$i", defaultU(i))
            markV[i] = prefs.getFloat("mk_v$i", defaultV(i))
        }
        aimU = prefs.getFloat("aim_u", 0.78f)
        aimV = prefs.getFloat("aim_v", 0.30f)
        ensureTwoMarkers()
        if (width > 0 && height > 0) loadTable()
    }

    fun saveState(prefs: SharedPreferences) {
        val editor = prefs.edit()
        editor.putInt("mk_n", markerCount)
        for (i in 0 until MAX_MARKERS) {
            editor.putFloat("mk_u$i", markU[i])
            editor.putFloat("mk_v$i", markV[i])
        }
        editor.putFloat("aim_u", aimU)
        editor.putFloat("aim_v", aimV)
        if (width > 0 && height > 0) {
            val key = orientationKey()
            val w = width.toFloat()
            val h = height.toFloat()
            editor.putFloat("tbl_${key}_l", tableL / w)
            editor.putFloat("tbl_${key}_t", tableT / h)
            editor.putFloat("tbl_${key}_r", tableR / w)
            editor.putFloat("tbl_${key}_b", tableB / h)
        }
        editor.apply()
    }

    private fun orientationKey(): String = if (width >= height) "land" else "port"

    private fun loadTable() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 1f || h < 1f) return
        val prefs = prefsRef
        val key = orientationKey()
        if (prefs != null && prefs.contains("tbl_${key}_l")) {
            tableL = prefs.getFloat("tbl_${key}_l", 0.07f) * w
            tableT = prefs.getFloat("tbl_${key}_t", 0.2f) * h
            tableR = prefs.getFloat("tbl_${key}_r", 0.93f) * w
            tableB = prefs.getFloat("tbl_${key}_b", 0.8f) * h
            sanitizeTable()
        } else {
            defaultTable()
        }
        recompute()
        invalidate()
    }

    private fun defaultTable() {
        val w = width.toFloat()
        val h = height.toFloat()
        var tw = w * 0.86f
        var th = tw / 2f
        if (th > h * 0.78f) {
            th = h * 0.78f
            tw = th * 2f
        }
        tableL = (w - tw) / 2f
        tableR = tableL + tw
        tableT = (h - th) / 2f
        tableB = tableT + th
    }

    private fun sanitizeTable() {
        val w = width.toFloat()
        val h = height.toFloat()
        val minSize = dp(MIN_TABLE_DP)
        tableL = tableL.coerceIn(0f, max(0f, w - minSize))
        tableT = tableT.coerceIn(0f, max(0f, h - minSize))
        tableR = tableR.coerceIn(min(tableL + minSize, w), w)
        tableB = tableB.coerceIn(min(tableT + minSize, h), h)
    }

    /** Volta a mesa para o enquadramento padrão da orientação atual. */
    fun resetTable() {
        if (width < 1 || height < 1) return
        defaultTable()
        recompute()
        invalidate()
        onStateChanged?.invoke()
    }

    // ------------------------------------------------------------------
    // Física e bolas
    // ------------------------------------------------------------------

    fun applyPhysicsSettings(prefs: SharedPreferences) {
        engine.maxBounces = prefs.getInt(OverlayContract.KEY_MAX_BOUNCES, OverlayContract.DEF_MAX_BOUNCES)
        engine.cushionAngleOffsetDeg =
            prefs.getInt(OverlayContract.KEY_CUSHION_ANGLE, OverlayContract.DEF_CUSHION_ANGLE).toFloat()
        engine.cushionRestitution =
            prefs.getInt(OverlayContract.KEY_CUSHION_RESTITUTION, OverlayContract.DEF_CUSHION_RESTITUTION) / 100f
        engine.pathLengthTables =
            prefs.getInt(OverlayContract.KEY_PATH_LENGTH, OverlayContract.DEF_PATH_LENGTH).toFloat()
        engine.ballScale =
            prefs.getInt(OverlayContract.KEY_BALL_SCALE, OverlayContract.DEF_BALL_SCALE) / 100f
        recompute()
        invalidate()
    }

    /** Adiciona uma bola virtual (Modo Multi-bolas). Retorna false se não couber. */
    fun addVirtualBall(): Boolean {
        if (captureActive || markerCount >= MAX_MARKERS) return false
        initMarker(markerCount)
        markerCount++
        recompute()
        invalidate()
        onStateChanged?.invoke()
        return true
    }

    /** Remove a última bola alvo. Retorna false se só restar a branca. */
    fun removeVirtualBall(): Boolean {
        if (captureActive || markerCount <= 1) return false
        markerCount--
        recompute()
        invalidate()
        onStateChanged?.invoke()
        return true
    }

    /**
     * Aplica o resultado do detector (coordenadas de tela já divididas pela escala
     * de captura via [invScale]).
     */
    fun applyDetection(result: ScreenBallDetector.Result, invScale: Float) {
        if (!captureActive || !multiMode) return
        val w = tableR - tableL
        val h = tableB - tableT
        if (w < 1f || h < 1f) return

        getLocationOnScreen(loc)
        screenOffsetX = loc[0]
        screenOffsetY = loc[1]

        if (result.cueIndex >= 0) {
            val px = result.x[result.cueIndex] * invScale - loc[0]
            val py = result.y[result.cueIndex] * invScale - loc[1]
            markU[0] = (px - tableL) / w
            markV[0] = (py - tableT) / h
        }
        ballColors[0] = Color.WHITE

        var next = 1
        for (i in 0 until result.count) {
            if (i == result.cueIndex) continue
            if (next >= MAX_MARKERS) break
            val px = result.x[i] * invScale - loc[0]
            val py = result.y[i] * invScale - loc[1]
            markU[next] = (px - tableL) / w
            markV[next] = (py - tableT) / h
            ballColors[next] = result.color[i]
            next++
        }
        markerCount = max(1, next)
        hasDetectedColors = true
        recompute()
        invalidate()
    }

    private fun recompute() {
        val w = tableR - tableL
        val h = tableB - tableT
        if (w < 1f || h < 1f) {
            visibleBalls = 0
            return
        }

        engine.setTable(tableL, tableT, tableR, tableB)
        val r = engine.ballRadius
        bMinX = tableL + r
        bMaxX = tableR - r
        bMinY = tableT + r
        bMaxY = tableB - r
        if (bMinX > bMaxX) {
            bMinX = (tableL + tableR) * 0.5f
            bMaxX = bMinX
        }
        if (bMinY > bMaxY) {
            bMinY = (tableT + tableB) * 0.5f
            bMaxY = bMinY
        }

        val n = if (multiMode) markerCount else min(markerCount, 2)
        engine.setBallCount(0)
        for (i in 0 until n) {
            val px = (tableL + markU[i] * w).coerceIn(bMinX, bMaxX)
            val py = (tableT + markV[i] * h).coerceIn(bMinY, bMaxY)
            ballPx[i] = px
            ballPy[i] = py
            engine.setBall(i, px, py)
        }
        visibleBalls = n

        aimPx = tableL + aimU * w
        aimPy = tableT + aimV * h
        engine.simulate(aimPx, aimPy)
        updateInfoText()
    }

    private fun updateInfoText() {
        infoText.setLength(0)
        if (engine.ghostValid) {
            infoText.append("Corte ")
                .append(engine.cutAngleDeg.roundToInt())
                .append("°   Separação ")
                .append(engine.separationAngleDeg.roundToInt())
                .append("°")
        }
    }

    private fun ballColor(i: Int): Int = when {
        i == 0 -> Color.WHITE
        hasDetectedColors && ballColors[i] != 0 -> ballColors[i]
        else -> VIRTUAL_COLORS[(i - 1) % VIRTUAL_COLORS.size]
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) loadTable()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        getLocationOnScreen(loc)
        screenOffsetX = loc[0]
        screenOffsetY = loc[1]
    }

    // ------------------------------------------------------------------
    // Desenho (sem alocações)
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tableR - tableL < 1f || tableB - tableT < 1f) return

        val r = engine.ballRadius
        val thin = captureActive

        canvas.drawRect(tableL, tableT, tableR, tableB, tablePaint)
        canvas.drawRect(bMinX, bMinY, bMaxX, bMaxY, boundsPaint)

        // trajetórias de todas as bolas que se moveram
        val impact = engine.cueImpactIndex
        val dotRadius = dp(3f)
        for (b in 0 until visibleBalls) {
            if (!engine.wasMoved(b)) continue
            val count = engine.pathCount(b)
            for (k in 1 until count) {
                val paint = if (b == 0) {
                    if (impact >= 0 && k > impact) tangentPaint else aimLinePaint
                } else {
                    chainPaints[(b - 1) % chainPaints.size]
                }
                val x1 = engine.pathX(b, k)
                val y1 = engine.pathY(b, k)
                canvas.drawLine(engine.pathX(b, k - 1), engine.pathY(b, k - 1), x1, y1, paint)
                canvas.drawCircle(x1, y1, dotRadius, dotPaint)
            }
        }

        // bola fantasma e ponto de contato
        if (engine.ghostValid) {
            canvas.drawCircle(engine.ghostX, engine.ghostY, r, ghostPaint)
            canvas.drawCircle(engine.contactX, engine.contactY, dp(3.5f), dotPaint)
        }

        // bolas
        for (i in 0 until visibleBalls) {
            val x = ballPx[i]
            val y = ballPy[i]
            val color = ballColor(i)
            if (!thin) {
                fillPaint.color = color
                canvas.drawCircle(x, y, r, fillPaint)
            }
            ringPaint.color = if (thin) Color.WHITE else if (i == 0) CUE_RING_COLOR else Color.WHITE
            canvas.drawCircle(x, y, r, ringPaint)
            if (editMode && !thin && i > 0) {
                canvas.drawText(labels[i], x, y + labelPaint.textSize * 0.35f, labelPaint)
            }
        }

        // marcador da mira
        val ar = dp(13f)
        canvas.drawCircle(aimPx, aimPy, ar, aimMarkerPaint)
        canvas.drawLine(aimPx - ar * 1.6f, aimPy, aimPx + ar * 1.6f, aimPy, aimMarkerPaint)
        canvas.drawLine(aimPx, aimPy - ar * 1.6f, aimPx, aimPy + ar * 1.6f, aimMarkerPaint)

        // alças dos cantos da mesa (edição)
        if (editMode) {
            val hr = dp(10f)
            for (c in 0 until 4) {
                val cx = cornerX(c)
                val cy = cornerY(c)
                if (!thin) canvas.drawCircle(cx, cy, hr, handleFillPaint)
                canvas.drawCircle(cx, cy, hr, handleRingPaint)
            }
        }

        if (infoText.isNotEmpty()) {
            canvas.drawText(infoText, 0, infoText.length, tableL + dp(10f), tableT + dp(22f), textPaint)
        }
    }

    // ------------------------------------------------------------------
    // Toque (apenas no Modo Edição)
    // ------------------------------------------------------------------

    private fun cornerX(c: Int): Float = if (c == 0 || c == 3) tableL else tableR

    private fun cornerY(c: Int): Float = if (c == 0 || c == 1) tableT else tableB

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pickTarget(event.x, event.y)
                return dragKind != DRAG_NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragKind == DRAG_NONE) return false
                dragTo(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragKind == DRAG_NONE) return false
                dragKind = DRAG_NONE
                invalidate()
                onStateChanged?.invoke()
                return true
            }
        }
        return dragKind != DRAG_NONE
    }

    private fun pickTarget(x: Float, y: Float) {
        dragKind = DRAG_NONE
        val hit = dp(40f)
        var best = hit * hit

        var d = sq(aimPx - x) + sq(aimPy - y)
        if (d < best) {
            best = d
            dragKind = DRAG_AIM
            grabDx = aimPx - x
            grabDy = aimPy - y
        }

        if (!captureActive) {
            for (i in 0 until visibleBalls) {
                d = sq(ballPx[i] - x) + sq(ballPy[i] - y)
                if (d < best) {
                    best = d
                    dragKind = DRAG_BALL
                    dragIndex = i
                    grabDx = ballPx[i] - x
                    grabDy = ballPy[i] - y
                }
            }
        }

        for (c in 0 until 4) {
            val cx = cornerX(c)
            val cy = cornerY(c)
            d = sq(cx - x) + sq(cy - y)
            if (d < best) {
                best = d
                dragKind = DRAG_CORNER
                dragIndex = c
                grabDx = cx - x
                grabDy = cy - y
            }
        }

        if (dragKind == DRAG_CORNER) snapshotMarkers()
    }

    private fun dragTo(x: Float, y: Float) {
        val w = max(tableR - tableL, 1f)
        val h = max(tableB - tableT, 1f)
        when (dragKind) {
            DRAG_AIM -> {
                val px = (x + grabDx).coerceIn(0f, width.toFloat())
                val py = (y + grabDy).coerceIn(0f, height.toFloat())
                aimU = (px - tableL) / w
                aimV = (py - tableT) / h
            }
            DRAG_BALL -> {
                val px = (x + grabDx).coerceIn(bMinX, bMaxX)
                val py = (y + grabDy).coerceIn(bMinY, bMaxY)
                markU[dragIndex] = (px - tableL) / w
                markV[dragIndex] = (py - tableT) / h
            }
            DRAG_CORNER -> {
                moveCorner(dragIndex, x + grabDx, y + grabDy)
                restoreMarkersFromSnapshot()
            }
        }
        recompute()
        invalidate()
    }

    private fun moveCorner(corner: Int, px: Float, py: Float) {
        val minSize = dp(MIN_TABLE_DP)
        val cx = px.coerceIn(0f, width.toFloat())
        val cy = py.coerceIn(0f, height.toFloat())
        when (corner) {
            0 -> {
                tableL = min(cx, tableR - minSize)
                tableT = min(cy, tableB - minSize)
            }
            1 -> {
                tableR = max(cx, tableL + minSize)
                tableT = min(cy, tableB - minSize)
            }
            2 -> {
                tableR = max(cx, tableL + minSize)
                tableB = max(cy, tableT + minSize)
            }
            3 -> {
                tableL = min(cx, tableR - minSize)
                tableB = max(cy, tableT + minSize)
            }
        }
    }

    /** Ao calibrar a mesa, os marcadores ficam parados na tela (não acompanham os cantos). */
    private fun snapshotMarkers() {
        val w = tableR - tableL
        val h = tableB - tableT
        for (i in 0 until markerCount) {
            snapX[i] = tableL + markU[i] * w
            snapY[i] = tableT + markV[i] * h
        }
        snapAimX = aimPx
        snapAimY = aimPy
    }

    private fun restoreMarkersFromSnapshot() {
        val w = max(tableR - tableL, 1f)
        val h = max(tableB - tableT, 1f)
        for (i in 0 until markerCount) {
            markU[i] = (snapX[i] - tableL) / w
            markV[i] = (snapY[i] - tableT) / h
        }
        aimU = (snapAimX - tableL) / w
        aimV = (snapAimY - tableT) / h
    }
}
