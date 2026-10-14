package app.aaps.fuse.core.controller

/**
 * Die Sofort-Freigabe am Mahlzeiten-Marker - verteilte Abgabe AB KNOPFDRUCK,
 * ohne auf CGM-Evidenz zu warten.
 *
 * Konsolidierung 2.0: Anhebung (`lift`) und Plan (`plan`, samt CLEARANCE-Verschiebung des Fensterstarts) sind
 * entfallen - der Plan speiste zuletzt nur den Uebergabeanker des ebenfalls entfallenen Mahlzeitenfundaments. Es
 * bleiben die Fenster-Konstanten (Buchung von `primeSpentU`, Rueckweg auf 1.x) und der Typ [Plan] fuer den Trail.
 * Die Beschreibung unten ist der Stand davor.
 *
 * WARUM ES SIE GIBT, gemessen an einem Fruehstueck: zwischen Essen (Marker)
 * und der ersten CGM-Regung vergingen viele Minuten, der Gipfel kam spaet,
 * danach folgte ein reales Tief - AUCH auf prod mit Marker und Temp-Target.
 * Diesen Verzug holt kein Schaetzer; Insulin ab dem Essen wirkt maximal etwa
 * AM Gipfel statt danach.
 *
 * WAS SIE IST: eine BEZIFFERTE, RUECKHOLBARE Wette. Die Huelle ist nach dem
 * Reversibilitaetsmass KC2-53 bemessen: per Basal-Null ist ueber 120 min eine
 * Menge in der Groessenordnung des Defaults zurueckholbar (Default 1,2 U).
 * Ein Fehl-Marker ohne
 * Mahlzeit ist damit durch Zurueckhalten vollstaendig korrigierbar, WENN die
 * Wache frueh dreht. Das unterscheidet sie von tim2000s' Prime-Boli, die
 * Toni am Boost-Port bewusst NICHT uebernommen hatte: dort ungedeckelt am
 * Signal, hier gedeckelt an der gemessenen Rueckholkapazitaet. Die alte Linie
 * wurde am 07.08. von Toni selbst neu gezogen ("die Insulinabgabe koennte
 * frueher beginnen sobald das Fruehstueck startet oder zumindest eine Art
 * Freigabe").
 *
 * WAS SIE NICHT DARF, und zwar strukturell statt per Disziplin:
 *  - OHNE ausdrueckliche manuelle Autorisierung hebt sie nur eine
 *    Entscheidung an, deren einzige Schwaeche fehlender BEDARF war
 *    ([FuseController.Block.NONE], NO_DEMAND, BELOW_PUMP_INCREMENT). Jede
 *    echte Sperre gewinnt dann unveraendert.
 *  - Sie respektiert dieselben Mengen-Deckel wie jede andere Freigabe
 *    (maxSmb, iobTH-Spielraum, maxIOB-Spielraum).
 *
 * WAS SICH AM 11.08. GEAENDERT HAT, und der Absatz oben behauptete bis dahin
 * das Gegenteil: mit eingeschalteter Einstellung `MarkerAuthorisesRelease` ist der
 * Knopfdruck eine AUSDRUECKLICHE MANUELLE INSULIN-AUTORISIERUNG. Tonis
 * Entscheidung dazu ist eindeutig - modellbasierte Annahmen duerfen den
 * markerfinanzierten Anteil dann nicht nachtraeglich wieder auf null setzen:
 *
 *   AUTORISIERT UEBERSTIMMBAR (Modell/Prognose):
 *     LOW / SAFETY_HOLD, GUARD_FLOOR, CLEARANCE, TAIL
 *   BLEIBT ZWINGEND GESCHLOSSEN (Wirklichkeit, nicht Modell):
 *     PUMP_BUSY und jeder Transportfehler, NO_INPUT, HEALTH_NOT_READY,
 *     fehlender Safety-Snapshot, FAKE_EXTENDED, LEDGER_HOLD,
 *     und ALLE absoluten Mengengrenzen (maxSmb, iobTH, maxIOB, Restbudget,
 *     Pumpenschritt)
 *
 * Die Trennlinie ist nicht "gefaehrlich vs. harmlos", sondern "wer weiss es
 * besser": bei unsichtbaren Kohlenhydraten weiss der Mensch, dass gleich
 * etwas kommt, und das Modell weiss es nicht. Bei einer belegten Pumpe oder
 * einem fehlenden Messwert weiss der Mensch NICHTS Zusaetzliches - dort
 * bleibt die Sperre. Die schuetzende Basalabsenkung laeuft in beiden Faellen
 * unveraendert weiter; ueberstimmt wird die Mengensperre, nicht der Schutz.
 *
 * WAS DAS FUER DIE PUNKTE UNTEN HEISST: sie beschreiben den Fall OHNE
 * Autorisierung. Mit Autorisierung entfaellt die Freigangsprobe, der
 * Schwanz-Deckel in `lift` entfaellt ebenfalls, und die Bahn darf sogar
 * ganz fehlen (s. `Input.safetyMinLowerMgdl` und [MarkerFallback]).
 *
 *  - CLEARANCE-Gate: die Guardbahn muss den Boden um die 60-min-Wirkung der
 *    RESTLICHEN Huelle ueberragen (Anteil `CLEARANCE_60MIN_FRACTION` bei
 *    DIA 9). Das ist die ehrliche Ersatzpruefung, solange CandidateSearch
 *    nicht verdrahtet ist - konservativ genug fuer die Nacht (Bahn nahe am
 *    Boden -> gesperrt), durchlaessig am Mahlzeitenbeginn (Bahn ~100 bei
 *    Boden 70 -> offen).
 *    WELCHE Guardbahn, seit C1/C2 (Codex-Adjudication H1/H2, K2 Punkte 6/8):
 *    die pessimistischste ueber ALLE glaubwuerdigen Bahnen - Haupt UND Bremse -
 *    und PRIOR-FREI. S. `Input.safetyMinLowerMgdl`.
 *  - Verteilung statt Klumpen: der Rest der Huelle wird gleichmaessig ueber
 *    das restliche Fenster gestreckt. JEDE im Fenster gelieferte Einheit
 *    zaehlt gegen die Huelle - auch evidenzgetriebene; sobald die Mahlzeit
 *    im CGM ankommt und der normale Pfad mehr fordert, ist die Wette
 *    beendet, nicht verdoppelt.
 */
