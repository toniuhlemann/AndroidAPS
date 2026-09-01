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
    ): Boolean =
        enabled &&
            zeroLatchActive &&
            !measuredLow &&
            !descentRiskActive &&
            healthReady &&
            verdictNone

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
