package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryQuery
import app.aaps.fuse.core.predictor.minSafetyLowerOf
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * FUSE-Regelkern: Trajektorie hinein, Kanalentscheidung heraus.
 *
 * Kein zweiter IOB-Abzug: die Wirkung des vorhandenen Insulins steckt bereits
 * in der Trajektorie (K2 v0.3 §0.1). Wer hier nochmal `- iob` rechnet, zieht
 * denselben Bestand zweimal ab.
 *
 * Rein und deterministisch — die VirtualPump-Sperre sitzt bewusst NICHT hier,
 * sondern im Android-Adapter, wo der tatsaechlich gewaehlte Pumpentyp bekannt
 * ist. Ein reiner Kern kann eine Pumpe nicht pruefen und soll es nicht
 * vortaeuschen.
 */
object FuseController {

    /** Toleranz beim Abwaertsrunden auf Pumpenschritte. Gross genug gegen die
     *  Binaerdarstellung von Zehnteln, klein genug, um nie einen zusaetzlichen
     *  Schritt zu erfinden. */
    private const val TICK_EPS = 1e-9

    /**
     * REBOUND-FENSTER NACH TIEF (4x gemessen am 07.08.: 07:09, 15:0x, 16:28,
     * ~17:3x): nach einem Tief liest der 18-min-Median die Erholungssteigung
     * als grosse Stoerung (16:28: r 3,3 elf Minuten nach q1<75 -> 1,65 U in
     * die zweite Senke). Die EINZIGE Information, die diese Lage von einem
     * Mahlzeitenbeginn unterscheidet, ist das juengste Tief selbst. War q1 in
     * den letzten [REBOUND_WINDOW_MIN] Minuten unter [REBOUND_LOW_MGDL],
     * bleibt die Rampe auf dem Korrektur-Anteil gedeckelt - egal wie hoch r
     * steigt. Beweisbar einseitig: der Deckel kann den Anteil nur senken.
     * Konstanten PROVISORISCH (Toni-Konvention Tief-Schutz ~101/75, Fenster
     * an die 45-min-Gerueststaffel angelehnt); Preferences erst nach Messung.
     */
    const val REBOUND_LOW_MGDL = 75.0
    const val REBOUND_WINDOW_MIN = 45

    /**
     * REBOUND v2 - TOTBAND (Vorfaelle #5/#6, Nacht 07./08.08.: 1,05 U + 0,60 U
     * TROTZ Ratio-Deckel, Treiber war insulinReq aus der tau-60-Extrapolation
     * der Erholungssteigung): Im Rebound-Fenster ist die Rueckkehr bis leicht
     * UEBER das Ziel ERWUENSCHT - kein Bedarf, solange der Anker unter
     * Ziel + [REBOUND_DEADBAND_MGDL] liegt. Haette in der Nacht alle 20
     * Rebound-Dosen genullt (BG lief 65->121 bei Ziel 97).
     */
    const val REBOUND_DEADBAND_MGDL = 25.0

    /** Kurzes tau im Rebound-Fenster [min]: Erholungssteigungen sterben in
     *  ~15 min - tau 60 schreibt sie eine Stunde fort. */
    const val REBOUND_TAU_MIN = 15