object PrimeRelease {

    /** Abgabefenster ab Knopfdruck [min]. 15 statt laenger: die Wette gilt
     *  dem CGM-blinden Kopf der Mahlzeit; danach traegt Evidenz oder nichts. */
    /** Vorgabe, wenn der Aufrufer nichts anderes sagt. */
    const val WINDOW_MIN = 15

    /**
     * Absolute Wanduhr-Kappe ab Knopfdruck [min].
     *
     * GEMESSEN an einer realen Mahlzeit (L-Marker): der Knopfdruck fiel
     * in ein CLEARANCE-Nein, das 15 Minuten lang stand - danach war das
     * Fenster VORBEI und die gesamte Huelle verfallen, ohne dass sie
     * je erteilbar gewesen waere. Eine Freigabe, die abgelaufen ist, weil sie
     * nie erteilt werden KONNTE, ist keine Sicherheitsentscheidung, sondern
     * ein Buchungsfehler.
     *
     * Deshalb zaehlt das Fenster LIEFERBARE Minuten: solange die Clearance
     * sperrt, schiebt der Aufrufer den Fensterstart nach. Ohne eine zweite,
     * absolute Grenze liefe die Wette aber beliebig lange - und ihre
     * Begruendung ist ausdruecklich der CGM-BLINDE KOPF der Mahlzeit. Nach
     * dieser Kappe traegt Evidenz oder nichts, so wie vorher auch.
     *
     * 45 min = dieselbe Groesse wie das Marker-Boost-Fenster.
     */
    const val WALL_CEILING_MIN = 45

    // Konsolidierung 2.0: MARKER_PRIOR_MGDL_PER_MIN ist nach [MarkerScope] umgezogen (Wert unveraendert); der
    // Clearance-Anteil und die Eingabe des Plans sind mit ihm entfallen.

    data class Plan(
        val active: Boolean,
        /** Mindest-Freigabe dieses Zyklus [U], auf Pumpenschritte gerundet. */
        val floorU: Double,
        val remainingU: Double,
        val reason: String,
    )

    // Konsolidierung 2.0: die Anhebung (`lift`, Quelle PRIME) samt Blocklisten und der Plan (`plan`) sind
    // entfallen.
}
