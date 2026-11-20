package com.poolpathpro.app.vision

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Detector leve de bolas de sinuca, sem OpenCV. Trabalha só na região da mesa:
 *
 *  1. Lê os pixels da região (getPixels em um IntArray reutilizado).
 *  2. Descobre a cor do feltro pelo pico de um histograma RGB (4 bits por canal).
 *  3. Marca como "primeiro plano" as células cuja cor difere do feltro.
 *  4. Aplica transformada de distância (chanfro 3-4) na máscara. Cada bola vira
 *     um pico de valor ~ raio; bolas encostadas ainda geram picos separados.
 *     Picos grandes demais (caçapas, menus) e finos demais (taco, linhas) são ignorados.
 *  5. Classifica a bola branca (cor clara e pouco saturada) e guarda a cor média de cada bola.
 *
 * Os buffers são reaproveitados entre quadros. [detect] roda na thread de captura
 * e [copyLatestInto] pode ser chamado de outra thread com segurança.
 */
class ScreenBallDetector {

    /** Resultado em coordenadas do bitmap analisado (pixels). */
    class Result {
        val x = FloatArray(ScreenBallDetector.MAX_DETECTIONS)
        val y = FloatArray(ScreenBallDetector.MAX_DETECTIONS)
        val color = IntArray(ScreenBallDetector.MAX_DETECTIONS)
        var count = 0

        /** Índice da bola branca dentro do resultado, ou -1 se não foi encontrada. */
        var cueIndex = -1

        fun copyFrom(other: Result) {
            count = other.count
            cueIndex = other.cueIndex
            for (i in 0 until other.count) {
                x[i] = other.x[i]
                y[i] = other.y[i]
                color[i] = other.color[i]
            }
        }
    }

    companion object {
        const val MAX_DETECTIONS = 16
        private const val MAX_CANDIDATES = 512
        private const val HIST_BINS = 4096
        private const val INF = 0x3FFFFFFF
    }

    /** Soma de |dR|+|dG|+|dB| acima da qual um pixel deixa de ser "feltro". */
    var clothTolerance = 110

    private val lock = Any()
    private var pixels = IntArray(0)
    private var dist = IntArray(0)
    private val histCount = IntArray(HIST_BINS)
    private val histR = IntArray(HIST_BINS)
    private val histG = IntArray(HIST_BINS)
    private val histB = IntArray(HIST_BINS)
    private val candIdx = IntArray(MAX_CANDIDATES)
    private val candVal = IntArray(MAX_CANDIDATES)
    private val work = Result()
    private val latest = Result()