    data class State(
        val health: Health,
        val safetyHold: Boolean,
        val phase: Phase,
        val netIobU: Double,
        val bolusIobU: Double,
        val basalIobU: Double,
        val iobThU: Double,
        val maxIobU: Double,
        val targetMgdl: Double,
        val isfMgdlPerU: Double,
        /**
         * Anteil im KORREKTURBETRIEB — kein bestaetigter Anstieg.
         * Hier ist Zurueckhaltung richtig: was hier zuviel gegeben wird, faellt
         * in eine Lage, in der ohnehin nichts drueckt.
         */
        val smbRatioCorrection: Double,
        /**
         * Anteil im ANSTIEGSBETRIEB — der Observer hat einen Anstieg bestaetigt.
         *
         * DAS ist die Unterscheidung, um die es bei FCL geht: frueh den
         * Grossteil aufbauen, hinten heraus ruhiger. Bisher gab es EINEN
         * Anteil fuer beide Faelle, und ein einzelner Wert kann das nicht
         * leisten — er ist entweder fuer die Korrektur zu scharf oder fuer die
         * Mahlzeit zu zaghaft.
         *
         * Und der Unterschied zu autoISF: dort musste eine TT gesetzt werden,
         * damit die Gewichtsbruecke umschaltet. Hier erkennt der Observer es
         * selbst.
         */
        val smbRatioRise: Double,
        /**
         * Die GEMESSENE Evidenz — dieselbe Groesse, aus der auch die Bahn
         * entsteht. `null` heisst nicht berechenbar; dann gilt der
         * Korrekturanteil, denn ohne Evidenz gibt es keinen Grund fuer mehr.
         */
        val rSignedMgdlPerMin: Double?,
        /** Untere Kante der Rampe: bis hierher gilt der Korrekturanteil. */
        val riseRampLowRPerMin: Double,
        /** Obere Kante: ab hier gilt der volle Anstiegsanteil. */
        val riseRampHighRPerMin: Double,
        val pumpIncrementU: Double,
        val maxSmbU: Double,
        val pumpBusy: Boolean,
        /** q1 war in den letzten [REBOUND_WINDOW_MIN] min unter
         *  [REBOUND_LOW_MGDL] - die Rampe bleibt auf dem Korrektur-Anteil. */
        val reboundWindow: Boolean = false,
        /**
         * REST DES REBOUND-FENSTERS [min], `null` = unbekannt oder kein Fenster.
         *
         * Reine ANZEIGEGROESSE - sie geht in keine Entscheidung ein. Sie steht
         * hier, weil der Regler sonst nur `reboundWindow` true/false nach aussen
         * gibt und die Frage "wie lange noch?" am Geraet unbeantwortbar bleibt
         * (Toni 16.08.: "totband restzeit waere auch so ein wert").
         *
         * WARUM DIE ZAHL HIER GERECHNET WIRD UND NICHT IM VIEWER: die Restzeit
         * folgt aus `lastLowTs` und [REBOUND_WINDOW_MIN]. Beides im Viewer
         * nachzubilden hiesse, eine Konstante des Reglers zu duplizieren und
         * denselben Zustand aus zwei Quellen zu fuehren - genau die
         * Fehlerklasse, die dieses Projekt schon einmal teuer bezahlt hat.
         */
        val reboundRestMin: Int? = null,
        /** Wirksames Rebound-Totband [mg/dl] - Einstellung statt Konstante
         *  (Toni 09.08.); [REBOUND_DEADBAND_MGDL] ist nur noch der Default. */
        val reboundDeadbandMgdl: Double = REBOUND_DEADBAND_MGDL,
        /** Anker liegt im konfigurierten NACHTFENSTER. */
        val nightWindow: Boolean = false,
        /** Totband der Nacht [mg/dl]; 0 = aus. Ein erklaerter Marker hebt es
         *  auf, das Rebound-Totband dagegen nicht - s. NightWindow. */
        val nightDeadbandMgdl: Double = 0.0,
        /** Marker-Sonderrechte aktiv (hebt NUR das Nacht-Totband auf). */
        val markerBoost: Boolean = false,
        /**
         * Zeitpunkt des Knopfdrucks, 0 = kein Marker. REINES MESSFELD - der
         * Regler liest es nicht, und er darf es nicht: eine Entscheidung, die
         * am Alter des Markers haengt, gehoert in die Fenster-Logik des
         * Aufrufers, nicht hierher.
         *
         * Es steht trotzdem im State, weil ohne t0 die wichtigste Groesse im
         * FCL nicht messbar ist: die LATENZ vom Druck bis zur ersten Freigabe.
         */
        val markerArmedTs: Long = 0L,
        /** Episoden-Wahl "ohne Vorschuss" aus dem Marker-Dialog: die
         *  Freigabe-Huelle und der Erklaerungs-Kredit dieser Episode sind 0. */
        val markerNoPrime: Boolean = false,
        /** Rebound-Bedingung lag an, wurde aber durch einen aktiven Marker
         *  entwaffnet (Gas-vor-Bremse NUR fuer erklaertes Wissen, 08.08.):
         *  das Fenster schuetzt vor dem Jagen UNANGEKUENDIGTER Hypo-
         *  Gegenesser; ein gedrueckter Marker ist die Ankuendigung. Reines
         *  Mess-Flag - der Regler liest es nicht. */
        val reboundSuppressedByMarker: Boolean = false,
        /**
         * MAHLZEIT-FENSTER (Fenster-Trio, 08.08.): offen durch Marker, offene
         * Onset-Episode ODER kinematische Persistenz - mit 10-min-Gedaechtnis
         * gegen Plateau-Flattern (Abendessen 07.08.: echte langsame Mahlzeit
         * war kinematisch schwach, der Marker-Zweig traegt sie). AUSSERHALB
         * gilt der Korrektur-Anteil, egal wie hoch r steht - r kann positiv
         * sein, waehrend BG faellt (insulinbereinigte Stoerung, GPT-Befund
         * 07.08. bestaetigt: "BG faellt, r 0,98 -> Ratio 0,21" war semantisch
         * falsch).
         */
        val mealWindow: Boolean = false,
    ) {
        init {
            // Audit R95 F-P0-08: NaN im IOB-Snapshot schaltet beide
            // Headroom-Gates still aus (NaN <= x ist false), korrupt
            // negatives Bolus-IOB blaeht sie auf. Der State konstruiert im
            // CoreInputGuard - ein Wurf hier ist ein benannter Abort, keine
            // stille Fehlregelung. Grenzen bewusst grob: sie sollen KORRUPTE
            // Werte fangen, nie physiologische (Basal-IOB ist bei Zero-Temps
            // normal negativ; Bolus-IOB kann es nie sein).
            require(netIobU.isFinite() && bolusIobU.isFinite() && basalIobU.isFinite()) {
                "IOB nicht endlich: net=$netIobU bolus=$bolusIobU basal=$basalIobU"
            }
            require(bolusIobU >= -0.01) { "Bolus-IOB negativ: $bolusIobU (korrupte Behandlung?)" }
            require(kotlin.math.abs(netIobU) <= 100.0 && bolusIobU <= 100.0 && kotlin.math.abs(basalIobU) <= 100.0) {
                "IOB ausserhalb Plausibilitaet: net=$netIobU bolus=$bolusIobU basal=$basalIobU"
            }
            require(isfMgdlPerU.isFinite() && isfMgdlPerU > 0.0) { "ISF unplausibel: $isfMgdlPerU" }
            // DER DIVISOR DER RASTERUNG, bisher ungeprueft, obwohl der Block
            // ISF, Ziel, iobTH, maxIOB und alle drei IOB-Felder prueft. Bei 0.0
            // liefert `floor(x/0)*0` NaN, `NaN < 0.0` ist false, das
            // BELOW_PUMP_INCREMENT-Tor feuert nicht - und eine NaN-Dosis
            // verliesse den Regler. Davor stand bisher nur der Runner.
            require(pumpIncrementU.isFinite() && pumpIncrementU > 0.0) { "Pumpenschritt unplausibel: $pumpIncrementU" }
            require(maxSmbU.isFinite() && maxSmbU >= 0.0) { "maxSMB unplausibel: $maxSmbU" }
            require(targetMgdl.isFinite() && targetMgdl in 40.0..400.0) { "Ziel unplausibel: $targetMgdl" }
            require(iobThU.isFinite() && iobThU >= 0.0 && maxIobU.isFinite() && maxIobU >= 0.0) {
                "iobTH/maxIOB unplausibel: $iobThU/$maxIobU"
            }
        }

        /** Bindungsgroesse fuer iobTH: zurueckgehaltenes Basal waehrend Zero-TBR
         *  darf KEIN zusaetzliches SMB-Budget erzeugen (Fork-Praxis). */
        val capIobU: Double get() = max(netIobU, bolusIobU)

        /**
         * Der Anteil, der in DIESER Lage gilt — STETIG an `r` gekoppelt, nicht
         * an die Observer-Phase.
         *
         * WARUM NICHT AN DIE PHASE: die Phasenschwelle ist `thr = 0,50
         * mg/dl/min` und erkennt "irgendetwas steigt", nicht "eine Mahlzeit
         * laeuft". Ein echter Onset liegt bei 3-5 mg/dl/min. Der erste
         * Geraetelauf hat das sofort gezeigt: ein flacher Verlauf 100-105 mit
         * r ~ 0,65 stand als RISE_ACTIVE da und haette mit einem binaeren
         * Schalter den vollen Mahlzeitenanteil bekommen. Eine Detektionsschwelle
         * als Verstaerkungsschalter zweckzuentfremden behauptet eine
         * Trennschaerfe, die sie nicht hat.
         *
         * Die Phase bleibt, wofuer sie gebaut ist: sie sagt, OB eine Episode
         * laeuft. Wie STARK die Evidenz ist, steht in `r` — und die Verstaerkung
         * soll damit wachsen, nicht springen.
         *
         * Kein Phasen-Gate obendrauf: nach dem Peak faellt `r` von selbst, die
         * Rampe regelt das ohne zweite Regel.
         */
        val effectiveSmbRatio: Double
            get() {
                // Rebound-Deckel VOR der Rampe: die Erholung nach einem Tief
                // ist keine Mahlzeit, egal was der Median glaubt.
                if (reboundWindow) return smbRatioCorrection
                // Fenster-Trio: volle Rampe nur im Mahlzeit-Fenster.
                if (!mealWindow) return smbRatioCorrection
                val r = rSignedMgdlPerMin ?: return smbRatioCorrection
                if (!r.isFinite() || riseRampHighRPerMin <= riseRampLowRPerMin) return smbRatioCorrection
                val f = ((r - riseRampLowRPerMin) / (riseRampHighRPerMin - riseRampLowRPerMin))
                    .coerceIn(0.0, 1.0)
                return smbRatioCorrection + f * (smbRatioRise - smbRatioCorrection)
            }
    }

