package com.padelgrabber.training

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ====================================================================== Articulaciones

object J {
    const val NOSE = 0
    const val L_SH = 1; const val R_SH = 2
    const val L_EL = 3; const val R_EL = 4
    const val L_WR = 5; const val R_WR = 6
    const val L_HIP = 7; const val R_HIP = 8
    const val L_KN = 9; const val R_KN = 10
    const val L_AN = 11; const val R_AN = 12
    const val COUNT = 13

    val MLKIT = intArrayOf(
        PoseLandmark.NOSE,
        PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
    )

    /** Huesos para dibujar el esqueleto. */
    val BONES = arrayOf(
        intArrayOf(L_SH, R_SH), intArrayOf(L_SH, L_EL), intArrayOf(L_EL, L_WR),
        intArrayOf(R_SH, R_EL), intArrayOf(R_EL, R_WR), intArrayOf(L_SH, L_HIP),
        intArrayOf(R_SH, R_HIP), intArrayOf(L_HIP, R_HIP), intArrayOf(L_HIP, L_KN),
        intArrayOf(L_KN, L_AN), intArrayOf(R_HIP, R_KN), intArrayOf(R_KN, R_AN)
    )
}

class Pt(val x: Float, val y: Float)

/** Postura simplificada en coordenadas de la imagen (ya derecha). */
class PoseFrame(val t: Long, val pts: Array<Pt?>, val width: Int, val height: Int) {
    operator fun get(i: Int): Pt? = pts[i]

    companion object {
        fun from(pose: Pose, t: Long, width: Int, height: Int, minLikelihood: Float = 0.5f): PoseFrame {
            val arr = arrayOfNulls<Pt>(J.COUNT)
            for (i in 0 until J.COUNT) {
                val lm = pose.getPoseLandmark(J.MLKIT[i]) ?: continue
                if (lm.inFrameLikelihood >= minLikelihood) arr[i] = Pt(lm.position.x, lm.position.y)
            }
            return PoseFrame(t, arr, width, height)
        }
    }

    val hasBody: Boolean
        get() = pts[J.L_SH] != null && pts[J.R_SH] != null && (pts[J.L_HIP] != null || pts[J.R_HIP] != null)
}

object Geo {
    /** Ángulo en b (grados) formado por a-b-c. */
    fun angle(a: Pt?, b: Pt?, c: Pt?): Float? {
        if (a == null || b == null || c == null) return null
        val v1x = a.x - b.x; val v1y = a.y - b.y
        val v2x = c.x - b.x; val v2y = c.y - b.y
        val n = sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y)
        if (n < 1e-3f) return null
        val cos = ((v1x * v2x + v1y * v2y) / n).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    fun mid(a: Pt?, b: Pt?): Pt? = when {
        a != null && b != null -> Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        else -> a ?: b
    }

