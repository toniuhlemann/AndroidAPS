package app.aaps.fuse.core.controller

/**
 * DAS EINTRITTSTOR DER TEILBASAL-RUECKKEHR - an EINER Stelle.
 *
 * Vorher stand diese Bedingung im Runner und ein zweites Mal im
 * Auswertungs-Rig. Zwei Kopien einer Torbedingung laufen auseinander,
 * und dann misst der Rig eine andere Regel, als die Produktion faehrt.
 *
 * ===================================================================
 * DAS ENTFERNTE UKF-TOR (Toni, nach der Mehrnaechte-Auswertung)
 * ===================================================================
 * Bis hierher verlangte das Tor zusaetzlich `ukfRatePerMin >= -0,03`.
 * Gemessen ueber sieben Naechte (1526 Zyklen mit laufender Null):
 * diese Bedingung schloss das Tor in 1278 Faellen, davon in **633 als
 * EINZIGER Grund** - also obwohl LowThreat, gemessenes Tief,
 * Abwaertsrisiko und Signalgesundheit den Eintritt gerade NICHT
 * verhinderten.
 *
 * Sie war ausserdem ein Entwurfswiderspruch: die Stufe laesst
 * `q1NichtFallend` bewusst weg, weil der milde Restabfall genau ihr
 * Fall ist - behielt aber die SCHAERFERE Flachheitsforderung bei.
 *
 * Ein steiler Fall wird deshalb NICHT mehr ueber eine zweite
 * Flachheitspruefung abgefangen, sondern dort, wo er hingehoert:
 *  - [LowThreatGate.measuredDescentRisk] (gemessener Fall mit
 *    Bolusueberdeckung und Bodenkontakt im Fenster) -> `descentRisk`,
 *  - [LowThreatGate.evaluate] -> `verdict != NONE`,
 *  - das gemessene Tief,
 *  - [floorApproachBlocks] - die BOLUSUNABHAENGIGE Bodenannaeherung,
 *  - und die vollstaendige [BasalRecoverySearch], die eine Rate nur
 *    freigibt, wenn die Bahn MIT ihr den Boden noch traegt.
 *
 * Was hier NICHT geaendert wurde: der allgemeine UKF, das LowThreat-
 * Tor selbst und der Zero-Latch bleiben unangetastet. Entfernt ist
 * ausschliesslich die zusaetzliche Flachheitsforderung DIESER Stufe.
 */
object PartialRecoveryGate {

    /**
     * Zusammenhaengende offene Zyklen bis zum Eintritt.
     *
     * FUENF statt drei. Gemessen kostet der konservativere Eintritt
     * ohne UKF-Tor fast nichts (226,4 -> 206,5 min Teilstufe, in der
     * einzigen Nacht mit belegtem Profilbasal 0,600 -> 0,597 U), waehrend
     * er unter dem alten strengen Tor teuer gewesen waere (71,2 -> 27,0
     * min). Das Verhaeltnis kehrt sich um, weil die Stufen ohne das Tor
     * laenger und zusammenhaengender werden.
     */
    const val ENTRY_CYCLES = 5

    /**
     * Steht das Tor in DIESEM Zyklus offen?
     *
     * Alle Bedingungen sind Sperren; keine ist optional. Die Reihenfolge
     * ist ohne Bedeutung - es ist eine Konjunktion, kein Fruehausstieg
     * mit Nebenwirkung.
     */
    fun open(
        enabled: Boolean,
        zeroLatchActive: Boolean,
        measuredLow: Boolean,
        descentRiskActive: Boolean,
        healthReady: Boolean,
        verdictNone: Boolean,
        /** s. [floorApproachBlocks] - die bolusunabhaengige Sperre. */
        floorApproaching: Boolean,
    ): Boolean =
        enabled &&
            zeroLatchActive &&
            !measuredLow &&
            !descentRiskActive &&
            healthReady &&
            verdictNone &&
            !floorApproaching