    data class Limits(
        /** Untergrenze, die die pessimistische Bahn nicht unterschreiten darf. */
        val guardFloorMgdl: Double = 70.0,
        /** Horizont, auf dem der Bedarf abgelesen wird (Index in points). */
        val releaseHorizonMin: Int = 30,
    )

    /**
     * EPISODENZUSTAND, nicht Verstaerkungsschalter.
     *
     * Er wird BERICHTET (Schirm, Export), steuert aber nichts mehr: die
     * Verstaerkung haengt an `r`, s. [State.effectiveSmbRatio]. Die
     * Unterscheidung bleibt trotzdem wertvoll — sie sagt, ob der Observer eine
     * Episode fuehrt, und das ist beim Auswerten etwas anderes als die Hoehe
     * des Anstiegs.
     *
     * `CANDIDATE` zaehlt schon zum Anstieg, obwohl er noch nicht bestaetigt
     * ist: die Schwelle ist ueberschritten, und genau die ersten Minuten sind
     * die, in denen ein FCL vorn sein muss. Der Guard bleibt in beiden Faellen
     * derselbe — es geht um die Verstaerkung, nicht um den Schutz.
     *
     * `TURN` liegt bewusst im Korrekturtopf: der Peak ist ueberschritten, das
     * meiste bereits gegebene Insulin ist noch gar nicht angekommen.
     */
    enum class Context { CORRECTION, RISE }

    fun contextOf(phase: Phase): Context = when (phase) {
        Phase.CANDIDATE, Phase.RISE_ACTIVE, Phase.CARRY -> Context.RISE
        Phase.REARMING, Phase.ARMED, Phase.TURN         -> Context.CORRECTION
    }

    enum class TbrAction { KEEP_CURRENT, CANCEL_TO_SCHEDULED, ZERO_TEMP, NO_NEW_POSITIVE }

    /**
     * Konkrete TBR-Antwort. AAPS setzt die TBR in JEDEM Zyklus VOR dem SMB — ein
     * APS ohne Rate und Dauer liesse das Basal ungeregelt.
     *
     * [rateUPerH] ist ABSOLUT (nicht Prozent), [durationMin] die angeforderte
     * Laufzeit. `null` heisst ausdruecklich "keine neue Anforderung, laufende
     * TBR unberuehrt lassen" — nicht "Rate 0".
     */
    data class TbrRequest(val rateUPerH: Double, val durationMin: Int)

    /** Uebersetzt die Kategorie in eine konkrete Anforderung.
     *
     *  Die Trennung ist Absicht: die Kategorie sagt, WAS gilt; erst hier kommen
     *  Profilbasal und Pumpengrenzen dazu. So bleibt die Entscheidung testbar,
     *  ohne dass jede Regel Pumpendetails kennen muss. */
    fun tbrRequest(
        action: TbrAction,
        scheduledBasalUPerH: Double,
        maxBasalUPerH: Double,
        durationMin: Int = 30,
    ): TbrRequest? = when (action) {
        // Zero-Temp ist die einzige Rueckholmoeglichkeit des Loops. Sie wird
        // ueber die volle Dauer gesetzt, nicht minutenweise erneuert.
        TbrAction.ZERO_TEMP           -> TbrRequest(0.0, durationMin)
        // Zurueck auf Profilbasal: als absolute Rate, damit eine laufende
        // Abweichung sicher endet statt nur auszulaufen.
        TbrAction.CANCEL_TO_SCHEDULED -> TbrRequest(scheduledBasalUPerH.coerceIn(0.0, maxBasalUPerH), durationMin)
        // Nichts anfordern: eine bestehende TBR laeuft weiter. NICHT Rate 0.
        TbrAction.KEEP_CURRENT        -> null
        // Kein neues POSITIVES Temp — eine bereits laufende Absenkung darf
        // bleiben, weil sie in die sichere Richtung wirkt.
        TbrAction.NO_NEW_POSITIVE     -> null
    }

    enum class Block {
        NONE, HEALTH_NOT_READY, SAFETY_HOLD, PUMP_BUSY, GUARD_FLOOR,
        NO_DEMAND, IOB_TH_REACHED, MAX_IOB_REACHED, BELOW_PUMP_INCREMENT, HORIZON_MISSING,
        /** Die Kandidatensuche hat den Vorschlag inhaltlich auf null gesetzt
         *  (Guard risse MIT der Dosis, Band, Headroom) - s. CandidateGate. */
        CANDIDATE,

        /**
         * Der Commitment-Ledger meldet einen Vertragsbruch (holdActuation):
         * der Regler weiss nicht mehr sicher, was draussen unterwegs ist -
         * keine neue Dosis, bis repariert oder ausdruecklich quittiert ist.
         * Gesetzt vom [LedgerHoldGate] NACH PrimeRelease.lift, damit weder
         * der Ratio-Pfad (bei Kernel-Ausfall) noch die Sofort-Freigabe am
         * Kandidaten-Reject vorbeikommen.
         */
        LEDGER_HOLD,

        /**
         * Der SCHWANZ traegt nichts mehr: was am Haftungshorizont noch an Bord
         * ist, schoepft das Budget der Bahn danach bereits aus.
         *
         * Heisst TAIL und nicht TAIL_FLOOR, weil v0.4 §268 den bindenden Grund
         * so nennt (NEAR_TERM_GUARD | TAIL | TARGET_BAND | CAP). Ein zweiter
         * Name fuer dieselbe Sache waere im Export nicht zuzuordnen.
         */
        TAIL,

        /**
         * Der Zyklus kam gar nicht bis zum Regler: kein Profil, kein Signal,
         * eine ungueltige Eingabe.
         *
         * Bewusst NICHT als `HEALTH_NOT_READY` getarnt — das waere eine Aussage
         * ueber den Observer, den es in diesem Fall noch gar nicht gesehen hat.
         * Wer spaeter auswertet, muss "Regler sagt nein" von "Regler lief nicht"
         * unterscheiden koennen.
         */
        NO_INPUT,
    }

