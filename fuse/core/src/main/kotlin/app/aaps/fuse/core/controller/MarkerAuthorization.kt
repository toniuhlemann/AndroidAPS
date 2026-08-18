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
     * DIE EINE ENTSCHEIDUNGSSTELLE - ein exhaustives `when` OHNE `else`.
     *
     * WARUM NICHT ZWEI MENGEN, wie es hier zuerst stand (Toni 18.08., P1).
     * Der zugehoerige Test behauptete, jeder neue `Block`-Wert erzwinge eine
     * bewusste Einordnung. Er tat es nicht: ein neuer Wert war weder in der
     * Lift-Liste noch in der erwarteten Lift-Liste, beide Seiten ergaben
     * `false`, und der Test blieb GRUEN. Die Zusicherung stand nur im
     * Kommentar.
     *
     * Ein `when` ohne `else` verlagert den Riegel vom Test in den COMPILER:
     * ein neuer Enumwert laesst dieses Modul nicht mehr uebersetzen, bis
     * jemand ihn eingeordnet hat. Das ist die staerkere Bauform - ein Test
     * kann uebersehen werden, ein Uebersetzungsfehler nicht.
     *
     * @param authorized hat ein bewusster Markerdruck Insulin autorisiert?
     */
    fun lifts(block: FuseController.Block, authorized: Boolean = true): Boolean =
        when (block) {
            // ---- Hier fehlte nur BEDARF, keine Sicherheit ----------------
            // Diese drei sagen nichts ueber Gefahr aus: der normale Pfad sah
            // in diesem Zyklus nichts zu tun. Sie sind auch OHNE
            // Autorisierung hebbar.
            FuseController.Block.NONE                 -> true
            FuseController.Block.NO_DEMAND            -> true
            FuseController.Block.BELOW_PUMP_INCREMENT -> true

            // ---- Das MODELLURTEIL, nur mit Autorisierung ------------------
            // GUARD_FLOOR vergleicht die PROGNOSTIZIERTE Unterkante gegen den
            // Boden. Genau diese Unterkante wird nach einer Prime-Abgabe vom
            // eigenen Insulin nach unten gerechnet - der Guard sperrt dann
            // die Nachversorgung mit der Wirkung der eigenen Phase A.
            FuseController.Block.GUARD_FLOOR          -> authorized

            // ---- WIRKLICHKEIT: nie hebbar ---------------------------------
            // SAFETY_HOLD traegt SafetyReason.LOW und der entsteht aus
            // `bg < lowEnterMgdl` mit signalInputBg = signal.rawBg - dem
            // ROHEN Messwert. Keine Autorisierung macht einen gemessenen
            // Wert ungeschehen.
            FuseController.Block.SAFETY_HOLD          -> false

            // Signal- und Zustandsfehler: der Zyklus weiss nicht, wo er steht.
            FuseController.Block.HEALTH_NOT_READY     -> false
            FuseController.Block.HORIZON_MISSING      -> false
            // NO_INPUT hat der Compiler beim ersten Uebersetzen dieses `when`
            // eingefordert - er stand in keiner der beiden frueheren Mengen und
            // waere dort stillschweigend auf "hart" gefallen. Zufaellig
            // richtig, aber unbemerkt: genau der Fall, gegen den diese Bauform
            // gebaut ist.
            FuseController.Block.NO_INPUT             -> false

            // Harte Mengengrenzen - sie begrenzen, WIEVIEL insgesamt an Bord
            // sein darf, und stehen ueber jeder einzelnen Autorisierung.
            FuseController.Block.IOB_TH_REACHED       -> false
            FuseController.Block.MAX_IOB_REACHED      -> false

            // Buchfuehrung und Transport.
            FuseController.Block.LEDGER_HOLD          -> false
            FuseController.Block.PUMP_BUSY            -> false

            // ---- Der Schwanz, in BEIDEN Formen ---------------------------
            //
            // TAIL STAND HIER ZUERST AUF "hart", UND DAS WAR FALSCH (Toni
            // 18.08.). Der Schwanz erscheint im Controller in zwei Gestalten,
            // und sie haengen an einem Vorzeichen:
            //
            //   headroomU <= 0  -> frueher Return mit Block.TAIL
            //                      (FuseController: "if (tail != null &&
            //                      tail.usable && tail.headroomU <= 0.0)")
            //   headroomU >  0  -> der Fluss erreicht die Kappenliste, wo
            //                      "tailHeadroom" eine Kappe unter anderen ist
            //
            // Waere der Block hart und nur die Kappe hebbar, entstuende am
            // Nullpunkt eine unlogische Kante: bei +0,001 U kaeme das
            // Fundament durch, bei -0,001 U waere es tot. Und genau die
            // negative Seite ist nach Phase A der Normalfall - das gerade
            // abgegebene Prime-Insulin steht auf der Haftungsseite, waehrend
            // die Kohlenhydrate, die es finanziert haben, nirgends stehen.
            // Phase B waere damit wieder wirkungslos.
            //
            // Beide Gestalten sind DIESELBE Haftungsprognose ueber H - eine
            // Modellaussage, und zwar eine mit wachsendem Fehler, weil der
            // Horizont 120 Minuten weit reicht. Also beide hebbar.
            //
            // DAS AENDERT AUCH PRIME, und das ist Absicht: dieselbe Kante
            // existierte dort. Ein Prime mit Headroom -0,001 starb am Block,
            // mit +0,001 nicht.
            FuseController.Block.TAIL                 -> authorized

            // CANDIDATE bleibt hart, und der Grund ist ein anderer als bei
            // TAIL (Toni 18.08.): der Block ist ein SAMMELBEGRIFF. Die
            // Kandidatensuche lehnt sowohl aus modellbasierten Gruenden ab
            // (Guard risse MIT der Dosis) als auch aus technischen oder
            // korrupten. Ihn pauschal zu heben waere fail-open - man wuesste
            // nicht, was man hebt.
            //
            // WENN DER REPLAY ZEIGT, dass Phase B haeufig an einem CANDIDATE
            // mit Ursache GUARD_FLOOR stirbt, ist die Antwort NICHT, diesen
            // Sammelblock freizugeben, sondern den zugrunde liegenden
            // `CandidateSearch.Reject` TYPISIERT weiterzureichen. Dann laesst
            // sich die modellbasierte Teilmenge heben und der Rest nicht.
            FuseController.Block.CANDIDATE            -> false
        }

    /**
     * OHNE Autorisierung hebbar - abgeleitet, nicht zweitgefuehrt.
     */
    val LIFTABLE_WITHOUT_AUTHORIZATION: Set<FuseController.Block> =
        FuseController.Block.entries.filter { lifts(it, authorized = false) }.toSet()

    /**
     * MIT Autorisierung hebbar - ebenfalls abgeleitet. Beide Mengen stammen
     * aus [lifts]; eine eigene Aufzaehlung waere die zweite Wahrheit, die
     * dieser Umbau gerade beseitigt hat.
     */
    val LIFTABLE_ON_AUTHORIZATION: Set<FuseController.Block> =
        FuseController.Block.entries.filter { lifts(it, authorized = true) }.toSet()

    /** Fuer den Vergleich an einer Stelle. */
    fun liftsWithoutAuthorization(block: FuseController.Block): Boolean =
        lifts(block, authorized = false)
}