    fun dist(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

    /** Largo del tronco (hombros a caderas): sirve de "regla" para normalizar. */
    fun torso(f: PoseFrame): Float? {
        val sh = mid(f[J.L_SH], f[J.R_SH]) ?: return null
        val hip = mid(f[J.L_HIP], f[J.R_HIP]) ?: return null
        val d = dist(sh, hip)
        return if (d > 5f) d else null
    }

    /** Inclinación de la línea de hombros (grados). */
    fun shoulderTilt(f: PoseFrame): Float? {
        val l = f[J.L_SH] ?: return null
        val r = f[J.R_SH] ?: return null
        return Math.toDegrees(atan2((r.y - l.y).toDouble(), (r.x - l.x).toDouble())).toFloat()
    }
}

// ====================================================================== Golpes

/** Rango orientativo de una medida. null = sin límite por ese lado. */
class Range(val min: Float?, val max: Float?) {
    fun contains(v: Float) = (min == null || v >= min) && (max == null || v <= max)
    fun distance(v: Float): Float = when {
        min != null && v < min -> min - v
        max != null && v > max -> v - max
        else -> 0f
    }
    fun text(unit: String): String = when {
        min != null && max != null -> "${fmt(min)}–${fmt(max)}$unit"
        min != null -> "≥ ${fmt(min)}$unit"
        max != null -> "≤ ${fmt(max)}$unit"
        else -> "—"
    }
    private fun fmt(v: Float) = if (abs(v - v.toInt()) < 0.01f) v.toInt().toString() else String.format("%.1f", v)
}

enum class Metric(val label: String, val unit: String, val tolerance: Float) {
    ELBOW("Ángulo del codo en el impacto", "°", 18f),
    SHOULDER("Elevación del brazo en el impacto", "°", 18f),
    KNEE("Flexión de rodillas (ángulo mínimo)", "°", 15f),
    ROTATION("Giro del tronco", "°", 15f),
    HEIGHT("Altura del impacto (tronco = 1)", "", 0.3f),
    FOLLOW("Recorrido tras el impacto (tronco = 1)", "", 0.4f),
    SPEED("Velocidad del brazo (troncos/s)", "", 2f)
}

class GuideSection(val title: String, val items: List<String>)

enum class Stroke(
    val title: String,
    val summary: String,
    val ranges: Map<Metric, Range>,
    val sections: List<GuideSection>,
    val camera: String
) {
    DERECHA(
        "Derecha",
        "Golpe de fondo por el lado de la mano dominante, normalmente tras el bote o la salida de la pared.",
        mapOf(
            Metric.ELBOW to Range(110f, 165f),
            Metric.ROTATION to Range(25f, 75f),
            Metric.KNEE to Range(null, 160f),
            Metric.HEIGHT to Range(-1.2f, 0.1f),
            Metric.FOLLOW to Range(0.7f, null)
        ),
        listOf(
            GuideSection("Fases", listOf(
                "Preparación: split-step al bote del rival, giro de hombros y pala atrás a la altura de la bola.",
                "Aceleración: transfiere el peso de la pierna de atrás a la de delante; la cadera arrastra al tronco y el tronco al brazo.",
                "Impacto: delante del cuerpo, entre la cadera y el pecho, con el codo algo flexionado y la muñeca firme.",
                "Terminación: la pala acompaña hacia la red y termina a la altura del hombro contrario."
            )),
            GuideSection("Claves biomecánicas", listOf(
                "La potencia sale de las piernas y del giro del tronco (cadena cinética), no del brazo.",
                "Rodillas flexionadas para bajar a la bola: el ángulo de rodilla suele quedar entre 120° y 160°.",
                "Codo semiflexionado en el impacto (≈110–165°): estirarlo del todo resta control, cerrarlo demasiado resta alcance.",
                "Hombros girados al menos 25–30° en la preparación para tener recorrido."
            )),
            GuideSection("Errores habituales", listOf(
                "Golpear con las piernas rectas y sin girar: todo el esfuerzo lo hace el brazo y el codo sufre.",
                "Impactar tarde, a la altura del cuerpo o detrás: la bola se va alta y fuera.",
                "Cortar la terminación: el golpe pierde profundidad."
            )),
            GuideSection("Ejercicio", listOf(
                "Sombra con pala: 3 series de 10 derechas lentas parando en el impacto y comprobando codo y rodillas.",
                "Con compañero: bolas suaves al lateral, 10 seguidas apuntando a la línea de saque."
            ))
        ),
        "Cámara de lado, a la altura de la cintura, a 3–5 m."
    ),
    REVES(
        "Revés",
        "Golpe de fondo por el lado contrario a la mano dominante.",
        mapOf(
            Metric.ELBOW to Range(120f, 175f),
            Metric.ROTATION to Range(25f, 80f),
            Metric.KNEE to Range(null, 160f),
            Metric.HEIGHT to Range(-1.2f, 0.1f),
            Metric.FOLLOW to Range(0.6f, null)
        ),
        listOf(
            GuideSection("Fases", listOf(
                "Preparación: giro de hombros pronunciado, la espalda casi mira a la red; la mano libre ayuda a llevar la pala atrás.",
                "Aceleración: paso hacia la bola con la pierna del lado de la mano dominante.",
                "Impacto: delante de la pierna adelantada, con el brazo más estirado que en la derecha.",
                "Terminación: el brazo se extiende hacia el objetivo y el pecho se abre al final."
            )),
            GuideSection("Claves biomecánicas", listOf(
                "El giro del tronco es todavía más importante que en la derecha: suele superar los 30°.",
                "Codo casi extendido en el impacto (≈120–175°) para golpear lejos del cuerpo.",
                "Mantén las rodillas flexionadas y el peso hacia delante."
            )),
            GuideSection("Errores habituales", listOf(
                "Golpear con el codo por delante de la pala («codo de tenista»).",
                "No girar los hombros y empujar solo con el antebrazo.",
                "Impactar pegado al cuerpo."
            )),
            GuideSection("Ejercicio", listOf(
                "Sombra: 3×10 reveses marcando giro de hombros y paso.",
                "Bolas lanzadas a la mano: busca el impacto delante de la pierna adelantada."
            ))
        ),
        "Cámara de lado o detrás del jugador, a la altura de la cintura."
    ),
    VOLEA(
        "Volea",
        "Golpe sin bote cerca de la red. Corto, firme y con poca preparación.",
        mapOf(
            Metric.ELBOW to Range(90f, 150f),
            Metric.ROTATION to Range(null, 35f),
            Metric.KNEE to Range(null, 165f),
            Metric.HEIGHT to Range(-0.7f, 0.5f),
            Metric.FOLLOW to Range(null, 1.2f)
        ),
        listOf(
            GuideSection("Fases", listOf(
                "Preparación: pala delante del cuerpo, giro corto de hombros, sin llevar la pala atrás.",
                "Impacto: delante y al lado del cuerpo, con la muñeca firme y la cara de la pala algo abierta.",
                "Terminación: corta, hacia el objetivo; vuelve rápido a la posición de espera."
            )),
            GuideSection("Claves biomecánicas", listOf(
                "Codo flexionado (≈90–150°) y por delante del cuerpo: el golpe sale del hombro, no de la muñeca.",
                "Poco giro de tronco (menos de 30–35°): la bola viene rápida.",
                "Rodillas flexionadas para las bolas bajas: baja el cuerpo, no la pala."
            )),
            GuideSection("Errores habituales", listOf(
                "Hacer swing como en un golpe de fondo.",
                "Romper la muñeca en el impacto.",
                "Volear con las piernas rectas a bolas bajas."
            )),
            GuideSection("Ejercicio", listOf(
                "Volea-volea con compañero en la red, 2 minutos sin fallo.",
                "Bolas bajas a los pies: 10 voleas bajando con las piernas."
            ))
        ),
        "Cámara de frente o de lado, a la altura del pecho."
    ),
    BANDEJA(
        "Bandeja",
        "Golpe por encima de la cabeza para mantener la red: controlado, con efecto cortado y sin buscar el punto.",
        mapOf(
            Metric.ELBOW to Range(115f, 165f),
            Metric.SHOULDER to Range(95f, 150f),
            Metric.ROTATION to Range(20f, 65f),
            Metric.HEIGHT to Range(0.4f, 1.4f),
            Metric.KNEE to Range(null, 170f)
        ),
        listOf(
            GuideSection("Fases", listOf(
                "Preparación: perfil a la red, pala alta detrás de la cabeza y brazo libre señalando la bola.",
                "Aceleración: el tronco gira y el brazo describe un movimiento de lado, «en bandeja».",
                "Impacto: por delante y al lado de la cabeza, sin estirar del todo el brazo; cara de la pala ligeramente abierta.",
                "Terminación: la pala sigue hacia delante y abajo, hacia el lado contrario."
            )),
            GuideSection("Claves biomecánicas", listOf(
                "Impacto más bajo que en el remate: el codo no se estira del todo (≈115–165°).",
                "Brazo elevado entre 95° y 150° respecto al tronco.",
                "Giro de tronco moderado: control antes que potencia."
            )),
            GuideSection("Errores habituales", listOf(
                "Golpear con la bola detrás de la cabeza: sobrecarga el hombro.",
                "Hacer un remate plano en vez de una bandeja.",
                "No ponerse de perfil."
            )),
            GuideSection("Ejercicio", listOf(
                "Globos suaves desde el fondo: 10 bandejas al cristal lateral contrario.",
                "Sombra parando en el impacto para revisar la altura del codo."
            ))
        ),
        "Cámara de lado, algo alejada para que salgan la pala y la cabeza."
    ),
    VIBORA(
        "Víbora",
        "Bandeja más agresiva, con más efecto lateral y más velocidad.",
        mapOf(
            Metric.ELBOW to Range(125f, 170f),
            Metric.SHOULDER to Range(95f, 150f),
            Metric.ROTATION to Range(30f, 85f),
            Metric.HEIGHT to Range(0.3f, 1.3f),
            Metric.KNEE to Range(null, 170f)
        ),
        listOf(
            GuideSection("Fases", listOf(
                "Preparación: como la bandeja, pero con la pala más atrás y más giro de hombros.",
                "Aceleración: latigazo del tronco y del antebrazo de fuera hacia dentro.",
                "Impacto: al lado del cuerpo, algo más bajo que la bandeja.",
                "Terminación: la pala cruza por delante del cuerpo hacia la cadera contraria."
            )),
            GuideSection("Claves biomecánicas", listOf(
                "Más giro de tronco que en la bandeja (≥ 30°): de ahí sale la velocidad.",
                "Codo algo más estirado (≈125–170°).",
                "La muñeca acompaña, pero el motor sigue siendo el tronco."
            )),
            GuideSection("Errores habituales", listOf(
                "Buscar el efecto solo con la muñeca: riesgo de lesión.",
                "Golpear demasiado bajo y que la bola se quede en la red."
            )),
            GuideSection("Ejercicio", listOf(
                "Alterna bandeja y víbora con el mismo globo para notar la diferencia de giro.",
                "10 víboras apuntando a la pared lateral, cerca de la esquina."
            ))
        ),
        "Cámara de lado, algo alejada."
    ),
    REMATE(
        "Remate",
        "Golpe por encima de la cabeza para ganar el punto: por 3, por 4 o plano.",
        mapOf(
            Metric.ELBOW to Range(150f, 180f),
            Metric.SHOULDER to Range(135f, 180f),
            Metric.ROTATION to Range(30f, 95f),
            Metric.HEIGHT to Range(0.9f, 2.0f),
            Metric.KNEE to Range(null, 165f)
        ),
        listOf(
            GuideSection("Fases", listOf(
                "Preparación: perfil, pala detrás de la cabeza («rascarse la espalda»), brazo libre arriba apuntando a la bola.",
                "Impulso: flexión de rodillas y empuje del suelo hacia arriba.",
                "Impacto: en el punto más alto que alcances, delante de la cabeza y con el brazo casi estirado.",
                "Terminación: la pala baja por el lado contrario del cuerpo; aterriza equilibrado."
            )),
            GuideSection("Claves biomecánicas", listOf(
                "Brazo casi totalmente extendido (codo ≈150–180°) y muy elevado (≥ 135°).",
                "Impacto alto: la muñeca muy por encima de la cabeza.",
                "Mucho giro de tronco: los hombros pasan de perfil a mirar la red.",
                "Las piernas inician el golpe: flexiona antes de saltar."
            )),
            GuideSection("Errores habituales", listOf(
                "Dejar caer la bola y golpear bajo.",
                "No ponerse de perfil: se pierde casi toda la potencia.",
                "Golpear con el codo por debajo del hombro: riesgo para el hombro."
            )),
            GuideSection("Ejercicio", listOf(
                "Desplazamiento lateral hacia atrás + remate a globos medios, 3×8.",
                "Lanzamientos de pelota con la mano imitando el gesto, para trabajar el giro."
            ))
        ),
        "Cámara de lado, alejada para que salga el brazo estirado."
    ),
    SAQUE(
        "Saque",
        "Saque por debajo: la bola debe botar y golpearse a la altura de la cintura o por debajo.",
        mapOf(
            Metric.ELBOW to Range(115f, 170f),
            Metric.ROTATION to Range(15f, 60f),
            Metric.HEIGHT to Range(-1.5f, -0.6f),
            Metric.KNEE to Range(null, 160f)
        ),
        listOf(
            GuideSection("Fases", listOf(
                "Preparación: de perfil, bote de la bola delante y al lado del pie adelantado.",
                "Aceleración: pala atrás y abajo; el peso pasa a la pierna de delante.",
                "Impacto: a la altura de la cintura o por debajo (reglamento), delante del cuerpo.",
                "Terminación: la pala sube hacia el objetivo y se avanza hacia la red."
            )),
            GuideSection("Claves biomecánicas", listOf(
                "Impacto por debajo de la cintura: en la app, más de 0,6 troncos por debajo de los hombros.",
                "Flexiona las rodillas para golpear bajo sin encorvar la espalda.",
                "Giro de tronco moderado para dar dirección."
            )),
            GuideSection("Errores habituales", listOf(
                "Golpear por encima de la cintura (falta).",
                "Sacar sin flexionar las piernas, doblando solo la espalda.",
                "No acompañar y quedarse en el fondo."
            )),
            GuideSection("Ejercicio", listOf(
                "10 saques a la «T» y 10 al cristal lateral.",
                "Saque + subida a la red con split-step."
            ))
        ),
        "Cámara de lado, a la altura de la cintura."
    );

    companion object {
        fun byName(n: String?): Stroke? = values().firstOrNull { it.name == n }
    }
}

/** Temas generales de la guía. */
object GeneralGuide {
    val topics: List<Pair<String, List<GuideSection>>> = listOf(
        "Cadena cinética" to listOf(
            GuideSection("Qué es", listOf(
                "La fuerza de un golpe nace en el suelo y pasa por piernas → cadera → tronco → hombro → codo → muñeca → pala.",
                "Cada segmento acelera cuando el anterior está frenando: así se suma la velocidad.",
                "Si un eslabón falla (piernas rectas, tronco sin girar), el brazo tiene que compensarlo y aparecen las lesiones."
            )),
            GuideSection("Cómo se ve en la app", listOf(
                "Flexión de rodillas: las piernas están cargadas.",
                "Giro del tronco: los hombros pasan de perfil a frente.",
                "Velocidad del brazo: es la consecuencia, no la causa."
            ))
        ),
        "Posición de espera y split-step" to listOf(
            GuideSection("Claves", listOf(
                "Pies a la anchura de los hombros, rodillas flexionadas, peso en la parte delantera del pie.",
                "Pala delante del cuerpo, a la altura del pecho.",
                "Pequeño salto (split-step) justo cuando el rival golpea: te deja listo para salir a cualquier lado."
            ))
        ),
        "Empuñadura" to listOf(
            GuideSection("Continental", listOf(
                "Es la más usada en pádel: sirve para derecha, revés, volea y golpes por encima de la cabeza sin cambiar de agarre.",
                "Coge la pala como si fuera un martillo, con la cara perpendicular al suelo.",
                "Agarre firme en el impacto pero relajado entre golpes: un agarre tenso todo el rato carga el antebrazo."
            ))
        ),
        "Prevención de lesiones" to listOf(
            GuideSection("Codo (epicondilitis)", listOf(
                "Aparece sobre todo al golpear tarde, con la muñeca o con la pala muy pesada.",
                "Golpea delante del cuerpo y usa las piernas y el tronco."
            )),
            GuideSection("Hombro", listOf(
                "En bandejas y remates, golpea con la bola delante de la cabeza, no detrás.",
                "Fortalece el manguito rotador con gomas elásticas (rotaciones externas e internas)."
            )),
            GuideSection("Calentamiento", listOf(
                "5–10 minutos: movilidad de tobillos, caderas, tronco y hombros, desplazamientos y golpes suaves.",
                "Si tienes dolor que no se va, consulta con un fisioterapeuta o un médico."
            ))
        )
    )
}

// ====================================================================== Análisis de golpes

class StrokeResult(
    val t: Long,
    val stroke: Stroke,
    val auto: Boolean,
    val metrics: Map<Metric, Float>,
    val score: Int,
    val tips: List<String>,
    val impact: PoseFrame
) {
    fun toJson(): JSONObject {
        val m = JSONObject()
        metrics.forEach { (k, v) -> m.put(k.name, v.toDouble()) }
        return JSONObject().put("t", t).put("stroke", stroke.name).put("auto", auto)
            .put("score", score).put("metrics", m)
    }
}

/**
 * Detecta golpes (pico de velocidad de la muñeca de la mano dominante) y mide cada uno.
 * Funciona en directo y con vídeos.
 */
class SwingAnalyzer(
    var rightHanded: Boolean = true,
    var fixedStroke: Stroke? = null,
    private val onStroke: (StrokeResult) -> Unit
) {
    private val frames = ArrayList<PoseFrame>()
    private var candidate: Int = -1
    private var candidatePeak = 0f
    private var lastStrokeT = -10_000L
    var lastSpeed = 0f
        private set

    private val wristIdx get() = if (rightHanded) J.R_WR else J.L_WR
    private val elbowIdx get() = if (rightHanded) J.R_EL else J.L_EL
    private val shoulderIdx get() = if (rightHanded) J.R_SH else J.L_SH
    private val hipIdx get() = if (rightHanded) J.R_HIP else J.L_HIP

    fun reset() {
        frames.clear()
        candidate = -1
        lastStrokeT = -10_000L
    }

    fun add(f: PoseFrame) {
        frames.add(f)
        while (frames.size > 2 && f.t - frames[0].t > 3000) {
            frames.removeAt(0)
            if (candidate >= 0) candidate--
        }
        val n = frames.size
        if (n < 2) return
        val a = frames[n - 2]
        val b = frames[n - 1]
        val speed = wristSpeed(a, b)
        lastSpeed = speed

        // Pico candidato
        if (speed > 3.2f && b.t - lastStrokeT > 900) {
            if (candidate < 0 || speed > candidatePeak) {
                candidate = n - 1
                candidatePeak = speed
            }
        }
        // Damos el golpe por terminado 400 ms después del pico
        if (candidate >= 0 && b.t - frames[candidate].t >= 400) {
            val idx = candidate
            candidate = -1
            lastStrokeT = frames[idx].t
            measure(idx)?.let(onStroke)
            candidatePeak = 0f
        }
    }

    private fun wristSpeed(a: PoseFrame, b: PoseFrame): Float {
        val wa = a[wristIdx] ?: return 0f
        val wb = b[wristIdx] ?: return 0f
        val torso = Geo.torso(b) ?: Geo.torso(a) ?: return 0f
        val dt = (b.t - a.t).coerceAtLeast(1) / 1000f
        return Geo.dist(wa, wb) / torso / dt
    }

    private fun measure(impactIdx: Int): StrokeResult? {
        val imp = frames[impactIdx]
        val torso = Geo.torso(imp) ?: return null
        val metrics = LinkedHashMap<Metric, Float>()

        Geo.angle(imp[shoulderIdx], imp[elbowIdx], imp[wristIdx])?.let { metrics[Metric.ELBOW] = it }
        Geo.angle(imp[hipIdx], imp[shoulderIdx], imp[elbowIdx])?.let { metrics[Metric.SHOULDER] = it }

        val before = frames.filter { it.t in (imp.t - 1200)..imp.t }
        val after = frames.filter { it.t in imp.t..(imp.t + 450) }

        // Rodillas: ángulo mínimo en la preparación
        var knee: Float? = null
        for (f in before) {
            val l = Geo.angle(f[J.L_HIP], f[J.L_KN], f[J.L_AN])
            val r = Geo.angle(f[J.R_HIP], f[J.R_KN], f[J.R_AN])
            val k = listOfNotNull(l, r).minOrNull() ?: continue
            knee = if (knee == null) k else min(knee, k)
        }
        knee?.let { metrics[Metric.KNEE] = it }

        // Giro de tronco a partir de cuánto "se estrecha" la línea de hombros
        val widths = before.plus(after).mapNotNull { f ->
            val l = f[J.L_SH]; val r = f[J.R_SH]; val t = Geo.torso(f)
            if (l != null && r != null && t != null) abs(l.x - r.x) / t else null
        }
        if (widths.size >= 3) {
            val wMax = widths.max()
            val wMin = widths.min()
            if (wMax > 0.05f) metrics[Metric.ROTATION] = Math.toDegrees(acos((wMin / wMax).coerceIn(0f, 1f).toDouble())).toFloat()
        }

        // Altura del impacto respecto a los hombros (positivo = por encima)
        val shMid = Geo.mid(imp[J.L_SH], imp[J.R_SH])
        val wr = imp[wristIdx]
        if (shMid != null && wr != null) metrics[Metric.HEIGHT] = (shMid.y - wr.y) / torso

        // Recorrido de la muñeca después del impacto
        val wEnd = after.lastOrNull()?.get(wristIdx)
        if (wr != null && wEnd != null) metrics[Metric.FOLLOW] = Geo.dist(wr, wEnd) / torso

        metrics[Metric.SPEED] = candidatePeak

        val auto = fixedStroke == null
        val stroke = fixedStroke ?: classify(metrics, imp)
        val (score, tips) = evaluate(stroke, metrics)
        return StrokeResult(imp.t, stroke, auto, metrics, score, tips, imp)
    }

    /** Clasificación sencilla cuando no se ha elegido golpe. */
    private fun classify(m: Map<Metric, Float>, f: PoseFrame): Stroke {
        val h = m[Metric.HEIGHT] ?: 0f
        val elbow = m[Metric.ELBOW] ?: 150f
        if (h > 0.85f && elbow > 150f) return Stroke.REMATE
        if (h > 0.35f) return if ((m[Metric.ROTATION] ?: 0f) > 40f) Stroke.VIBORA else Stroke.BANDEJA
        if (h < -0.75f && (m[Metric.FOLLOW] ?: 0f) > 0.5f && (m[Metric.SPEED] ?: 0f) < 6f) return Stroke.SAQUE
        if ((m[Metric.FOLLOW] ?: 2f) < 0.9f && (m[Metric.ROTATION] ?: 0f) < 30f) return Stroke.VOLEA
        // Derecha o revés: ¿la muñeca está del lado de la mano dominante?
        val wr = f[wristIdx]
        val lsh = f[J.L_SH]
        val rsh = f[J.R_SH]
        if (wr != null && lsh != null && rsh != null) {
            val dominant = if (rightHanded) rsh else lsh
            val other = if (rightHanded) lsh else rsh
            val sameSide = abs(wr.x - dominant.x) < abs(wr.x - other.x)
            return if (sameSide) Stroke.DERECHA else Stroke.REVES
        }
        return Stroke.DERECHA
    }

    companion object {
        fun evaluate(stroke: Stroke, metrics: Map<Metric, Float>): Pair<Int, List<String>> {
            val scores = ArrayList<Float>()
            val tips = ArrayList<String>()
            for ((metric, range) in stroke.ranges) {
                val v = metrics[metric] ?: continue
                val d = range.distance(v)
                scores.add(max(0f, 100f - 60f * d / metric.tolerance))
                if (d > 0f) tip(stroke, metric, v, range)?.let { tips.add(it) }
            }
            val score = if (scores.isEmpty()) 0 else scores.average().toInt()
            if (tips.isEmpty() && scores.isNotEmpty()) tips.add("¡Buen golpe! Todas las medidas dentro de lo recomendado.")
            return score to tips
        }

        private fun tip(stroke: Stroke, m: Metric, v: Float, r: Range): String? {
            val low = r.min != null && v < r.min
            return when (m) {
                Metric.ELBOW -> if (low) "Codo demasiado doblado en el impacto: golpea un poco más lejos del cuerpo."
                else if (stroke == Stroke.BANDEJA || stroke == Stroke.VIBORA) "Brazo demasiado estirado: en la ${stroke.title.lowercase()} el impacto es algo más bajo que en el remate."
                else "Brazo demasiado estirado: deja el codo algo flexionado para controlar mejor."
                Metric.SHOULDER -> if (low) "Brazo bajo: sube el codo por encima de la línea del hombro." else "Brazo muy vertical: deja la bola un poco más delante."
                Metric.KNEE -> "Piernas casi rectas: flexiona más las rodillas para cargar el golpe."
                Metric.ROTATION -> if (low) "Poco giro de tronco: ponte más de perfil en la preparación." else "Demasiado giro para este golpe: acórtalo y gana control."
                Metric.HEIGHT -> when {
                    stroke == Stroke.SAQUE && !low -> "Impacto demasiado alto: en el saque hay que golpear a la altura de la cintura o por debajo."
                    low -> "Impacto bajo: busca la bola antes, más arriba."
                    else -> "Impacto alto para este golpe: deja bajar un poco la bola o elige otro golpe."
                }
                Metric.FOLLOW -> if (low) "Terminación corta: acompaña la bola hacia el objetivo." else "Demasiado recorrido: la volea es un golpe corto."
                Metric.SPEED -> null
            }
        }
    }
}
