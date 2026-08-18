package app.aaps.fuse.core.controller

/**
 * DIE GEMEINSAME AUTORISIERUNGSPOLITIK VON PHASE A UND PHASE B
 * (Toni 18.08.).
 *
 * Der bewusste Markerdruck autorisiert Insulin. Diese Datei sagt, WAS er
 * damit ueberstimmen darf - und was nicht:
 *
 *     MODELL UEBERSTIMMBAR, WIRKLICHKEIT NICHT.
 *
 * WARUM EINE EIGENE STELLE. Prime (Phase A) und das Mahlzeitenfundament
 * (Phase B) stammen aus DERSELBEN Autorisierung: ein Knopfdruck, ein
 * Budget, nur zeitlich verteilt. Zwei Tabellen waeren zwei Wahrheiten, und
 * die driften - hier besonders teuer, weil die Frage "was darf ein Marker"
 * bei jedem neuen Block-Wert erneut gestellt wird. `PrimeRelease` liest
 * seine Listen deshalb von hier.
 *
 * DIE TRENNLINIE ist nicht "wie gefaehrlich klingt es", sondern:
 *
 *   MODELLURTEIL    beruht auf einer VORHERSAGE. Bei einer Mahlzeit kann es
 *                   systematisch danebenliegen, weil die Bahn per
 *                   Konstruktion kohlenhydratfrei ist - sie rechnet das
 *                   gerade abgegebene Prime-Insulin nach unten, ohne die
 *                   Kohlenhydrate zu kennen, die es finanziert hat.
 *                   Ueberstimmbar.
 *
 *   WIRKLICHKEIT    beruht auf einer MESSUNG oder einer harten Grenze.
 *                   Nicht ueberstimmbar - keine Autorisierung der Welt
 *                   macht einen gemessenen Wert ungeschehen.
 *
 * DER FALL, DER DIESE DATEI AUSGELOEST HAT. `SAFETY_HOLD` stand bis zum
 * 18.08. auf der hebbaren Seite, mit der Begruendung, es sei "ein
 * MODELL-Block wie GUARD_FLOOR". Das war faktisch falsch:
 *
 *     SAFETY_HOLD <- safetyReasons.isNotEmpty()
 *                 <- SafetyReason.LOW
 *                 <- bg < lowEnterMgdl        (ObserverStateMachine)
 *     mit signalInputBg = signal.rawBg        (FuseCycleRunner)
 *
 * Das ist der rohe Messwert. Der Marker hat damit das GEMESSENE TIEF
 * ueberstimmt. Tonis Entscheidung: "Der Marker autorisiert eine Mahlzeit,
 * aber kein Insulin bei aktuell gemessenem Tief."
 */
object MarkerAuthorization {

    /**
     * OHNE Autorisierung hebbar: hier fehlte nur BEDARF, keine Sicherheit.
     *
     * Diese drei sagen nichts ueber Gefahr aus - sie sagen, dass der normale
     * Pfad in diesem Zyklus nichts zu tun sah.
     */
    val LIFTABLE_WITHOUT_AUTHORIZATION: Set<FuseController.Block> = setOf(
        FuseController.Block.NONE,
        FuseController.Block.NO_DEMAND,
        FuseController.Block.BELOW_PUMP_INCREMENT,
    )

    /**
     * MIT Autorisierung zusaetzlich hebbar.
     *
     * Nur `GUARD_FLOOR`, und nur weil er echt eine Prognose ist: er
     * vergleicht die prognostizierte Unterkante `minLower` gegen den Boden.
     * Genau diese Unterkante wird nach einer Prime-Abgabe vom eigenen Insulin
     * nach unten gerechnet - der Guard sperrt dann die Nachversorgung mit der
     * Wirkung der eigenen Phase A.
     *
     * DIE SCHWANZKAPPE gehoert der Sache nach hierher, taucht aber nicht auf:
     * sie ist kein Basis-BLOCK, sondern eine Mengenkappe, und wird an der
     * Stelle uebersprungen, an der sie greift. Sie ist ebenfalls eine
     * Haftungsprognose - ueber einen 120-Minuten-Horizont sogar eine mit
     * wachsendem Fehler.
     */
    val LIFTABLE_ON_AUTHORIZATION: Set<FuseController.Block> =
        LIFTABLE_WITHOUT_AUTHORIZATION + setOf(FuseController.Block.GUARD_FLOOR)

    /**
     * Darf eine autorisierte Menge diesen Block ueberstimmen?
     *
     * FAIL-CLOSED DURCH BAUART: die Antwort ist eine Mitgliedschaft in einer
     * AUFZAEHLUNG, kein Ausschluss. Ein neu hinzugefuegter Block-Wert landet
     * damit automatisch auf der harten Seite - wer ihn heben will, muss ihn
     * eintragen und sich dabei erklaeren. Andersherum waere jeder neue Wert
     * stillschweigend ueberstimmbar.
     */
    fun lifts(block: FuseController.Block): Boolean = block in LIFTABLE_ON_AUTHORIZATION

    /**
     * Und ohne Autorisierung - fuer den Vergleich an einer Stelle.
     */
    fun liftsWithoutAuthorization(block: FuseController.Block): Boolean =
        block in LIFTABLE_WITHOUT_AUTHORIZATION
}
