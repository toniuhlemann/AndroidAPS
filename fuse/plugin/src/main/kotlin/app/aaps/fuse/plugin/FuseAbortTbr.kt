package app.aaps.fuse.plugin

import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.fuse.core.controller.FuseController

/**
 * DER ABBRUCH-VERTRAG — genau EINE Implementierung (C7c, Codex-Adjudication
 * D-Tabelle C7 / Frage 2, K2 Punkt 10).
 *
 * Der Vertrag stammt aus F-P0-07: ein Abbruch darf eine LAUFENDE POSITIVE TBR
 * nicht einfach bis zu ihrem Ende weiterlaufen lassen (fail-silent). Er lag
 * bisher als lokale Funktion IM [FuseCycleRunner] - und damit ausserhalb der
 * Reichweite des Pfades, der ihn am dringendsten braucht: eine Ausnahme, die
 * aus `runner.run()` entkommt, wurde in `FusePlugin.invoke()` gefangen und
 * erzeugte ein RT mit `tbr = null`. Genau der Fall, gegen den der Vertrag
 * geschrieben wurde, lief also am Vertrag vorbei.
 *
 * Deshalb steht die Regel hier, wird von BEIDEN Pfaden aufgerufen und nirgends
 * kopiert. Eine zweite Kopie waere schlimmer als keine: sie wuerde beim
 * naechsten Fix nur an einer Stelle nachgezogen.
 *
 * DIE REGEL, in beiden Fassungen identisch:
 *
 *   laeuft nichts                        -> keine Anforderung, kein Alarm
 *   Rate UEBER Profilbasal               -> Abbruch (rate 0 / duration 0)
 *   Rate auf/unter Profilbasal           -> keine Anforderung (ein blinder
 *                                           Abbruch wuerde eine Absenkung
 *                                           beenden und MEHR Insulin freigeben)
 *   FAKE_EXTENDED                        -> kein Eingriff, aber ALARM (nicht
 *                                           abbrechbare Abgabe, ReadOnlyHold)
 *   Snapshot/Profil unlesbar             -> kein blinder Eingriff, aber ALARM
 */
object FuseAbortTbr {

    /**
     * `request = null` heisst ausdruecklich "nichts anfordern, Laufendes
     * unberuehrt lassen" — NICHT "Rate 0". [alarm] traegt den Fall, in dem
     * FUSE die Lage nicht klassifizieren konnte; er muss sichtbar bleiben,
     * sonst ist "es lief nichts" von "wir wissen es nicht" nicht zu trennen.
     */
    data class Outcome(val request: FuseController.TbrRequest?, val alarm: Boolean)

    private val NOTHING = Outcome(null, false)
    private val UNKNOWN = Outcome(null, true)

    /** Der Abbruch ist `rate 0 / duration 0` — diese Kombination liest der Loop
     *  unmittelbar als Cancel (gleiche Kodierung wie in TbrPolicy). */
    private val CANCEL = Outcome(FuseController.TbrRequest(0.0, 0), false)

    /**
     * Die REINE Regel, ohne AAPS-Quellen — der testbare Kern.
     *
     * @param fakeExtended ein als Temp gefuehrter Extended Bolus: laufende,
     *   nicht abbrechbare Abgabe.
     * @param runningRateUPerH absolute Rate der laufenden TBR, `null` = nicht
     *   lesbar/nicht klassifizierbar.
     * @param scheduledBasalUPerH Profilbasal, `null` = kein Profil.
     */
    fun classify(fakeExtended: Boolean, runningRateUPerH: Double?, scheduledBasalUPerH: Double?): Outcome {
        if (fakeExtended) return UNKNOWN
        if (runningRateUPerH == null || scheduledBasalUPerH == null) return UNKNOWN
        if (!runningRateUPerH.isFinite() || !scheduledBasalUPerH.isFinite()) return UNKNOWN
        // Das Epsilon ist die Double-Toleranz, nicht die Pumpen-Toleranz: hier
        // wird nur POSITIV von NICHT-POSITIV getrennt, und im Zweifel gilt
        // "nicht positiv" - dann bleibt die laufende TBR stehen.
        return if (runningRateUPerH > scheduledBasalUPerH + 1e-9) CANCEL else NOTHING
    }

    /**
     * Die LESENDE Fassung. Vollstaendig in `runCatching`: ein Abbruchpfad darf
     * nicht daran scheitern, dass die Quelle, die ihn ausgeloest hat, immer
     * noch zickt — dann gilt UNKNOWN (kein Eingriff, Alarm).
     */
    fun evaluate(
        processedTbrEbData: ProcessedTbrEbData,
        profileFunction: ProfileFunction,
        nowMs: Long,
    ): Outcome = runCatching {
        val running = processedTbrEbData.getTempBasalIncludingConvertedExtended(nowMs)
            ?: return@runCatching NOTHING
        if (running.type == TB.Type.FAKE_EXTENDED) return@runCatching UNKNOWN
        val prof = profileFunction.getProfile(nowMs) ?: return@runCatching UNKNOWN
        classify(false, running.convertedToAbsolute(nowMs, prof), prof.getBasal(nowMs))
    }.getOrElse { UNKNOWN }

    /** Grund des Ausnahmepfads. Als Konstante, weil im Trail danach gesucht
     *  wird: ein Zyklus, der gar nicht gerechnet hat, ist die interessanteste
     *  Zeile. Das Suffix trennt "klassifiziert" von "nicht klassifizierbar". */
    const val EXCEPTION_REASON = "EXCEPTION"

    /**
     * Das fail-closed RT des AUSNAHMEPFADS - dieselbe Bauform wie der Abbruch
     * des Runners, damit es keine zweite Vorstellung davon gibt, was FUSE nach
     * einer Ausnahme anfordert.
     *
     * Es traegt KEINE Menge (`noInput`) und genau die TBR-Anforderung aus
     * [evaluate]. Der Pumpen-Riegel bleibt davor: gegen eine NICHT ERLAUBTE
     * Pumpe filtert [FuseRtBuilder] auch diesen Abbruch heraus. Gegen eine
     * erlaubte - VirtualPump oder den belegten Medtrum Nano - geht er durch;
     * genau dafuer ist er da.
     */
    fun abortRt(nowMs: Long, gate: FusePumpGate.Result, outcome: Outcome): RT =
        FuseRtBuilder.build(
            nowMs = nowMs,
            bgMgdl = null, targetMgdl = null, iobU = null, profileIsfMgdlPerU = null,
            decision = FuseController.noInput(
                if (outcome.alarm) "$EXCEPTION_REASON|TBR_UNCLASSIFIABLE" else EXCEPTION_REASON
            ),
            tbr = outcome.request,
            gate = gate,
        )
}