    data class Decision(
        val smbU: Double,
        val tbr: TbrAction,
        val block: Block,
        /**
         * Der gerechnete Bedarf [U] - `null` heisst NICHT GERECHNET.
         *
         * Auditbefund P1-4 (16.08.2026): hier stand ein nicht-nullbarer
         * `Double`, und die Frueh-Ausstiege (NO_INPUT, none(), GUARD_FLOOR,
         * TAIL) uebergaben ein hartkodiertes `0.0`. Der Bedarf wird aber erst
         * NACH diesen Ausstiegen gerechnet - ein geblockter Zyklus war im
         * Trail damit von echtem Nullbedarf nicht unterscheidbar.
         *
         * Das ist keine Kosmetik: der Fehler hat im Audit ZWEI unabhaengige
         * Pruefer zu derselben falschen Aussage verleitet ("der Regler wies
         * null Bedarf aus"), und bei der Plateau-Analyse am selben Tag noch
         * einen dritten. Er verletzt die Projektregel "fehlende Daten heissen
         * UNKNOWN, niemals 0" an genau der Stelle, an der man sie am
         * dringendsten braucht - beim Deuten eines blockierten Zyklus.
         */
        val insulinReqU: Double?,
        val predAtReleaseMgdl: Double?,
        /**
         * Die SICHERHEITSBAHN, gegen die der Guard entschieden hat: prior-frei
         * und ueber Haupt- UND Bremsbahn minimiert (C7, Codex H2). Bewusst
         * NICHT die prior-gehobene Anzeigebahn - eine Zahl, die guenstiger
         * aussieht als das, was geprueft wurde, gehoert in keinen Export
         * (gleiche Regel wie in [CandidateSearch]).
         */
        val minLowerMgdl: Double?,
        val bindingLimit: String,
        /** Schwanzhaftung, falls bewertet. `null` = Guard aus oder nicht
         *  auswertbar. Traegt seinen eigenen Unvollstaendigkeitsvermerk. */
        val tail: TailLiability.Report? = null,
        /**
         * Was der Schwanz-Guard die Freigabe GEKOSTET hat [U] — die Differenz
         * zwischen der bindenden Grenze ohne und mit Schwanzterm.
         *
         * Reine Arithmetik INNERHALB des Zyklus, also messbar und kein
         * Counterfactual: beide Zahlen entstehen aus derselben Momentaufnahme.
         * Guard v0.3 §5 verlangt, dass der Onset-Verlust ueber H BEZIFFERT wird
         * — genau das ist diese Zahl.
         */
        val tailCostU: Double = 0.0,
        /** In welcher Lage entschieden wurde. Gehoert in den Export und auf den
         *  Schirm: dieselbe Zahl bedeutet in RISE etwas anderes als in
         *  CORRECTION. */
        val context: Context? = null,
        /**
         * Hat die schnelle Bahn den GUARD gesenkt? (S0)
         *
         * Getrennt vom Bedarf gefuehrt, weil es zwei verschiedene Dinge sind:
         * hier verschiebt die Bremse die Sicherheitsbahn und kann damit
         * BLOCKIEREN, unten kuerzt sie die ANFORDERUNG. Bis S0 war beides ein
         * Bit, und "gebremst" liess offen, welches von beiden passiert ist.
         */
        val restraintBoundGuard: Boolean = false,
        /** Hat die schnelle Bahn den BEDARF gesenkt? (S0) — s.
         *  [restraintBoundGuard]. */
        val restraintBoundDemand: Boolean = false,
        /**
         * Das Minimum der HAUPTbahn allein [mg/dl] (S0).
         *
         * [minLowerMgdl] ist ueber beide Bahnen minimiert; ohne diese Zahl ist
         * das AUSMASS der Bremswirkung nicht rekonstruierbar, nur ihr
         * Vorhandensein. `null` = keine Bahn.
         */
        val minLowerMainMgdl: Double? = null,
        /**
         * Wie weit die Sicherheitsbahn unter den Guard-Boden faellt [mg/dl],
         * sonst 0 (S0, Invariante I16).
         *
         * Der Guard ist ein reiner Schwellentest — eine Bahn bei 69 war im
         * Export von einer bei -382 nicht zu unterscheiden. Diese Zahl und
         * [timeToFloorMin] machen den Unterschied sichtbar. Sie ENTSCHEIDEN
         * nichts.
         */
        val floorDeficitMgdl: Double = 0.0,
        /** Nach wievielen Minuten die Sicherheitsbahn den Boden erstmals
         *  unterschreitet; `null` = nie im Fenster (S0). */
        val timeToFloorMin: Int? = null,
        /**
         * WANN das Minimum liegt, gegen das ENTSCHIEDEN wurde - ueber Haupt-
         * UND Bremsbahn (S0, I16).
         *
         * Gegenstueck zu [minLowerMgdl]. Der Zeitindex der EINZELNEN Bahn
         * (`PredictorResult.timeToMinSafetyLowerMin`) beschreibt einen anderen
         * Zeitpunkt, sobald das Minimum aus der Bremsbahn stammt - live am
         * 10.08.: minLower 71,17 bei Anker ~90,61 und Index 0. Beide Zahlen
         * waren richtig, nebeneinander ergaben sie eine unmoegliche Bahn.
         */
        val timeToMinCombinedMin: Int? = null,
        /**
         * ALLE Mengengrenzen dieses Zyklus, nicht nur die bindende (S0, K2).
         *
         * `bindingLimit` nennt genau eine, und bei Gleichstand entscheidet die
         * Listenreihenfolge. Auf diesem Geraet ist das kein Randfall: mit
         * `IobThPercent = 100` ist `iobThU == maxIobU` bitgenau, also sind
         * `iobThHeadroom` und `maxIobHeadroom` IMMER gleich, und genannt wird
         * immer der erste — iobTH. Wer spaeter fragt "war maxIOB mit aktiv?",
         * kann das ohne diese Liste nicht beantworten.
         *
         * Leer, wenn der Zyklus die Mengenrechnung nie erreicht hat.
         */
        val caps: List<Cap> = emptyList(),
        /**
         * ZU WELCHER STUFE die Kappenliste gehoert (S0).
         *
         * Die Entscheidung wird nach dem Regler noch mehrfach umgeschrieben -
         * CandidateGate, PrimeRelease, SubStep -, und dabei aenderten sich
         * `smbU` und `bindingLimit`, waehrend `caps` aus der Basisstufe
         * stehenblieb. Schlimmer noch: `CandidateSearch` fuehrt DIESELBEN
         * NAMEN mit ledgerkorrigierten Werten. Ein Datensatz konnte damit
         * eine gelieferte Menge zeigen, die GROESSER ist als die Kappe, die
         * er als bindend markiert - genau der Widerspruch, gegen den die
         * Liste ueberhaupt gebaut wurde.
         *
         * Leere Liste bei einer Stufe != [STAGE_BASE] heisst: die Grenzen
         * dieser Stufe sind nicht aufzaehlbar. Das ist eine Aussage, kein
         * Fehlen.
         */
        val capsStage: String = STAGE_BASE,
        /** Gewuenschte Menge VOR der Pumpenschritt-Rasterung [U] (Toni 09.08.):
         *  Eingang des Rest-Zaehlers gegen die Quantisierungs-Totzone. 0 heisst
         *  "es gab keinen Wunsch", nicht "der Wunsch war klein". */
        val desiredBeforeStepU: Double = 0.0,
        /**
         * Der Anteil dieser Menge, den eine AUSDRUECKLICHE manuelle
         * Autorisierung bei gemessenem Tief traegt [U] (Toni 11.08.).
         *
         * TYPISIERTE HERKUNFT, und das ist der ganze Punkt. Der Translator
         * muss eine markerfinanzierte Menge von jeder anderen unterscheiden
         * koennen, OHNE sie aus `bindingLimit` oder einem Grundtext zu raten -
         * Texte sind fuer Menschen, an ihnen darf keine Insulinabgabe haengen.
         *
         * 0 heisst: nichts an dieser Menge ist manuell autorisiert. Dann nullt
         * jeder Mengen-Block sie vollstaendig, wie bisher.
         */
        val markerAuthorizedU: Double = 0.0,
    ) {

        /**
         * Hat die schnelle Bahn ueberhaupt gebremst?
         *
         * ABGELEITET und nicht mitgefuehrt: so kann das Bit nicht behaupten,
         * was die beiden Ursachen nicht hergeben. Fuer alle bisherigen Leser
         * bedeutungsgleich zum frueheren Feld.
         */
        val restraintBound: Boolean get() = restraintBoundGuard || restraintBoundDemand
    }

