package app.aaps.fuse.core.controller

import app.aaps.fuse.core.signal.GlucosePoint
import app.aaps.fuse.core.signal.MeasuredGlucose

/**
 * MESSBESTAETIGUNG DER HALTE-ANHEBUNG (Tonis Vertrag 15.09. abends) - reine
 * Rechnung, Zustand wird hinein- und herausgereicht.
 *
 * ANLASS: die erste v53-Fassung bestaetigte ueber zwei Zyklen
 * `UKF-Rate + Aktivitaet x ISF >= Antrieb`. Das ist eine BGI-bereinigte
 * Stoerungsrate, kein Nachweis eines tatsaechlichen Anstiegs - ein kleiner
 * Sensorsprung (+8 mg/dl, zwei Werte) startete die Anhebung, weil derselbe
 * Sprung die UKF-Rate in zwei Zyklen hintereinander trug.
 *
 * ZWEI GETRENNTE BEDINGUNGEN, KEINE UNABHAENGIGEN BELEGE: Bedarf (bereinigt)
 * und gemessene Entwicklung (roh) stammen aus DEMSELBEN Sensor. Diese Klasse
 * trennt die Pruefungen, behauptet aber keine statistische Unabhaengigkeit.
 *
 * WAS DIE BESTAETIGUNG VORANBRINGT:
 *  - nur NEUE Rohwerte mit eindeutigem Zeitstempel, nach dem zuletzt
 *    verbrauchten Wert und nach der Autorisierung; ein weiterer Loop-Lauf ueber
 *    dieselben Daten bringt nichts,
 *  - je [BLOCK_READINGS] neue Werte bilden EINEN Block; der Median von drei ist
 *    gegen genau einen abweichenden Wert immun,
 *  - ein Block bestaetigt nur, wenn sein Median ein NEUES HOCH ueber allen
 *    Blockmedianen der letzten [HIGH_WINDOW_MS] um mehr als
 *    [NEW_HIGH_MARGIN_MGDL] ist UND der Bedarf (vom Aufrufer) in DIESEM Zyklus
 *    erfuellt ist. Der Bedarf wird damit nur einmal je frischem Block gezaehlt -
 *    ein Sprung, der die UKF-Rate ueber mehrere Zyklen traegt, zaehlt nicht
 *    mehrfach,
 *  - [CONFIRM_BLOCKS] aufeinanderfolgende bestaetigende Bloecke.
 * Ein Block ohne neues Hoch, ohne Bedarf oder ohne Bezugsblock setzt die Folge
 * auf 0. Schliesst ein Zyklus mehrere Bloecke ab, kann nur der juengste mit dem
 * Bedarf dieses Zyklus bestaetigen; aeltere setzen die Folge zurueck
 * (UNCHECKED_BLOCK) - konservativ.
 *
 * NEUBEGINN: Wechsel der Signalepoche (Luecke, Sensor-/Kalibrierepoche,
 * Eingangssprung - [MeasuredGlucose.signalEpochTs]), Verlust oder Wechsel der
 * MEAL-Autorisierung, Schalter aus. Der Bezugsblock darf aus Werten VOR der
 * Autorisierung derselben Epoche stammen - er bestaetigt nie selbst.
 *
 * AUSDRUECKLICH NICHT VERSPROCHEN: Artefakte allein an der Kurvenform sicher zu
 * erkennen. Nicht unterscheidbar sind u. a. eine langsame Artefaktrampe, die
 * neue Hochs bildet, ein mehrstufiger Sprung im Blocktakt und eine Erholung
 * nach Kompression, die das Niveau vor dem Tief uebersteigt oder laenger als
 * das Fenster dauert.
 */
object MeasuredRiseEvidence {

    const val BLOCK_READINGS = 3
    const val CONFIRM_BLOCKS = 2

    /** Rohwerte sind ganzzahlig; ein neues Hoch muss mehr als eine Aufloesungsstufe betragen. */
    const val NEW_HIGH_MARGIN_MGDL = 1.0

    /** Bezugsfenster fuer "neues Hoch" - dieselbe Laenge wie das Messfenster der Signalquelle. */
    const val HIGH_WINDOW_MS = 18 * 60_000L

    enum class Denial {
        DISABLED,
        NOT_MEAL_AUTHORIZED,
        NO_BASELINE,
        NOT_NEW_HIGH,
        NEED_NOT_MET,
        UNCHECKED_BLOCK,
        NOT_CONFIRMED,
    }

