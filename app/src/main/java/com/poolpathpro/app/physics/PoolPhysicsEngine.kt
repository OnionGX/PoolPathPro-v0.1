package com.poolpathpro.app.physics

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor de física 2D para estudo de sinuca (colisões elásticas em cadeia).
 *
 * Funcionamento: simulação orientada a eventos (sem passos de tempo fixos).
 * Cada bola em movimento avança em linha reta até o PRÓXIMO evento global:
 *   - bola x bola (solução exata da equação quadrática de distância),
 *   - bola x tabela (retângulo interno da mesa, já descontado o raio),
 *   - bola parou (acabou o "orçamento" de distância).
 *
 * Atrito: cada bola tem uma distância restante `rem`. Sob atrito constante, a
 * distância de parada é proporcional a v², então a velocidade atual é
 * v = sqrt(rem / pathBudget) (a bola branca começa com v = 1).
 *
 * Colisão bola x bola: massas iguais, impulso ao longo da linha dos centros com
 * coeficiente de restituição [ballRestitution] (1.0 = perfeitamente elástica,
 * onde a branca desvia exatamente 90° da bola alvo).
 *
 * Tabelas: reflexão especular com perda de energia ([cushionRestitution]) e um
 * desvio angular configurável ([cushionAngleOffsetDeg]) que simula efeito/atrito.
 *
 * Todas as estruturas são pré-alocadas: [simulate] e os acessores de resultado
 * não alocam memória, então podem ser chamados com segurança durante o toque
 * e lidos dentro de onDraw(). Não é thread-safe: use sempre na mesma thread.
 */
class PoolPhysicsEngine {

    companion object {
        const val MAX_BALLS = 16
        const val MAX_POINTS = 24
        const val CUE_BALL = 0

        /** Raio da bola / lado maior da mesa (mesa de 9 pés: 28,575 mm / 2540 mm). */
        const val RADIUS_TO_LONG_SIDE = 0.01125f

        private const val MAX_EVENTS = 256
        private const val MIN_REMAINING = 0.5f
        private const val EPS = 1e-6f
        private const val MAX_CUSHION_ANGLE_RAD = 1.5358897f // 88 graus
        private const val RAD_TO_DEG = 57.29578f
        private const val DEG_TO_RAD = 0.017453292f

        private const val EVENT_NONE = 0
        private const val EVENT_STOP = 1
        private const val EVENT_WALL_X = 2
        private const val EVENT_WALL_Y = 3
        private const val EVENT_BALL = 4
    }

    // ------------------------------------------------------------------
    // Configuração
    // ------------------------------------------------------------------