    /**
     * Eine Mengengrenze mit ihrem Wert (S0-Telemetrie, K2).
     *
     * [active] heisst "fuer die Dosis von der bindenden Grenze nicht zu
     * unterscheiden", und das ist GLEICHE TICKZAHL - NICHT "innerhalb eines
     * Pumpenschritts". Ein ganzer Schritt Abstand ist bereits eine andere
     * lieferbare Menge. Die Regel steht in [capsOf] und nur dort; dieser
     * Absatz hat sie einmal falsch wiederholt und die Korrektur nicht
     * mitbekommen.
     *
     * KEINE Einstufung in hart/weich hier. Welche Grenze ein spaeteres
     * Privileg erweitern darf, ist eine Regel des Privilegs — der Regler
     * liefert die Tatsachen.
     */
    data class Cap(val name: String, val valueU: Double, val active: Boolean)

    /**
     * Die fail-closed Entscheidung fuer einen Zyklus, der den Regler nie
     * erreicht hat.
     *
     * Sie steht hier und nicht im Adapter, damit es genau EINE Form von "FUSE
     * hat nichts entschieden" gibt: keine Menge, nichts Positives mehr, und der
     * Grund im Klartext. `NO_NEW_POSITIVE` und nicht `ZERO_TEMP`: eine fehlende
     * Eingabe ist kein Sicherheitsbefund. Wer bei jedem Signalaussetzer 30
     * Minuten Basal stoppt, hat eine eigene Fehldosis gebaut, nur mit
     * umgekehrtem Vorzeichen.
     */
    fun noInput(reason: String): Decision =
        Decision(0.0, TbrAction.NO_NEW_POSITIVE, Block.NO_INPUT, null, null, null, reason)