    data class Block(val endTs: Long, val medianMgdl: Double)

    data class State(
        val epochTs: Long = 0L,
        val authorizationId: Long = 0L,
        val lastConsumedTs: Long = 0L,
        val blocks: List<Block> = emptyList(),
        val confirmations: Int = 0,
    ) {
        companion object {
            val EMPTY = State()
        }
    }

    data class Input(
        val enabled: Boolean,
        val mealAuthorized: Boolean,
        /** Gepinnte Markeridentitaet (= Markerzeit); 0 = keine. */
        val authorizationId: Long,
        val measured: MeasuredGlucose,
        /** Bedarf in DIESEM Zyklus erfuellt (positiver Antrieb, bereinigte Rate >= Antrieb). */
        val needMet: Boolean,
    )

    data class Result(
        val state: State,
        val confirmed: Boolean,
        /** null = bestaetigt. */
        val denial: Denial?,
        /** In diesem Zyklus abgeschlossene Bloecke. */
        val newBlocks: Int,
        /** Die Folge begann in diesem Zyklus neu (Epoche/Autorisierung). */
        val restarted: Boolean,
    )

    fun step(prev: State, i: Input): Result {
        if (!i.enabled) return Result(State.EMPTY, false, Denial.DISABLED, 0, prev != State.EMPTY)
        if (!i.mealAuthorized || i.authorizationId <= 0L)
            return Result(State.EMPTY, false, Denial.NOT_MEAL_AUTHORIZED, 0, prev != State.EMPTY)
        val epoch = i.measured.signalEpochTs
        val fortsetzung = epoch > 0L && prev.epochTs == epoch && prev.authorizationId == i.authorizationId
        val punkte = i.measured.points
            .filter { it.rawBg.isFinite() && (epoch <= 0L || it.sourceTs >= epoch) }
            .distinctBy { it.sourceTs }
            .sortedBy { it.sourceTs }
        val basis = if (fortsetzung) prev else neuerStart(epoch, i.authorizationId, punkte)
        val neu = punkte.filter { it.sourceTs > basis.lastConsumedTs }
        val anzahl = neu.size / BLOCK_READINGS

        var blocks = basis.blocks
        var folge = basis.confirmations
        var last = basis.lastConsumedTs
        var blockDenial: Denial? = null
        for (k in 0 until anzahl) {
            val b = neu.subList(k * BLOCK_READINGS, (k + 1) * BLOCK_READINGS)
            val median = median(b)
            val ende = b.last().sourceTs
            val bezug = blocks.filter { it.endTs >= ende - HIGH_WINDOW_MS }
            val juengster = k == anzahl - 1
            blockDenial = when {
                bezug.isEmpty() -> Denial.NO_BASELINE
                median <= bezug.maxOf { it.medianMgdl } + NEW_HIGH_MARGIN_MGDL -> Denial.NOT_NEW_HIGH
                !juengster -> Denial.UNCHECKED_BLOCK
                !i.needMet -> Denial.NEED_NOT_MET
                else -> null
            }
            folge = if (blockDenial == null) minOf(folge + 1, 99) else 0
            blocks = bezug + Block(ende, median)
            last = ende
        }
        val state = State(epoch, i.authorizationId, last, blocks, folge)
        val confirmed = folge >= CONFIRM_BLOCKS
        val denial = when {
            confirmed -> null
            anzahl > 0 && blockDenial != null -> blockDenial
            else -> Denial.NOT_CONFIRMED
        }
        return Result(state, confirmed, denial, anzahl, !fortsetzung && prev != State.EMPTY)
    }

    /** Neubeginn: Bezugsblock aus den juengsten Werten bis zur Autorisierung (derselben Epoche). */
    private fun neuerStart(epoch: Long, authorizationId: Long, punkte: List<GlucosePoint>): State {
        val vorher = punkte.filter { it.sourceTs <= authorizationId }.takeLast(BLOCK_READINGS)
        val bezug = if (vorher.size == BLOCK_READINGS) listOf(Block(vorher.last().sourceTs, median(vorher))) else emptyList()
        val kante = maxOf(authorizationId, vorher.lastOrNull()?.sourceTs ?: 0L)
        return State(epoch, authorizationId, kante, bezug, 0)
    }

    private fun median(b: List<GlucosePoint>): Double {
        val v = b.map { it.rawBg }.sorted()
        val m = v.size / 2
        return if (v.size % 2 == 1) v[m] else (v[m - 1] + v[m]) / 2.0
    }
}
