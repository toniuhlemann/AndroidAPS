package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.PredictorResult
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
    ) {
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
        val insulinReqU: Double,
        val predAtReleaseMgdl: Double?,
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
        /** Hat die SCHNELLE Bahn die Entscheidung gebremst? Gehoert in den
         *  Export: sonst ist im Nachhinein nicht unterscheidbar, ob eine
         *  Zurueckhaltung aus dem Antrieb oder aus der Bremse kam. */
        val restraintBound: Boolean = false,
    )

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
        Decision(0.0, TbrAction.NO_NEW_POSITIVE, Block.NO_INPUT, 0.0, null, null, reason)

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
            Decision(0.0, tbr, block, 0.0, null, null, block.name, context = ctx)

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
        val minLower = minOf(prediction.minLowerBg, restraint?.minLowerBg ?: Double.MAX_VALUE)
        val releaseMean = minOf(release.meanBg, restraintRelease?.meanBg ?: Double.MAX_VALUE)
        val restraintBound = restraint != null &&
            (minLower < prediction.minLowerBg || releaseMean < release.meanBg)

        // Guard: bewertet wird das MINIMUM der pessimistischen Bahn, nicht ihr
        // Endwert — eine Bahn kann harmlos enden und zwischendurch tief gehen.
        if (minLower < limits.guardFloorMgdl) {
            return Decision(
                0.0, TbrAction.ZERO_TEMP, Block.GUARD_FLOOR, 0.0,
                releaseMean, minLower, "guardFloor=${limits.guardFloorMgdl}", context = ctx, restraintBound = restraintBound,
            )
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
                0.0, TbrAction.NO_NEW_POSITIVE, Block.TAIL, 0.0,
                releaseMean, minLower, "tailHeadroom=${tail.headroomU}", tail, context = ctx, restraintBound = restraintBound,
            )
        }

        // Kein zweites "- iob": die IOB-Wirkung ist in predBG bereits enthalten.
        val insulinReq = (releaseMean - state.targetMgdl) / state.isfMgdlPerU
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
                releaseMean, minLower, "insulinReq<=0", context = ctx, restraintBound = restraintBound,
            )
        }

        val maxIobHeadroom = state.maxIobU - state.netIobU
        if (maxIobHeadroom <= 0.0) {
            return Decision(
                0.0, TbrAction.NO_NEW_POSITIVE, Block.MAX_IOB_REACHED, insulinReq,
                releaseMean, minLower, "maxIOB=${state.maxIobU}", context = ctx, restraintBound = restraintBound,
            )
        }

        // iobTH ist die Grenze zwischen schnellem und langsamem Kanal — NICHT
        // der Gesamtdeckel. Oberhalb laeuft nur noch Basal weiter.
        val fastHeadroom = state.iobThU - state.capIobU
        if (fastHeadroom <= 0.0) {
            return Decision(
                0.0, TbrAction.NO_NEW_POSITIVE, Block.IOB_TH_REACHED, insulinReq,
                releaseMean, minLower, "iobTH=${state.iobThU}", context = ctx, restraintBound = restraintBound,
            )
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
        val deliverable = floor(raw / state.pumpIncrementU + TICK_EPS) * state.pumpIncrementU
        if (deliverable < state.pumpIncrementU) {
            return Decision(
                0.0, TbrAction.KEEP_CURRENT, Block.BELOW_PUMP_INCREMENT, insulinReq,
                releaseMean, minLower, binding.first, tail, tailCost, ctx, restraintBound,
            )
        }

        return Decision(
            smbU = min(deliverable, raw),
            tbr = TbrAction.KEEP_CURRENT,
            block = Block.NONE,
            insulinReqU = insulinReq,
            predAtReleaseMgdl = releaseMean,
            minLowerMgdl = minLower,
            bindingLimit = binding.first,
            tail = tail,
            tailCostU = tailCost,
            context = ctx,
            restraintBound = restraintBound,
        )
    }
}