    fun decide(
        state: State,
        prediction: PredictorResult?,
        limits: Limits = Limits(),
        /** `null` = Schwanz-Guard aus. Vierter Parameter mit Default, damit
         *  bestehende Aufrufe unveraendert bleiben. */
        tail: TailLiability.Report? = null,
        /**
         * ZWEITE Bahn aus einer SCHNELLEN Rate — sie darf ausschliesslich
         * BREMSEN.
         *
         * Warum eine reine Bremse und keine Ersetzung: `rSigned` ist der Median
         * ueber 18 Minuten und haengt an jedem Wendepunkt rund sechs Minuten
         * nach. Am 06.08. gemessen, in BEIDE Richtungen:
         *
         *   Onset 13:08   roh +1,00 mg/dl/min   r -0,60   -> zu spaet dosiert
         *   Wende 14:05   roh -1,00             r +5,49   -> zu lange dosiert
         *
         * Die Wende kostete 2,20 U in 14 SMBs, abgegeben bei bis zu
         * -3,7 mg/dl/min FALLENDER Glukose.
         *
         * WARUM NUR BREMSEN, und nicht die naheliegende Asymmetrie: der erste
         * Entwurf lautete "Guard nimmt das MAXIMUM beider Bahnen, damit die
         * schnelle nur oeffnen kann". Das ist im ABSTIEG falsch — dort ist die
         * schnelle Bahn die alarmierende, und `max` wirft sie weg. Welche Bahn
         * "die sichere" ist, wechselt mit der Richtung; eine feste Asymmetrie
         * kann das nicht abbilden.
         *
         * Das MINIMUM beider Bahnen ist dagegen richtungsunabhaengig richtig:
         * ein Guard ist pessimistisch, und die pessimistischere zweier
         * gleichzeitiger Schaetzungen zu nehmen ist genau das. Es kann keine
         * Dosis erhoehen und keinen heute vorhandenen Block entfernen — der
         * Eingriff ist damit beweisbar einseitig.
         *
         * WAS ES NICHT BEHEBT: den Onset. Dort ist die langsame Bahn die
         * pessimistischere, gewinnt also, und FUSE bleibt zu zaghaft. Das ist
         * ein Problem des ANTRIEBS, nicht der Kombination, und wird getrennt
         * behandelt.
         */
        restraint: PredictorResult? = null,
        /**
         * Fliesst in diesem Zyklus Kredit aus dem versiegelten Evidenzbestand?
         *
         * Entwaffnet die Totbaender (s. [NightWindow.effectiveDeadbandMgdl]).
         *
         * OHNE DEFAULT, nach derselben Regel wie dort - und aus gemessenem
         * Anlass: die erste Fassung trug `= false`, der einzige
         * Produktionsaufrufer vergass den Anschluss, und der Default hielt
         * die Totbaender kompilierfehlerfrei still scharf (Abschluss-Audit
         * 15.08.: 81 Kreditzyklen im 2-Tage-Trail geblockt, waehrend die
         * Commit-Botschaft die Verdrahtung behauptete). Ein Kompilierfehler
         * je Aufrufstelle ist billiger als genau dieser stille Ausfall.
         */
        evidenceCreditActive: Boolean,
        /** Rest der Onset-Haftungshuelle [U]. Nicht-null NUR, wenn der
         *  OnsetChannel in diesem Zyklus die Mittelbahn gehoben hat - dann
         *  kappt er die Menge, die auf seiner eigenen Hebung beruht. */
        onsetCapU: Double? = null,
    ): Decision {
        // Der Kontext gehoert an JEDEN Rueckgabepfad, nicht nur an den
        // Erfolgsfall. Gerade beim Blockieren ist die Frage "war das die
        // Mahlzeiten- oder die Korrekturlage" die erste, die man stellt.
        val ctx = contextOf(state.phase)

        fun none(block: Block, tbr: TbrAction = TbrAction.NO_NEW_POSITIVE) =
            Decision(0.0, tbr, block, null, null, null, block.name, context = ctx)

        // Reihenfolge ist Absicht: Zustand vor Zahlen. Eine Dosis aus einer
        // Trajektorie, die gar nicht gelten darf, waere der teuerste Fehler.
        if (state.health != Health.READY) return none(Block.HEALTH_NOT_READY)
        if (state.safetyHold) return none(Block.SAFETY_HOLD, TbrAction.ZERO_TEMP)
        if (state.pumpBusy) return none(Block.PUMP_BUSY, TbrAction.KEEP_CURRENT)
        if (prediction == null) return none(Block.HORIZON_MISSING)

        val release = prediction.points.firstOrNull { it.offsetMin == limits.releaseHorizonMin }
            ?: return none(Block.HORIZON_MISSING)

        // Die pessimistischere zweier gleichzeitiger Schaetzungen. `restraint`
        // kann nur senken, nie anheben.
        val restraintRelease = restraint?.points?.firstOrNull { it.offsetMin == limits.releaseHorizonMin }
        //
        // C7 / K2 Punkt 8 (Codex-Adjudication H2/H3): die SCHUTZ-Sicht ist die
        // PRIOR-FREIE, ueber ALLE Bahnen minimierte - dieselbe Zahl, die
        // CandidateSearch, PrimeRelease und die finale Wirkungspruefung
        // benutzen (`minSafetyLowerOf`).
        //
        // Hier stand die PRIOR-GEHOBENE Bahn. Eine Dosis konnte sie zwar nicht
        // mehr autorisieren - das finale Zeugnis ist prior-frei -, aber sie
        // konnte einen SCHUETZENDEN ZERO_TEMP UNTERDRUECKEN: prior-frei war
        // der Fall GUARD_FLOOR -> ZERO_TEMP, prior-gehoben lief er als
        // CANDIDATE/KEEP_CURRENT aus, und die Bremswirkung des Basalstopps
        // fiel ersatzlos weg. Ein Marker-Prior ist BEDARFS-Evidenz (H2) und
        // darf Sicherheit nie vortaeuschen.
        //
        // Der BEDARF (releaseMean -> insulinReq) bleibt bewusst auf der
        // Mittelbahn: dass der Prior Bedarf erzeugen darf, ist gewollt.
        //
        // Einseitig: die prior-freie Kante liegt nie UEBER der gehobenen, der
        // Guard kann dadurch nur haeufiger greifen, nie seltener.
        val minLower = minSafetyLowerOf(prediction, restraint)
        val releaseMean = minOf(release.meanBg, restraintRelease?.meanBg ?: Double.MAX_VALUE)
        // S0: die beiden Ursachen GETRENNT. "Gebremst" war bisher ein Bit ueber
        // zwei verschiedene Wirkungen - die Bremse kann die Sicherheitsbahn
        // senken (und damit blockieren) ODER den Bedarf kuerzen. Wer eine
        // Zurueckhaltung nachvollziehen will, braucht die Unterscheidung.
        val restraintGuard = restraint != null && minLower < prediction.minSafetyLowerBg
        val restraintDemand = restraint != null && releaseMean < release.meanBg

        // S0 (I16): WIE TIEF und WIE BALD unter dem Boden. Ueber DIESELBEN
        // Bahnen wie der Guard - der Boden kommt aus den Limits, die Bahnen aus
        // dem Zyklus, und die Zahlen entscheiden nichts.
        val floorDeficit = TrajectoryQuery.floorDeficitOf(limits.guardFloorMgdl, prediction, restraint)
        val timeToFloor = TrajectoryQuery.timeToFloorOf(limits.guardFloorMgdl, prediction, restraint)
        val timeToMinCombined = TrajectoryQuery.timeToMinSafetyLowerOf(prediction, restraint)

        // Alle Rueckgaben dieses Laufs teilen dieselbe S0-Telemetrie. Sie EINMAL
        // anzuhaengen ist nicht Bequemlichkeit: acht Rueckgabestellen, die je
        // fuenf Felder von Hand mitfuehren, sind acht Gelegenheiten, eines zu
        // vergessen - und ein fehlendes Telemetriefeld sieht im Export aus wie
        // ein Messwert (0 bzw. "nicht gebremst"), nicht wie eine Luecke.
        fun Decision.tele(caps: List<Cap> = emptyList()) = copy(
            restraintBoundGuard = restraintGuard,
            restraintBoundDemand = restraintDemand,
            minLowerMainMgdl = prediction.minSafetyLowerBg,
            floorDeficitMgdl = floorDeficit,
            timeToFloorMin = timeToFloor,
            timeToMinCombinedMin = timeToMinCombined,
            caps = caps,
        )

        // Guard: bewertet wird das MINIMUM der pessimistischen Bahn, nicht ihr
        // Endwert — eine Bahn kann harmlos enden und zwischendurch tief gehen.
        if (minLower < limits.guardFloorMgdl) {
            return Decision(
                0.0, TbrAction.ZERO_TEMP, Block.GUARD_FLOOR, null,
                releaseMean, minLower, "guardFloor=${limits.guardFloorMgdl}", context = ctx,
            ).tele()
        }

        // SCHWANZ-GUARD. Er sitzt NACH dem Nahzonen-Guard und VOR der
        // Bedarfsrechnung: was der Schwanz nicht mehr traegt, ist kein
        // Mengenproblem, sondern eine Haftungsgrenze.
        //
        // Die Kategorie ist NO_NEW_POSITIVE und ausdruecklich NICHT ZERO_TEMP.
        // Zwei Gruende, beide aus der Sache: ein Zero-Temp kann die bereits
        // gelieferte Wirkung, um die es hier geht, gar nicht zurueckholen — und
        // ein blindes Zero-Temp bei sicherer Nahbahn waere eine eigene
        // Fehldosis mit umgekehrtem Vorzeichen (s. NO_DEMAND weiter unten).
        // Ein Schwanzbefund ist kein Sicherheitsbefund der Nahzone.
        if (tail != null && tail.usable && tail.headroomU <= 0.0) {
            return Decision(
                0.0, TbrAction.NO_NEW_POSITIVE, Block.TAIL, null,
                releaseMean, minLower, "tailHeadroom=${tail.headroomU}", tail, context = ctx,
            ).tele()
        }

        // Kein zweites "- iob": die IOB-Wirkung ist in predBG bereits enthalten.
        val insulinReq = (releaseMean - state.targetMgdl) / state.isfMgdlPerU

        // REBOUND-TOTBAND (v2): Nach einem Tief ist die Erholung bis leicht
        // ueber das Ziel das ZIEL der Traubenzucker-Aktion, keine Stoerung.
        // Der Anker (nicht die Bahn) entscheidet - die Bahn ist im Rebound
        // vom aufgeblaehten r verzerrt, genau deshalb existiert das Fenster.
        // NACHT-TOTBAND (Toni 09.08.) laeuft ueber DENSELBEN Riegel: der
        // groessere der beiden Gruende gilt, zwei Schutzgruende duerfen sich
        // nie gegenseitig aufweichen. Gemessener Anlass: 05:25-06:24 am
        // 09.08. - 1,10 U bei BG 89-116, r um null, Bedarf allein aus
        // negativem Basal-IOB.
        val deadbandMgdl = NightWindow.effectiveDeadbandMgdl(
            reboundWindow = state.reboundWindow,
            reboundDeadbandMgdl = state.reboundDeadbandMgdl,
            isNight = state.nightWindow,
            nightDeadbandMgdl = state.nightDeadbandMgdl,
            markerBoost = state.markerBoost,
            evidenceCreditActive = evidenceCreditActive,
        )
        if (deadbandMgdl > 0.0 && prediction.bgAtAnchor < state.targetMgdl + deadbandMgdl) {
            return Decision(
                0.0, TbrAction.NO_NEW_POSITIVE, Block.NO_DEMAND, insulinReq,
                releaseMean, minLower,
                if (state.reboundWindow && deadbandMgdl == state.reboundDeadbandMgdl) "reboundDeadband" else "nightDeadband",
                tail, context = ctx,
            ).tele()
        }

        if (insulinReq <= 0.0) {
            // NO_NEW_POSITIVE und NICHT ZERO_TEMP. Die erste Fassung stand hier
            // auf Zero-Temp und widersprach damit dem Vertrag, den [TbrPolicy]
            // selbst aufschreibt: "Kein zusaetzlicher Bedarf heisst nicht, dass
            // das Profilbasal 30 Minuten gestoppt gehoert. Ein blindes
            // Zero-Temp bei sicherer Bahn ist eine eigene Fehldosis, nur mit
            // umgekehrtem Vorzeichen." Die gefaehrliche Lage faengt der Guard
            // oben ab, und der faengt sie ueber das MINIMUM der Bahn, nicht nur
            // ueber den Freigabepunkt.
            //
            // Was dabei mit verschwindet, gehoert benannt: das alte Zero-Temp
            // hat unbeabsichtigt das FEHLENDE Unsicherheitsband kompensiert
            // (lower == mean, Alpha 1). Der Guard ist damit heute genau so
            // empfindlich wie die Mittelbahn — nicht empfindlicher. Das ist ein
            // Argument fuer das Band, nicht fuer ein pauschales Basal-Aus.
            return Decision(
                0.0, TbrAction.NO_NEW_POSITIVE, Block.NO_DEMAND, insulinReq,
                releaseMean, minLower, "insulinReq<=0", context = ctx,
            ).tele()
        }

        // Tonis IOB-Referenz-Regel (08.08. abends): PROGNOSE rechnet mit net
        // (die Bahn muss die Basal-Referenzbuchung kennen), DOSIER-Grenzen
        // rechnen mit capIob = max(net, bolus) - stark negatives Basal-IOB
        // ist keine physische Substanz und darf keinen SMB-Spielraum
        // erzeugen. iobTH lief schon immer auf capIob; maxIOB zieht nach.
        val maxIobHeadroom = state.maxIobU - state.capIobU
        val fastHeadroom = state.iobThU - state.capIobU

        // S0 (K2): DIE BEIDEN IOB-GRENZEN WERDEN ZUSAMMEN BERICHTET, auch wenn
        // nur eine den Ausschlag gibt. Auf diesem Geraet ist
        // `iobThU = percent/100 * maxIobU` mit percent = 100, also sind beide
        // Spielraeume BITGENAU gleich - "welche hat gebunden" ist dann keine
        // Messung mehr, sondern die Reihenfolge einer Liste. Die
        // Reihenfolge der beiden Tests unten ist trotzdem richtig herum
        // (maxIOB zuerst) und bleibt unveraendert.
        val iobCaps = capsOf(
            state.pumpIncrementU,
            "maxIobHeadroom" to maxIobHeadroom,
            "iobThHeadroom" to fastHeadroom,
        )

        if (maxIobHeadroom <= 0.0) {
            return Decision(
                0.0, TbrAction.NO_NEW_POSITIVE, Block.MAX_IOB_REACHED, insulinReq,
                releaseMean, minLower, "maxIOB=${state.maxIobU}", context = ctx,
            ).tele(iobCaps)
        }

        // iobTH ist die Grenze zwischen schnellem und langsamem Kanal — NICHT
        // der Gesamtdeckel. Oberhalb laeuft nur noch Basal weiter.
        if (fastHeadroom <= 0.0) {
            return Decision(
                0.0, TbrAction.NO_NEW_POSITIVE, Block.IOB_TH_REACHED, insulinReq,
                releaseMean, minLower, "iobTH=${state.iobThU}", context = ctx,
            ).tele(iobCaps)
        }

        val baseCandidates = listOf(
            "smbRatio" to insulinReq * state.effectiveSmbRatio,
            "iobThHeadroom" to fastHeadroom,
            "maxIobHeadroom" to maxIobHeadroom,
            "maxSmb" to state.maxSmbU,
        )
        // Die Onset-Huelle gehoert zu den Basis-Kandidaten: sie ist die Grenze
        // des Kanals, der die Bahn gehoben hat - nicht Teil der Schwanzkosten.
        val baseWithOnset =
            if (onsetCapU != null) baseCandidates + ("onsetEnvelope" to onsetCapU)
            else baseCandidates
        val withoutTail = baseWithOnset.minOf { it.second }
        val candidates =
            if (tail != null && tail.usable) baseWithOnset + ("tailHeadroom" to tail.headroomU)
            else baseWithOnset
        val binding = candidates.minByOrNull { it.second }!!
        val raw = binding.second
        val tailCost = (withoutTail - raw).coerceAtLeast(0.0)
        // S0 (K2): die VOLLSTAENDIGE Kappenliste, nicht nur die bindende.
        // `minByOrNull` nennt bei Gleichstand die erste - und Gleichstand ist
        // hier der Normalfall, nicht der Randfall.
        val capList = capsOf(state.pumpIncrementU, *candidates.toTypedArray())

        // AUSSCHLIESSLICH abwaerts runden: eine Freigabe darf durch Rundung nie
        // groesser werden. Unter dem Pumpeninkrement gibt es keinen SMB — es
        // wird NICHT auf die Mindestmenge aufgerundet.
        //
        // DAS EPSILON IST TRAGEND, nicht Kosmetik. Ohne es verliert `floor` an
        // exakten Vielfachen einen GANZEN Pumpenschritt: 0,15 U sind als Double
        // 0,1499999999999999944…, geteilt durch 0,05 ergibt das 2,9999999999…
        // und floor macht daraus 2 — also 0,10 statt 0,15. Bei Dosen zwischen
        // 0,05 und 0,30 U sind das 17 bis 100 % der Menge, systematisch nach
        // unten. Gefunden hat es der Kontext-Test: erwartet 0,60, geliefert
        // 0,55. `CandidateSearch` hatte dieselbe Stelle von Anfang an mit
        // Epsilon.
        // UEBER `ticksOf`, nicht als zweite Abschrift. Dessen KDoc begruendet,
        // dass eine zweite Fassung dieser Rundung der eigentliche Hazard ist -
        // und liess die zweite Fassung dann hier stehen. Jetzt gilt "die Kappen
        // rastern wie die Dosis" per Konstruktion statt per Augenschein.
        val deliverable = ticksOf(raw, state.pumpIncrementU) * state.pumpIncrementU
        if (deliverable < state.pumpIncrementU) {
            return Decision(
                0.0, TbrAction.KEEP_CURRENT, Block.BELOW_PUMP_INCREMENT, insulinReq,
                releaseMean, minLower, binding.first, tail, tailCost, ctx,
                desiredBeforeStepU = raw,
            ).tele(capList)
        }

        return Decision(
            smbU = min(deliverable, raw),
            desiredBeforeStepU = raw,
            tbr = TbrAction.KEEP_CURRENT,
            block = Block.NONE,
            insulinReqU = insulinReq,
            predAtReleaseMgdl = releaseMean,
            minLowerMgdl = minLower,
            bindingLimit = binding.first,
            tail = tail,
            tailCostU = tailCost,
            context = ctx,
        ).tele(capList)
    }