    /** Restituição bola x bola (1.0 = elástica ideal). */
    var ballRestitution = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 1.0f)
        }

    /** Fração de velocidade mantida após bater na tabela. */
    var cushionRestitution = 0.90f
        set(value) {
            field = value.coerceIn(0.3f, 1.0f)
        }

    /** Desvio angular extra aplicado em cada rebote (graus, + abre, - fecha). */
    var cushionAngleOffsetDeg = 0f
        set(value) {
            field = value.coerceIn(-30f, 30f)
        }

    /** Quantidade de rebotes permitidos por bola (no próximo contato ela para). */
    var maxBounces = 3
        set(value) {
            field = value.coerceIn(0, 12)
        }

    /** Alcance total da tacada, em comprimentos da mesa. */
    var pathLengthTables = 4.0f
        set(value) {
            field = value.coerceIn(0.5f, 20f)
        }

    /** Multiplicador do raio padrão das bolas (ajuste fino para a mesa do jogo). */
    var ballScale = 1.0f
        set(value) {
            field = value.coerceIn(0.3f, 3f)
        }

    // ------------------------------------------------------------------
    // Mesa (linhas de contato das tabelas)
    // ------------------------------------------------------------------

    var tableLeft = 0f
        private set
    var tableTop = 0f
        private set
    var tableRight = 0f
        private set
    var tableBottom = 0f
        private set

    fun setTable(left: Float, top: Float, right: Float, bottom: Float) {
        tableLeft = min(left, right)
        tableRight = max(left, right)
        tableTop = min(top, bottom)
        tableBottom = max(top, bottom)
    }

    /** Raio atual das bolas em pixels. */
    val ballRadius: Float
        get() = max(
            2f,
            max(tableRight - tableLeft, tableBottom - tableTop) * RADIUS_TO_LONG_SIDE * ballScale
        )

    // ------------------------------------------------------------------
    // Bolas (índice 0 = branca)
    // ------------------------------------------------------------------

    var ballCount = 0
        private set

    private val startX = FloatArray(MAX_BALLS)
    private val startY = FloatArray(MAX_BALLS)

    fun setBall(index: Int, x: Float, y: Float) {
        if (index < 0 || index >= MAX_BALLS) return
        startX[index] = x
        startY[index] = y
        if (index >= ballCount) ballCount = index + 1
    }

    /** Adiciona uma bola virtual. Retorna o índice ou -1 se estiver cheio. */
    fun addBall(x: Float, y: Float): Int {
        if (ballCount >= MAX_BALLS) return -1
        val index = ballCount
        startX[index] = x
        startY[index] = y
        ballCount = index + 1
        return index
    }

    /** Remove uma bola alvo (a branca, índice 0, não pode ser removida). */
    fun removeBall(index: Int) {
        if (index < 1 || index >= ballCount) return
        for (k in index until ballCount - 1) {
            startX[k] = startX[k + 1]
            startY[k] = startY[k + 1]
        }
        ballCount--
    }

    /** Mantém apenas a branca e a quantidade de alvos pedida. */
    fun setBallCount(count: Int) {
        ballCount = count.coerceIn(0, MAX_BALLS)
    }

    fun ballX(index: Int): Float = startX[index]
    fun ballY(index: Int): Float = startY[index]

    // ------------------------------------------------------------------
    // Resultados da última simulação
    // ------------------------------------------------------------------

    private val pathXs = FloatArray(MAX_BALLS * MAX_POINTS)
    private val pathYs = FloatArray(MAX_BALLS * MAX_POINTS)
    private val pathLen = IntArray(MAX_BALLS)
    private val moved = BooleanArray(MAX_BALLS)

    /** true se a branca encostou em alguma bola. */
    var ghostValid = false
        private set

    /** Posição da branca ("bola fantasma") no instante do primeiro impacto. */
    var ghostX = 0f
        private set
    var ghostY = 0f
        private set

    /** Ponto de contato entre a branca e a bola atingida. */
    var contactX = 0f
        private set
    var contactY = 0f
        private set

    /** Índice da primeira bola atingida pela branca (-1 se nenhuma). */
    var hitBallIndex = -1
        private set

    /** Índice, no caminho da branca, do ponto de impacto (segmentos seguintes = tangente). */
    var cueImpactIndex = -1
        private set

    /** Ângulo de corte: 0° = bola cheia, perto de 90° = bola fina. */
    var cutAngleDeg = 0f
        private set

    /** Ângulo entre as trajetórias da branca e da alvo após o impacto (90° se elástica). */
    var separationAngleDeg = 0f
        private set

    fun pathCount(ball: Int): Int = if (ball in 0 until MAX_BALLS) pathLen[ball] else 0

    /** Sem checagem de limites (uso em onDraw): 0 <= index < pathCount(ball). */
    fun pathX(ball: Int, index: Int): Float = pathXs[ball * MAX_POINTS + index]

    fun pathY(ball: Int, index: Int): Float = pathYs[ball * MAX_POINTS + index]

    /** true se a bola se moveu na simulação (branca ou atingida em cadeia). */
    fun wasMoved(ball: Int): Boolean = ball in 0 until MAX_BALLS && moved[ball]

    // ------------------------------------------------------------------
    // Estado interno da simulação (pré-alocado)
    // ------------------------------------------------------------------

    private val cx = FloatArray(MAX_BALLS)
    private val cy = FloatArray(MAX_BALLS)
    private val ux = FloatArray(MAX_BALLS)
    private val uy = FloatArray(MAX_BALLS)
    private val vx = FloatArray(MAX_BALLS)
    private val vy = FloatArray(MAX_BALLS)
    private val spd = FloatArray(MAX_BALLS)
    private val rem = FloatArray(MAX_BALLS)
    private val mv = BooleanArray(MAX_BALLS)
    private val bounces = IntArray(MAX_BALLS)

    private var boundMinX = 0f
    private var boundMaxX = 0f
    private var boundMinY = 0f
    private var boundMaxY = 0f
    private var simRadius = 0f
    private var pathBudget = 1f

    // ------------------------------------------------------------------
    // API principal
    // ------------------------------------------------------------------

    /**
     * Simula a tacada da bola branca (índice 0) em direção ao ponto de mira.
     * Preenche os caminhos de todas as bolas que se moveram.
     */
    fun simulate(aimX: Float, aimY: Float) {
        resetResults()
        val n = ballCount
        if (n < 1) return

        val width = tableRight - tableLeft
        val height = tableBottom - tableTop
        if (width < 4f || height < 4f) return

        simRadius = ballRadius
        boundMinX = tableLeft + simRadius
        boundMaxX = tableRight - simRadius
        boundMinY = tableTop + simRadius
        boundMaxY = tableBottom - simRadius
        if (boundMinX > boundMaxX) {
            val mid = (tableLeft + tableRight) * 0.5f
            boundMinX = mid
            boundMaxX = mid
        }
        if (boundMinY > boundMaxY) {
            val mid = (tableTop + tableBottom) * 0.5f
            boundMinY = mid
            boundMaxY = mid
        }
        pathBudget = max(width, height) * pathLengthTables

        for (i in 0 until n) {
            cx[i] = startX[i].coerceIn(boundMinX, boundMaxX)
            cy[i] = startY[i].coerceIn(boundMinY, boundMaxY)
            ux[i] = 0f
            uy[i] = 0f
            vx[i] = 0f
            vy[i] = 0f
            spd[i] = 0f
            rem[i] = 0f
            mv[i] = false
            bounces[i] = 0
        }

        val dx = aimX - cx[CUE_BALL]
        val dy = aimY - cy[CUE_BALL]
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-3f) return

        ux[CUE_BALL] = dx / len
        uy[CUE_BALL] = dy / len
        rem[CUE_BALL] = pathBudget
        mv[CUE_BALL] = true
        moved[CUE_BALL] = true
        addPoint(CUE_BALL)

        runEvents(n)
    }

    // ------------------------------------------------------------------
    // Núcleo da simulação
    // ------------------------------------------------------------------

    private fun resetResults() {
        for (i in 0 until MAX_BALLS) {
            pathLen[i] = 0
            moved[i] = false
        }
        ghostValid = false
        ghostX = 0f
        ghostY = 0f
        contactX = 0f
        contactY = 0f
        hitBallIndex = -1
        cueImpactIndex = -1
        cutAngleDeg = 0f
        separationAngleDeg = 0f
    }

    private fun runEvents(n: Int) {
        var events = 0
        while (events < MAX_EVENTS) {
            events++

            var anyMoving = false
            for (i in 0 until n) {
                refreshVelocity(i)
                if (mv[i]) anyMoving = true
            }
            if (!anyMoving) return

            var tBest = Float.MAX_VALUE
            var kind = EVENT_NONE
            var bi = -1
            var bj = -1

            for (i in 0 until n) {
                if (!mv[i]) continue

                val s = spd[i]
                if (s < EPS || rem[i] <= MIN_REMAINING) {
                    tBest = 0f
                    kind = EVENT_STOP
                    bi = i
                    bj = -1
                    break
                }

                val tStop = rem[i] / s
                if (tStop < tBest) {
                    tBest = tStop
                    kind = EVENT_STOP
                    bi = i
                    bj = -1
                }

                val vxi = vx[i]
                if (vxi > EPS) {
                    val t = max((boundMaxX - cx[i]) / vxi, 0f)
                    if (t < tBest) {
                        tBest = t
                        kind = EVENT_WALL_X
                        bi = i
                        bj = -1
                    }
                } else if (vxi < -EPS) {
                    val t = max((boundMinX - cx[i]) / vxi, 0f)
                    if (t < tBest) {
                        tBest = t
                        kind = EVENT_WALL_X
                        bi = i
                        bj = -1
                    }
                }

                val vyi = vy[i]
                if (vyi > EPS) {
                    val t = max((boundMaxY - cy[i]) / vyi, 0f)
                    if (t < tBest) {
                        tBest = t
                        kind = EVENT_WALL_Y
                        bi = i
                        bj = -1
                    }
                } else if (vyi < -EPS) {
                    val t = max((boundMinY - cy[i]) / vyi, 0f)
                    if (t < tBest) {
                        tBest = t
                        kind = EVENT_WALL_Y
                        bi = i
                        bj = -1
                    }
                }

                for (j in 0 until n) {
                    if (j == i) continue
                    if (mv[j] && j < i) continue // par já avaliado quando i = j
                    val t = ballHitTime(i, j)
                    if (t >= 0f && t < tBest) {
                        tBest = t
                        kind = EVENT_BALL
                        bi = i
                        bj = j
                    }
                }
            }

            if (kind == EVENT_NONE) return

            if (tBest > 0f) advance(n, tBest)

            when (kind) {
                EVENT_STOP -> {
                    mv[bi] = false
                    rem[bi] = 0f
                    addPoint(bi)
                }
                EVENT_WALL_X -> hitWall(bi, true)
                EVENT_WALL_Y -> hitWall(bi, false)
                EVENT_BALL -> collide(bi, bj)
            }
        }
    }

    private fun speedFromRemaining(remaining: Float): Float =
        sqrt(max(remaining, 0f) / pathBudget)

    private fun refreshVelocity(i: Int) {
        if (mv[i]) {
            val s = speedFromRemaining(rem[i])
            spd[i] = s
            vx[i] = ux[i] * s
            vy[i] = uy[i] * s
        } else {
            spd[i] = 0f
            vx[i] = 0f
            vy[i] = 0f
        }
    }

    private fun advance(n: Int, t: Float) {
        for (k in 0 until n) {
            if (mv[k]) {
                cx[k] += vx[k] * t
                cy[k] += vy[k] * t
                rem[k] = max(rem[k] - spd[k] * t, 0f)
            }
        }
    }

    /** Tempo até a bola i (em movimento) tocar a bola j; -1 se não houver contato. */
    private fun ballHitTime(i: Int, j: Int): Float {
        val dx = (cx[j] - cx[i]).toDouble()
        val dy = (cy[j] - cy[i]).toDouble()
        val wx = (vx[j] - vx[i]).toDouble()
        val wy = (vy[j] - vy[i]).toDouble()

        val a = wx * wx + wy * wy
        if (a < 1e-12) return -1f

        val b = dx * wx + dy * wy
        if (b >= 0.0) return -1f // afastando-se

        val d = (simRadius * 2f).toDouble()
        val c = dx * dx + dy * dy - d * d
        if (c <= 0.0) return 0f // já em contato e se aproximando

        val disc = b * b - a * c
        if (disc < 0.0) return -1f

        val t = (-b - sqrt(disc)) / a
        return if (t < 0.0) 0f else t.toFloat()
    }

    private fun hitWall(b: Int, xAxis: Boolean) {
        val nx: Float
        val ny: Float
        if (xAxis) {
            val rightWall = vx[b] > 0f
            cx[b] = if (rightWall) boundMaxX else boundMinX
            nx = if (rightWall) -1f else 1f
            ny = 0f
        } else {
            val bottomWall = vy[b] > 0f
            cy[b] = if (bottomWall) boundMaxY else boundMinY
            nx = 0f
            ny = if (bottomWall) -1f else 1f
        }

        if (bounces[b] >= maxBounces) {
            mv[b] = false
            rem[b] = 0f
            addPoint(b)
            return
        }
        bounces[b]++

        // Reflexão especular: r = d - 2(d·n)n
        val dn = ux[b] * nx + uy[b] * ny
        var rx = ux[b] - 2f * dn * nx
        var ry = uy[b] - 2f * dn * ny

        // Ângulo configurável: gira a saída em direção à tangente da tabela
        if (abs(cushionAngleOffsetDeg) > 0.001f) {
            val rn = rx * nx + ry * ny
            val tx = rx - rn * nx
            val ty = ry - rn * ny
            val tl = sqrt(tx * tx + ty * ty)
            if (tl > 1e-4f) {
                val phi = atan2(tl, rn)
                val newPhi = (phi + cushionAngleOffsetDeg * DEG_TO_RAD)
                    .coerceIn(0f, MAX_CUSHION_ANGLE_RAD)
                val c = cos(newPhi)
                val s = sin(newPhi)
                rx = nx * c + (tx / tl) * s
                ry = ny * c + (ty / tl) * s
            }
        }

        val l = sqrt(rx * rx + ry * ry)
        if (l > EPS) {
            ux[b] = rx / l
            uy[b] = ry / l
        }

        // energia ∝ v², distância restante ∝ v²
        rem[b] *= cushionRestitution * cushionRestitution
        if (rem[b] <= MIN_REMAINING) {
            rem[b] = 0f
            mv[b] = false
        }
        addPoint(b)
    }

    /** Colisão da bola i (em movimento) com a bola j (parada ou em movimento). */
    private fun collide(i: Int, j: Int) {
        refreshVelocity(i)
        refreshVelocity(j)

        var nx = cx[j] - cx[i]
        var ny = cy[j] - cy[i]
        var dist = sqrt(nx * nx + ny * ny)
        if (dist < EPS) {
            nx = ux[i]
            ny = uy[i]
            dist = 1f
        }
        nx /= dist
        ny /= dist

        val vrel = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
        if (vrel <= 0f) return

        val impulse = (1f + ballRestitution) * 0.5f * vrel
        val niX = vx[i] - impulse * nx
        val niY = vy[i] - impulse * ny
        val njX = vx[j] + impulse * nx
        val njY = vy[j] + impulse * ny

        val firstCueImpact = i == CUE_BALL && !ghostValid
        if (firstCueImpact) {
            ghostValid = true
            ghostX = cx[i]
            ghostY = cy[i]
            contactX = cx[i] + nx * simRadius
            contactY = cy[i] + ny * simRadius
            hitBallIndex = j
            cutAngleDeg = acos((ux[i] * nx + uy[i] * ny).coerceIn(-1f, 1f)) * RAD_TO_DEG

            val si = sqrt(niX * niX + niY * niY)
            val sj = sqrt(njX * njX + njY * njY)
            separationAngleDeg = if (si > EPS && sj > EPS) {
                acos(((niX * njX + niY * njY) / (si * sj)).coerceIn(-1f, 1f)) * RAD_TO_DEG
            } else {
                0f
            }
        }

        setVelocity(i, niX, niY)
        setVelocity(j, njX, njY)

        if (mv[j]) {
            moved[j] = true
            addPoint(j)
        }
        addPoint(i)

        if (firstCueImpact) cueImpactIndex = pathLen[i] - 1
    }

    private fun setVelocity(b: Int, nvx: Float, nvy: Float) {
        val s = sqrt(nvx * nvx + nvy * nvy)
        val newRem = pathBudget * s * s
        if (s < EPS || newRem <= MIN_REMAINING) {
            mv[b] = false
            rem[b] = 0f
        } else {
            ux[b] = nvx / s
            uy[b] = nvy / s
            rem[b] = newRem
            mv[b] = true
        }
    }

    private fun addPoint(b: Int) {
        val count = pathLen[b]
        val base = b * MAX_POINTS
        val x = cx[b]
        val y = cy[b]
        if (count > 0) {
            val lx = pathXs[base + count - 1]
            val ly = pathYs[base + count - 1]
            if (abs(x - lx) < 0.01f && abs(y - ly) < 0.01f) return
        }
        if (count >= MAX_POINTS) {
            mv[b] = false
            return
        }
        pathXs[base + count] = x
        pathYs[base + count] = y
        pathLen[b] = count + 1
        if (count + 1 >= MAX_POINTS) mv[b] = false // caminho cheio: encerra a bola aqui
    }
}
