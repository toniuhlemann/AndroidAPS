package app.aaps.fuse.core.controller

/**
 * DIE BASAL-GRUNDREGEL DER MAHLZEIT (Tonis Vertrag, 17.08.2026).
 *
 * "Waehrend einer aktiven Mahlzeiten-/Absorptionslage gilt Profilbasal als
 *  Untergrenze. Eine rein modellbedingte Prognose darf keine Zero-TBR setzen
 *  oder erneuern."
 *
 * DER ANLASS, gemessen am Geraet: Marker 18:56, Huelle 3,5 U auf 25 min. Ab
 * 19:05 gab FUSE jede Minute 0,15 U aus der autorisierten Huelle UND hielt
 * per Guard-Null das Profilbasal zurueck - 0,35 U ueber 30 min, netto 3,10
 * statt 3,5 U. Ausloeser war nicht ein Tief, sondern die kohlenhydratfrei
 * gerechnete Bahn: das Prime-Insulin geht als reine Absenkung ein, die
 * Mahlzeit, die es autorisiert hat, nicht. Quantifiziert aus dem Trail:
 * 7,13 mg/dl Bahnabsenkung je 0,15-U-SMB bei ISF 63, gemessene minLower-
 * Schritte -7,09 und -6,73 - die Null hat sich selbst ausgeloest. Und um
 * 19:30 wurde sie aus einer 6-Punkte-Reihe nach CGM-Luecke ERNEUERT
 * (minLower 8,8 bei BG 127 steigend). Tonis manueller TBR-Abbruch wurde im
 * Folgezyklus wieder ueberschrieben.
 *
 * WARUM ZUSTANDSBASIERT UND NICHT "3-4 STUNDEN" (Toni): die Episode kann
 * zwischendurch DORMANT sein oder nach fuenf Stunden eine neue Welle zeigen.
 * Geschuetzt ist die LAGE, nicht die Uhr:
 *
 *     mealBasalProtected = Marker-Freigabe aktiv
 *                          ODER Evidenzphase ACTIVE/PENDING_SEAL
 *
 * WAS DIE REGEL AUSDRUECKLICH NICHT UEBERSTIMMT (die Wirklichkeit):
 *
 *  - das GEMESSENE Tief ([Input.measuredLow], SAFETY_HOLD-Pfad) - es traegt
 *    das Modell-Bit ohnehin nicht;
 *  - das nahe, FALLENDE Tief ([nearLowFalling]): BG unter ~90 UND der
 *    gemessene schnelle Trend negativ. Bewusst der UKF-Trend und nicht `r`:
 *    `r` ist BGI-bereinigt und traegt das 18-Minuten-Fenster - fuer einen
 *    Low-Suspend zu verzoegert (Toni). Die 90er-Schwelle ist per Replay noch
 *    zu pruefen; bis dahin gilt sie als konservative Setzung.
 *  - eine laufende FAKE_EXTENDED-Rate ([Input.tbrControllable] false): kann
 *    FUSE die TBR-Achse nicht kontrollieren, hebt es auch nichts - sonst
 *    wuerde die Hebung den Zyklus als "sicher" ausweisen und die
 *    C8-SMB-Sperre des Extended-Bolus aushebeln. Genau diese Regression hat
 *    der Verdrahtungstest im ersten Anlauf gefangen.
 *
 * NEGATIVE BAHNWERTE BLEIBEN. minLower lief am 17.08. bis -61,1 mg/dl, und
 * das ist die richtige Antwort auf eine kohlenhydratfreie Frage - falsch war
 * nur die daraus abgeleitete AKTION waehrend aktiver Absorption. Ein Clamp
 * haette die Bahn als Diagnosewerkzeug zerstoert (Toni: "Sie sind
 * diagnostisch nuetzlich. Sie duerfen ... nur nicht mehr allein die
 * Basalversorgung abschalten.").
 */
object MealBasalGuard {

    /**
     * Kennung im `bindingLimit`, wenn die modellbedingte Null unter der
     * Grundregel gehoben wurde. Als Konstante: an ihr haengt der Nachweis am
     * Geraet, ein Tippfehler im Test wuerde denselben Text erfinden, den er
     * pruefen soll.
     */
    const val TBR_LIFTED_MARK = "mealBasal|"

    /** Kennung, wenn eine modellbedingte Null wegen UNREIFER Signalreihe
     *  nicht gesetzt/erneuert wurde (Zeile 3 der Vertragstabelle). */
    const val IMMATURE_MARK = "unreifeBahn|"

    /**
     * Unter dieser BG-Schwelle [mg/dl] gilt ein fallender gemessener Trend
     * als reale Nah-Tief-Gefahr - dann bleibt die Null zulaessig.
     * REPLAY-OFFEN (Toni 17.08.): "Die konkrete 90er-Schwelle muss per
     * Replay geprueft werden; ein schnelles Fallen knapp oberhalb 90 darf
     * nicht blind werden."
     */
    const val NEAR_LOW_BG_MGDL = 90.0

