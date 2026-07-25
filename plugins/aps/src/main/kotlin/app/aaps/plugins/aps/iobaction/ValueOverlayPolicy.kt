package app.aaps.plugins.aps.iobaction

import app.aaps.core.keys.DoubleKey
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Grenzen und Draht-Format fuer die Double-wertigen Capabilities (SMBRATIO, WEIGHTS).
 *
 * DREI BEFUNDE AUS DER GRENZEN-PRUEFUNG (25.07.), die dieses Modul so aussehen lassen:
 *
 * 1. **Preferences klemmen NICHT.** `PreferencesImpl.put` schreibt `sp.putDouble(key, value)`
 *    ohne `coerceIn`, `get` liest ungeklemmt zurueck. Die min/max der Key-Definitionen sind
 *    Dialog-Hinweise, keine erzwungenen Invarianten. Diese Policy ist damit nicht EINE
 *    Absicherung, sondern die EINZIGE — und sie lehnt ab (reject-not-clamp), statt still
 *    zurechtzubiegen.
 *
 * 2. **Eine bestehende Automation darf mehr als ihr Key erlaubt.** `ActionSetPpWeight` bietet
 *    maxVal 0.2 an, waehrend [DoubleKey.ApsAutoIsfPpWeight] 0.15 deklariert. Da nichts klemmt,
 *    ist 0.2 real erreichbar. Diese Policy baut das NICHT nach: Grenzen kommen aus dem Key,
 *    der Kanal darf hier also weniger als die Automation.
 *
 * 3. **Float-Rundung.** Gewichte liegen als Float in den Prefs: aus 0.23 wird beim Lesen
 *    0.23000000417232513. Tausendstel sind deshalb ausschliesslich DRAHT- und POLICY-Format
 *    (keine Doubles in HMAC/Room). Die Basis wird als ROHER Double erfasst und zurueck-
 *    geschrieben, und der Fremdschreiber-Vergleich laeuft mit Toleranz statt auf Gleichheit —
 *    dieselbe Lehre, die `ActionSetPpWeight` schon als Kommentar traegt.
 */
object ValueOverlayPolicy {

    /** Draht-Skala: Tausendstel. Deckt die feinsten UI-Schritte (0.01 bei pp/acce, 0.1 bei dura) exakt ab. */
    const val SCALE = 1000L

    /**
     * Float-Rundungstoleranz fuer den Basis-/Fremdschreiber-Vergleich.
     *
     * Befund 10 des Reviews: 0.0005 war ein halber DRAHT-Schritt, nicht eine Float-Toleranz.
     * Der Rundtrip-Fehler, der die Toleranz ueberhaupt begruendet (0.23 -> 0.23000000417),
     * liegt bei ~1e-8 — die alte Schwelle war fuenf Groessenordnungen zu gross und haette eine
     * echte Fremdaenderung von exakt 0.0005 als "unveraendert" durchgehen lassen. 1e-6 deckt
     * den Rundtrip mit zwei Groessenordnungen Reserve und laesst keine Luecke.
     */
    const val EPS = 1e-6

    /** Kanonische Feldnamen — sie stehen im Draht-Payload, in Room und im Policy-Hash. */
    const val F_RATIO = "ratio"
    const val F_ACCE = "acce"
    const val F_BRAKE = "brake"
    const val F_PP = "pp"
    const val F_DURA = "dura"
    const val F_LOW = "lowRange"
    const val F_HIGH = "highRange"

    /** Feld -> Preference-Key. Die Grenzen werden IMMER hieraus abgeleitet, nie dupliziert. */
    val WEIGHT_KEYS: Map<String, DoubleKey> = mapOf(
        F_ACCE to DoubleKey.ApsAutoIsfBgAccelWeight,
        F_BRAKE to DoubleKey.ApsAutoIsfBgBrakeWeight,
        F_PP to DoubleKey.ApsAutoIsfPpWeight,
        F_DURA to DoubleKey.ApsAutoIsfDuraWeight,
        F_LOW to DoubleKey.ApsAutoIsfLowBgWeight,
        F_HIGH to DoubleKey.ApsAutoIsfHighBgWeight,
    )

    val RATIO_KEY: DoubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatio

    fun scaled(value: Double): Long = (value * SCALE).roundToLong()
    fun unscaled(value: Long): Double = value.toDouble() / SCALE

    private fun inKeyRange(key: DoubleKey, scaledValue: Long): Boolean {
        val v = unscaled(scaledValue)
        // Grenzen selbst in Tausendsteln vergleichen, damit die Kante exakt trifft.
        return scaledValue in scaled(key.min)..scaled(key.max) && v.isFinite()
    }

    fun isRatioAllowed(scaledRatio: Long): Boolean = inKeyRange(RATIO_KEY, scaledRatio)

    /** Teil-Nutzlast: mindestens ein Feld, nur bekannte Felder, jedes in seinem Key-Bereich. */
    fun areWeightsAllowed(scaledWeights: Map<String, Long>): Boolean =
        scaledWeights.isNotEmpty() &&
            scaledWeights.all { (field, v) -> WEIGHT_KEYS[field]?.let { inKeyRange(it, v) } == true }

    /** Toleranzvergleich statt Gleichheit — siehe Befund 3. */
    fun sameValue(a: Double, b: Double): Boolean = abs(a - b) <= EPS

    private fun range(key: DoubleKey) = "[${scaled(key.min)},${scaled(key.max)}]"

    /** Kanonische Form: Feldmenge UND Grenzen, damit jede Aenderung den Hash wandern laesst. */
    fun ratioCanonical(): String = "[\"SMBRATIO\",\"$F_RATIO\",${range(RATIO_KEY)},$SCALE]"

    fun weightsCanonical(): String =
        WEIGHT_KEYS.entries.sortedBy { it.key }.joinToString(
            separator = ",", prefix = "[\"WEIGHTS\",", postfix = ",$SCALE]",
        ) { "[\"${it.key}\",${range(it.value)}]" }

    fun ratioHash(): String = sha256(ratioCanonical())
    fun weightsHash(): String = sha256(weightsCanonical())

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