    /**
     * Analisa a região [left, top, right, bottom] do bitmap.
     * @param ballRadiusPx raio esperado da bola, em pixels do bitmap.
     * @return quantidade de bolas detectadas.
     */
    fun detect(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        ballRadiusPx: Float
    ): Int {
        work.count = 0
        work.cueIndex = -1

        if (bitmap.isRecycled || ballRadiusPx < 2f) {
            publish()
            return 0
        }

        val roiL = left.coerceIn(0, bitmap.width - 1)
        val roiT = top.coerceIn(0, bitmap.height - 1)
        val roiR = right.coerceIn(roiL + 1, bitmap.width)
        val roiB = bottom.coerceIn(roiT + 1, bitmap.height)
        val roiW = roiR - roiL
        val roiH = roiB - roiT
        if (roiW < 16 || roiH < 16) {
            publish()
            return 0
        }

        if (pixels.size < roiW * roiH) pixels = IntArray(roiW * roiH)
        bitmap.getPixels(pixels, 0, roiW, roiL, roiT, roiW, roiH)

        val cell = max(1, (ballRadiusPx / 4f).roundToInt())
        val cw = roiW / cell
        val ch = roiH / cell
        if (cw < 4 || ch < 4) {
            publish()
            return 0
        }
        val rCells = ballRadiusPx / cell
        if (dist.size < cw * ch) dist = IntArray(cw * ch)

        // 1) Cor dominante do feltro
        histCount.fill(0)
        histR.fill(0)
        histG.fill(0)
        histB.fill(0)
        val step = max(2, cell)
        var sy = 0
        while (sy < roiH) {
            val rowBase = sy * roiW
            var sx = 0
            while (sx < roiW) {
                val c = pixels[rowBase + sx]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                val bin = ((cr shr 4) shl 8) or ((cg shr 4) shl 4) or (cb shr 4)
                histCount[bin]++
                histR[bin] += cr
                histG[bin] += cg
                histB[bin] += cb
                sx += step
            }
            sy += step
        }
        var peakBin = 0
        var peakCount = 0
        for (i in 0 until HIST_BINS) {
            if (histCount[i] > peakCount) {
                peakCount = histCount[i]
                peakBin = i
            }
        }
        if (peakCount == 0) {
            publish()
            return 0
        }
        val clothR = histR[peakBin] / peakCount
        val clothG = histG[peakBin] / peakCount
        val clothB = histB[peakBin] / peakCount

        // 2) Máscara de primeiro plano (uma amostra por célula)
        val half = cell / 2
        for (cy in 0 until ch) {
            val rowBase = (cy * cell + half) * roiW
            for (cx in 0 until cw) {
                val c = pixels[rowBase + cx * cell + half]
                val diff = abs(((c shr 16) and 0xFF) - clothR) +
                    abs(((c shr 8) and 0xFF) - clothG) +
                    abs((c and 0xFF) - clothB)
                dist[cy * cw + cx] = if (diff > clothTolerance) INF else 0
            }
        }

        // 3) Transformada de distância (chanfro 3-4); fora da região = fundo
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                val idx = cy * cw + cx
                val v = dist[idx]
                if (v == 0) continue
                val l = if (cx > 0) dist[idx - 1] else 0
                val u = if (cy > 0) dist[idx - cw] else 0
                val ul = if (cx > 0 && cy > 0) dist[idx - cw - 1] else 0
                val ur = if (cy > 0 && cx < cw - 1) dist[idx - cw + 1] else 0
                dist[idx] = min(min(v, l + 3), min(u + 3, min(ul + 4, ur + 4)))
            }
        }
        for (cy in ch - 1 downTo 0) {
            for (cx in cw - 1 downTo 0) {
                val idx = cy * cw + cx
                val v = dist[idx]
                if (v == 0) continue
                val r = if (cx < cw - 1) dist[idx + 1] else 0
                val d = if (cy < ch - 1) dist[idx + cw] else 0
                val dr = if (cx < cw - 1 && cy < ch - 1) dist[idx + cw + 1] else 0
                val dl = if (cx > 0 && cy < ch - 1) dist[idx + cw - 1] else 0
                dist[idx] = min(min(v, r + 3), min(d + 3, min(dr + 4, dl + 4)))
            }
        }

        // 4) Picos locais com tamanho compatível com uma bola
        val minPeak = (rCells * 0.55f * 3f).toInt()
        val maxPeak = (rCells * 1.55f * 3f).toInt()
        var candCount = 0
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                val v = dist[cy * cw + cx]
                if (v < minPeak || v > maxPeak) continue
                if (!isLocalMax(cx, cy, cw, ch, v)) continue
                if (candCount < MAX_CANDIDATES) {
                    candIdx[candCount] = cy * cw + cx
                    candVal[candCount] = v
                    candCount++
                }
            }
        }

        // 5) Seleção por maior pico + supressão de vizinhos, centroide e cor
        val minSep = rCells * 1.5f
        val minSep2 = minSep * minSep
        val centroidRadius = max(1, ceil(rCells).toInt())
        val centroidRadius2 = centroidRadius * centroidRadius
        val colorRadius = max(2, (ballRadiusPx * 0.5f).toInt())
        var bestWhiteScore = -1
        var found = 0

        while (found < ScreenBallDetector.MAX_DETECTIONS) {
            var bestK = -1
            var bestV = 0
            for (k in 0 until candCount) {
                if (candVal[k] > bestV) {
                    bestV = candVal[k]
                    bestK = k
                }
            }
            if (bestK < 0) break

            val peakIdx = candIdx[bestK]
            val peakCx = peakIdx % cw
            val peakCy = peakIdx / cw
            candVal[bestK] = 0
            for (k in 0 until candCount) {
                if (candVal[k] > 0) {
                    val dx = (candIdx[k] % cw - peakCx).toFloat()
                    val dy = (candIdx[k] / cw - peakCy).toFloat()
                    if (dx * dx + dy * dy < minSep2) candVal[k] = 0
                }
            }

            // centroide do platô do pico
            var sumX = 0f
            var sumY = 0f
            var n = 0
            for (dy in -centroidRadius..centroidRadius) {
                for (dx in -centroidRadius..centroidRadius) {
                    if (dx * dx + dy * dy > centroidRadius2) continue
                    val nx = peakCx + dx
                    val ny = peakCy + dy
                    if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue
                    if (dist[ny * cw + nx] * 5 >= bestV * 4) {
                        sumX += nx
                        sumY += ny
                        n++
                    }
                }
            }
            val centerCx = if (n > 0) sumX / n else peakCx.toFloat()
            val centerCy = if (n > 0) sumY / n else peakCy.toFloat()
            val centerX = (centerCx + 0.5f) * cell
            val centerY = (centerCy + 0.5f) * cell

            // cor média no disco central
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var samples = 0
            val ccx = centerX.toInt()
            val ccy = centerY.toInt()
            var yy = -colorRadius
            while (yy <= colorRadius) {
                var xx = -colorRadius
                while (xx <= colorRadius) {
                    if (xx * xx + yy * yy <= colorRadius * colorRadius) {
                        val px = ccx + xx
                        val py = ccy + yy
                        if (px in 0 until roiW && py in 0 until roiH) {
                            val c = pixels[py * roiW + px]
                            sumR += (c shr 16) and 0xFF
                            sumG += (c shr 8) and 0xFF
                            sumB += c and 0xFF
                            samples++
                        }
                    }
                    xx += 2
                }
                yy += 2
            }
            val avgR = if (samples > 0) sumR / samples else clothR
            val avgG = if (samples > 0) sumG / samples else clothG
            val avgB = if (samples > 0) sumB / samples else clothB

            val minChannel = min(avgR, min(avgG, avgB))
            val maxChannel = max(avgR, max(avgG, avgB))
            if (minChannel >= 170 && maxChannel - minChannel <= 60 && minChannel > bestWhiteScore) {
                bestWhiteScore = minChannel
                work.cueIndex = found
            }

            work.x[found] = roiL + centerX
            work.y[found] = roiT + centerY
            work.color[found] = (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
            found++
        }

        work.count = found
        publish()
        return found
    }

    /** Copia o último resultado publicado (thread-safe). */
    fun copyLatestInto(target: Result) {
        synchronized(lock) {
            target.copyFrom(latest)
        }
    }

    /** Libera os buffers grandes (chamar ao parar a captura). */
    fun release() {
        synchronized(lock) {
            latest.count = 0
            latest.cueIndex = -1
        }
        work.count = 0
        work.cueIndex = -1
        pixels = IntArray(0)
        dist = IntArray(0)
    }

    private fun publish() {
        synchronized(lock) {
            latest.copyFrom(work)
        }
    }

    private fun isLocalMax(cx: Int, cy: Int, cw: Int, ch: Int, v: Int): Boolean {
        for (dy in -1..1) {
            val ny = cy + dy
            if (ny < 0 || ny >= ch) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx
                if (nx < 0 || nx >= cw) continue
                if (dist[ny * cw + nx] > v) return false
            }
        }
        return true
    }
}