    /**
     * DIE BOLUSUNABHAENGIGE BODENANNAEHERUNG - die Luecke, die das
     * Entfernen des UKF-Tors hinterlassen haette.
     *
     * [LowThreatGate.measuredDescentRisk] verlangt AUSDRUECKLICH eine
     * Bolusueberdeckung (`bolus * ISF > Abstand zum Boden`); ohne sie
     * liefert es kein Risiko, egal wie steil der Verlauf faellt. Genau
     * dieser Fall - starker Fall ohne Bolusdeckung - fiel nach dem
     * Entfernen des Flachheitstors allein auf die Bahnpruefung zurueck.
     *
     * Diese Pruefung ist NICHT das alte `UKF >= -0,03` in neuer Form. Der
     * Unterschied ist der Bezug: die alte Schwelle war eine reine
     * FLACHHEITSforderung ohne jeden Bezug zum Boden und sperrte deshalb
     * auch bei BG 200 und -0,05 mg/dl je min. Hier wird gefragt, ob der
     * GEMESSENE Verlauf den Boden im NAHHORIZONT ueberhaupt erreichte:
     *
     *     minutesToFloor = (BG - Guardboden) / |Fallrate|
     *
     * Beispiele mit Boden 70 und Horizont 30 min:
     *  - BG 100, -2,0/min  -> 15 min  -> GESPERRT (das ist der Fall)
     *  - BG 100, -0,15/min -> 200 min -> offen
     *  - BG 200, -2,0/min  -> 65 min  -> offen (die Bahnpruefung uebernimmt)
     *
     * Gesperrt wird nur bei ALLEN drei Bedingungen zugleich: gesundes
     * Signal, gemessene NEGATIVE Rate und Bodenkontakt im Horizont. Fehlt
     * die Rate, sperrt DIESE Pruefung nicht - dafuer sind Health und
     * LowThreat zustaendig; eine Sperre bei fehlendem Messwert waere die
     * Rueckkehr zum alten Tor durch die Hintertuer.
     */
    fun floorApproachBlocks(
        signalHealthy: Boolean,
        bgMgdl: Double?,
        fallRatePerMin: Double?,
        guardFloorMgdl: Double,
        horizonMin: Double,
    ): Boolean {
        if (!signalHealthy) return false
        if (bgMgdl == null || !bgMgdl.isFinite()) return false
        if (fallRatePerMin == null || !fallRatePerMin.isFinite() || fallRatePerMin >= 0.0) return false
        if (!guardFloorMgdl.isFinite() || !horizonMin.isFinite() || horizonMin <= 0.0) return false
        // Steht der Boden schon unter uns, ist die Annaeherung negativ und
        // damit erst recht im Horizont.
        val minutesToFloor = (bgMgdl - guardFloorMgdl) / kotlin.math.abs(fallRatePerMin)
        if (!minutesToFloor.isFinite()) return false
        return minutesToFloor <= horizonMin
    }

    /**
     * Der Streak-Anschluss auf der SIGNAL-Uhr: streng steigend und
     * hoechstens 90 s Abstand. Ein wiederholter oder zurueckspringender
     * Messpunkt ist kein neuer Beleg, auch wenn der Zyklus weiterlaeuft.
     */
    const val ANSCHLUSS_MAX_MS = 90_000L

    fun anschluss(letzterSourceTs: Long, sourceTs: Long): Boolean =
        letzterSourceTs > 0L &&
            sourceTs > letzterSourceTs &&
            sourceTs - letzterSourceTs <= ANSCHLUSS_MAX_MS

    /** Der fortgeschriebene Zaehler. 0 = Tor zu, Rueckfall im selben Zyklus. */
    fun streak(offen: Boolean, bisher: Int, letzterSourceTs: Long, sourceTs: Long): Int =
        if (!offen) 0 else if (anschluss(letzterSourceTs, sourceTs)) bisher + 1 else 1
}