    /**
     * Wieviele ganze Pumpenschritte in [u] passen - DIESELBE Rechnung, mit der
     * die Freigabe unten gerastert wird (`floor(raw / inc + TICK_EPS)`).
     *
     * Sie steht als eigene Funktion da, weil eine zweite Fassung derselben
     * Rundung genau an den exakten Vielfachen auseinanderliefe: 0,15 U sind als
     * Double 0,1499999999999999944…, ohne Epsilon wird daraus ein Schritt
     * weniger. Wer die Kappen anders rastert als die Dosis, stuft die Grenzen
     * nach einer Arithmetik ein, die nicht entschieden hat.
     */
    internal fun ticksOf(u: Double, pumpIncrementU: Double): Long {
        if (!u.isFinite() || !pumpIncrementU.isFinite() || pumpIncrementU <= 0.0) return Long.MIN_VALUE
        return floor(u / pumpIncrementU + TICK_EPS).toLong()
    }

    /**
     * Baut die Kappenliste und markiert, welche Grenzen die Menge
     * TATSAECHLICH bestimmt haben (S0, K2).
     *
     * AKTIV HEISST: GLEICHE TICKZAHL wie die kleinste Kappe - nicht "innerhalb
     * eines Pumpenschritts".
     *
     * Die erste Fassung prueft `v <= min + inc` und hat damit ihre eigene
     * Begruendung verfehlt: bei 1,00 und 1,05 U mit 0,05er Schritt sind das
     * 20 gegen 21 Ticks, also VERSCHIEDENE lieferbare Mengen - die zweite
     * Kappe hat nichts begrenzt und wurde trotzdem als mitbindend gefuehrt.
     * Gedacht war "ununterscheidbar fuer die Dosis"; das ist genau die
     * Tickgleichheit und sonst nichts.
     *
     * Ohne brauchbaren Pumpenschritt bleibt nur die exakte Gleichheit - eine
     * ehrliche Entartung statt einer erfundenen Toleranz.
     */
    internal fun capsOf(pumpIncrementU: Double, vararg caps: Pair<String, Double>): List<Cap> {
        val min = caps.minOfOrNull { it.second } ?: return emptyList()
        // NAN-RIEGEL. Er ist noetig, weil die beiden Reduktionen NICHT
        // dieselbe sind: `minOfOrNull` faltet mit Math.min und PROPAGIERT
        // NaN, waehrend der Dosispfad mit `minByOrNull` ueber die
        // Totalordnung vergleicht und NaN ans ENDE sortiert. Mit einer
        // NaN-Kappe waere die Markierung exakt INVERTIERT - die Kappe, die
        // entschieden hat, stuende inaktiv da und die unbrauchbare aktiv.
        // Lieber gar keine Markierung als eine falsche.
        if (!min.isFinite()) return caps.map { Cap(it.first, it.second, false) }
        val usable = pumpIncrementU.isFinite() && pumpIncrementU > 0.0
        val minTicks = ticksOf(min, pumpIncrementU)
        return caps.map { (name, v) ->
            val active =
                if (!v.isFinite()) false
                // Bei erschoepftem Spielraum trennt die Tickzahl Groessen,
                // die alle dasselbe bedeuten: nichts ist lieferbar.
                else if (min <= 0.0) v <= 0.0
                else if (usable) ticksOf(v, pumpIncrementU) == minTicks
                else v == min
            Cap(name, v, active)
        }
    }

    /** Stufe, aus der eine Kappenliste stammt - s. [Decision.capsStage]. */
    const val STAGE_BASE = "base"
    const val STAGE_CANDIDATE = "candidate"
    const val STAGE_PRIME = "prime"
    const val STAGE_SUBSTEP = "subStep"
}