    /**
     * Ab wieviel Punkten das r-Segment als REIF gilt. Das r-Fenster ist
     * 18 min (BgiAdjustedSeries.WINDOW_MS); im eingeschwungenen Zustand
     * traegt es 17-18 Punkte, die Toleranz deckt einzelne Luecken. Unterhalb
     * ist die Bremsbahn per Konstruktion schlechter informiert als die reife
     * Bahn, die die vorige Null begruendet hat - am 17.08. stand minLower
     * aus 6 Punkten bei 8,8 mg/dl, waehrend der reale BG 127 zeigte und
     * stieg. Eine NEUE Null aus so einer Reihe ist keine Schutzentscheidung.
     */
    const val SEGMENT_MATURE_MIN_SAMPLES = 15

    /**
     * @param protectionActive die Mahlzeiten-/Absorptionslage: Marker-Freigabe
     *   aktiv ODER Evidenzphase ACTIVE/PENDING_SEAL. DORMANT, SUSPENDED,
     *   UNKNOWN, EXPIRED und NONE schuetzen NICHT - dort gilt der normale
     *   TBR-Vertrag.
     * @param measuredLow es liegt JETZT ein gemessenes Tief vor.
     * @param nearLowFalling s. [nearLowFalling].
     * @param tbrControllable false, wenn eine FAKE_EXTENDED-Rate laeuft.
     * @param segmentMature das r-Segment hat [SEGMENT_MATURE_MIN_SAMPLES]
     *   erreicht.
     */
    data class Input(
        val protectionActive: Boolean,
        val measuredLow: Boolean,
        val nearLowFalling: Boolean,
        val tbrControllable: Boolean,
        val segmentMature: Boolean,
    )

    /**
     * Das gemessene Nah-Tief: BG unter [NEAR_LOW_BG_MGDL] UND fallender
     * UKF-Trend. Beides MESSGROESSEN, keine Bahnen - die Regel unterscheidet
     * "die Prognose sagt tief" von "es faellt wirklich Richtung tief".
     */
    fun nearLowFalling(bgMgdl: Double?, ukfRatePerMin: Double?): Boolean =
        bgMgdl != null && bgMgdl.isFinite() && bgMgdl < NEAR_LOW_BG_MGDL &&
            ukfRatePerMin != null && ukfRatePerMin.isFinite() && ukfRatePerMin < 0.0

    /**
     * Wendet die Grundregel auf die fertig verifizierte Entscheidung an.
     *
     * DREI WIRKUNGEN, jede einzeln nachweisbar:
     *
     * 1. HEBUNG: eine als modellbedingt ausgewiesene Null
     *    ([FuseController.Decision.zeroTempModelOnly], gesetzt allein am
     *    Guard-Riegel) wird in der geschuetzten Lage zu KEEP_CURRENT -
     *    das Profilbasal bleibt.
     * 2. UNREIFE-SPERRE (unabhaengig von der Lage): eine modellbedingte Null
     *    aus einem unreifen r-Segment wird zu NO_NEW_POSITIVE - keine neue
     *    oder erneuerte Null aus einer Reihe, die das Fenster noch nicht
     *    fuellt. Eine LAUFENDE Null laeuft aus; sie wird hier weder erneuert
     *    noch blind beendet.
     * 3. STEMPEL: [FuseController.Decision.mealBasalProtected] traegt die
     *    wirksame Schutzlage in den Translator - dort gibt C7c den Abbruch
     *    einer LAUFENDEN Null neben einem SMB frei, der sonst am C7a-Veto
     *    hinge.
     *
     * Ein gemessenes Tief ([Input.measuredLow]) schaltet die Schutzlage ab;
     * seine Null traegt das Bit ohnehin nicht und bleibt unangetastet.
     */
    fun apply(decision: FuseController.Decision, input: Input): FuseController.Decision {
        val geschuetzt = input.protectionActive && input.tbrControllable &&
            !input.measuredLow && !input.nearLowFalling
        val modellNull = decision.zeroTempModelOnly &&
            decision.tbr == FuseController.TbrAction.ZERO_TEMP
        return when {
            modellNull && geschuetzt -> decision.copy(
                tbr = FuseController.TbrAction.KEEP_CURRENT,
                // Das Bit beschreibt die AKTUELLE Aktion - und die ist keine
                // Null mehr. Stehengelassen behauptete es eine, die es nicht
                // mehr gibt.
                zeroTempModelOnly = false,
                bindingLimit = TBR_LIFTED_MARK + decision.bindingLimit,
                mealBasalProtected = true,
            )
            // Zeile 3 der Vertragstabelle: "Signal unbekannt/unreif -> keine
            // neue modellbasierte Zero-TBR". NUR bei kontrollierbarer
            // TBR-Achse: unter FAKE_EXTENDED muss die Null-Absicht stehen
            // bleiben, damit die C8-SMB-Sperre greift.
            modellNull && !input.segmentMature && input.tbrControllable -> decision.copy(
                tbr = FuseController.TbrAction.NO_NEW_POSITIVE,
                zeroTempModelOnly = false,
                bindingLimit = IMMATURE_MARK + decision.bindingLimit,
                mealBasalProtected = geschuetzt,
            )
            else -> if (decision.mealBasalProtected == geschuetzt) decision
            else decision.copy(mealBasalProtected = geschuetzt)
        }
    }
}
