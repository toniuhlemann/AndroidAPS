package app.aaps.fuse.core.predictor

import kotlin.math.abs

/**
 * Der K2-P-Trajektorienkern (Spec v0.1 + v0.1.1 + v0.1.2).
 *
 * Rechnet ZWEI Bahnen (mean und lower) ueber einen Horizont und liefert deren
 * Minimum. Ein Endwert allein genuegt nicht: die Bahn kann ein Tief durchlaufen
 * und danach wieder steigen — genau der Fall, der spaeter eine Dosis verbietet.
 *
 * SEIT C2 (Codex-Adjudication, K2 Punkt 8) eine DRITTE Bahn: die PRIOR-FREIE
 * Untergrenze. Sie laeuft in DERSELBEN Schleife wie die anderen beiden und
 * nicht in einem zweiten `predict()`-Aufruf. Zwei Gruende, und der zweite ist
 * der tragende:
 *
 *  1. Sie teilt Punkt fuer Punkt `bgiRate`, ISF-Slot und Zerfallsfaktor mit der
 *     gehobenen Bahn — die einzige Differenz ist der Antriebsterm. Ein zweiter
 *     Aufruf muesste all das erneut interpolieren und koennte auseinanderlaufen.
 *  2. Ein zweiter Aufruf kann UNABHAENGIG ABGELEHNT werden. Dann haette der
 *     Zyklus eine gueltige Dosierbahn und KEIN Sicherheitszeugnis — genau die
 *     Sorte Zustand, aus der ein fail-open-Pfad entsteht. Ein zweiter
 *     Akkumulator in derselben Schleife kann das strukturell nicht.
 *
 * Kosten: zwei zusaetzliche Additionen je Minute, kein zusaetzlicher
 * Datenbank- oder Interpolationszugriff.
 *
 * SEIT C3 (Codex-Adjudication, K4 Punkt 17) kann die Bahn zusaetzlich
 * SYNTHETISCHE DOSEN tragen: die publizierte, im IOB noch nicht sichtbare
 * Transportmenge ([PredictorInput.pending]). Sie wirkt auf ALLE DREI Bahnen -
 * senken ist in beide Richtungen konservativ (der Guard sperrt eher, der Bedarf
 * sinkt) - und verschwindet atomar mit derselben Zahl, mit der der Ledger sie
 * freigibt.
 *
 * SEIT C3-01 (Codex Fix-Pass-5-Closure G.2) ist das eine LISTE: jeder offene
 * Posten hat eigene Menge und eigenen Lieferanker. Die Summe der Wirkungen
 * mehrerer Anker ist NICHT die Wirkung der Summe an einem Anker - und der
 * aelteste Anker war ausgerechnet fuer die Resthaftung am Horizont die
 * unterschaetzende Wahl.
 *
 * Die oeffentliche API liefert bewusst KEINE Dosis, keine Rate und keine Dauer.
 */
object TrajectoryCore {

    private const val STEP_MS = 60_000L

    /** Toleranz der Rasterpruefung (R71-A/Q8). */
    const val GRID_TOLERANCE_MS = 1_000L

    fun predict(input: PredictorInput): PredictorOutcome {
        val t = input.trajectory
        val anchor = input.predictionAnchorTs

        // R74-F5: fail-closed BEVOR irgendetwas gerechnet wird. Ein
        // Forschungs-Predictor darf bei ungueltiger Eingabe niemals werfen und
        // niemals ein formal gueltiges Ok(NaN) liefern — beides waere schlimmer
        // als eine Ablehnung, weil es wie ein Ergebnis aussieht.
        if (t.points.isEmpty())
            return PredictorOutcome.Rejected(PredictorReason.ARRAY_TOO_SHORT, "points empty")
        if (input.horizonMin <= 0)
            return PredictorOutcome.Rejected(PredictorReason.ARRAY_TOO_SHORT, "horizon=${input.horizonMin}")
        if (!input.bgAtAnchor.isFinite())
            return PredictorOutcome.Rejected(PredictorReason.NON_FINITE_INPUT, "bgAtAnchor")

        // --- Zeitachsen-Gates -------------------------------------------------
        // BG/Q1 haengt an sourceTs, das IOB-Array an seinem eigenen now. Beides
        // ist nicht identisch; ohne Gate koennte schon der erste Schritt vor dem
        // Arrayanfang liegen. Es wird NICHT rueckwaerts extrapoliert.
        val firstQueryTs = anchor + STEP_MS
        val lastQueryTs = anchor + input.horizonMin * STEP_MS
        if (firstQueryTs < t.firstTs)
            return PredictorOutcome.Rejected(
                PredictorReason.SKEW_BEFORE_ARRAY_START,
                "firstQuery=$firstQueryTs < arrayFirst=${t.firstTs}",
            )
        if (lastQueryTs > t.lastTs)
            return PredictorOutcome.Rejected(
                PredictorReason.ARRAY_TOO_SHORT,
                "horizon reaches $lastQueryTs, array ends ${t.lastTs} (spanMin=${t.spanMin})",
            )

        // --- Eingaben pruefen -------------------------------------------------
        var prevTs = Long.MIN_VALUE
        var gridStepMs = 0L
        for (p in t.points) {
            if (!p.activity.isFinite() || !p.iob.isFinite() || !p.basalIob.isFinite())
                return PredictorOutcome.Rejected(PredictorReason.NON_FINITE_INPUT, "point ${p.timeMs}")
            if (p.timeMs <= prevTs)
                return PredictorOutcome.Rejected(PredictorReason.NON_MONOTONIC_TIMESTAMPS, "at ${p.timeMs}")
            if (prevTs != Long.MIN_VALUE) {
                val step = p.timeMs - prevTs
                if (gridStepMs == 0L) gridStepMs = step
                else if (Math.abs(step - gridStepMs) > GRID_TOLERANCE_MS)
                    return PredictorOutcome.Rejected(
                        PredictorReason.GRID_MISMATCH,
                        "step ${step}ms != ${gridStepMs}ms at ${p.timeMs}",
                    )
            }
            prevTs = p.timeMs
            // NUR der Betrag wird geprueft: negative Aktivitaet ist nach einer
            // Zero-/Low-TBR gueltige Physik (netBasalRate = rate - basalRate) und
            // erzeugt ueber bgi = -activity*isf korrekt positives BGI.
            input.bounds.maxAbsActivityUPerMin?.let { lim ->
                if (abs(p.activity) > lim)
                    return PredictorOutcome.Rejected(PredictorReason.ACTIVITY_OUT_OF_BOUNDS, "|${p.activity}| > $lim")
            }
        }
        // C3: die TRANSPORTMENGE als synthetische Dosis. Gepruefte Eingabe VOR
        // der Integration - eine Menge, deren Modell das Fenster nicht deckt,
        // wuerde sonst ab dem Traegerende still mit 0 weiterrechnen, und genau
        // diese stille Null waere die optimistische Lesart (Codex Abschnitt 10).
        //
        // C3-01 (Codex Fix-Pass-5-Closure G.2): JEDER POSTEN WIRD EINZELN
        // GEPRUEFT. Ein einziger zu kurzer Traeger kippt die ganze Bahn - eine
        // stille Teil-Null waere dieselbe optimistische Lesart, nur kleiner.
        for (p in input.pending) {
            if (!p.amountU.isFinite() || p.amountU < 0.0)
                return PredictorOutcome.Rejected(PredictorReason.NON_FINITE_INPUT, "pending amount=${p.amountU}")
            if (p.amountU > 0.0 && !p.covers(lastQueryTs))
                return PredictorOutcome.Rejected(
                    PredictorReason.PENDING_MODEL_TOO_SHORT,
                    "pending delivery=${p.deliveryTs} does not cover $lastQueryTs",
                )
        }
        // Menge 0 ist KEINE Dosis: dann rechnet die Bahn bitgleich wie ohne
        // Transportmenge weiter. Genau daran haengt die Doppelzaehlungs-Sperre.
        val pendingDoses = input.pending.filter { it.amountU > 0.0 }

        input.bounds.maxAbsDriveMgdlPerMin?.let { lim ->
            // Die prior-freie Untergrenze zaehlt mit: sie ist per Vertrag <= lower
            // und kann damit betragsmaessig GROESSER sein als beide anderen.
            if (abs(input.drive.meanMgdlPerMin) > lim || abs(input.drive.lowerMgdlPerMin) > lim ||
                abs(input.drive.safetyLowerMgdlPerMin) > lim
            )
                return PredictorOutcome.Rejected(PredictorReason.DRIVE_OUT_OF_BOUNDS, "drive beyond $lim")
        }

        // --- Integration ------------------------------------------------------
        val pts = ArrayList<TrajectoryPoint>(input.horizonMin)
        var meanBg = input.bgAtAnchor
        var lowerBg = input.bgAtAnchor
        // C2: dritte Bahn, gleicher Anker — am Anker sind alle drei identisch.
        var lowerBgPriorFree = input.bgAtAnchor
        var minMean = input.bgAtAnchor
        var minLower = input.bgAtAnchor
        var minLowerPriorFree = input.bgAtAnchor
        var timeToMinLower = 0
        // C3: was die synthetische Dosis der Bahn bis zum Horizont genommen hat.
        var pendingDrop = 0.0

        for (i in 1..input.horizonMin) {
            val ts = anchor + i * STEP_MS
            val sMin = i.toDouble()

            val activity = interpolateActivity(t.points, ts)
                ?: return PredictorOutcome.Rejected(PredictorReason.GRID_MISMATCH, "no activity at $ts")
            val isf = isfAt(input.isfSlots, ts)
                ?: return PredictorOutcome.Rejected(PredictorReason.MISSING_ISF_SLOT, "no ISF slot at $ts")
            // NaN entkommt einem Bereichsvergleich: sowohl `<` als auch `>` sind
            // fuer NaN false. Endlichkeit muss deshalb EIGENS geprueft werden.
            if (!isf.isFinite() || isf < input.bounds.minIsfMgdlPerU || isf > input.bounds.maxIsfMgdlPerU)
                return PredictorOutcome.Rejected(PredictorReason.ISF_OUT_OF_BOUNDS, "isf=$isf")

            // Vorzeichentreu, identisch zur gelockten K1-Regel bgiRate = -activity*profileIsf.
            val bgiRate = -activity * isf
            val f = input.decay.factorAt(sMin)
            // C10 (Codex H5): der Zerfall ist VORZEICHENBEWUSST. Fuer negative
            // Antriebsanteile gilt der LANGSAMERE der beiden Faktoren - ein
            // negativer Antrieb darf nie schneller wegsterben als ohne die
            // Kuerzung. `maxOf` statt "nimm einfach decayNegativeDrive": so
            // haengt die Einseitigkeit an der Rechnung und nicht daran, dass
            // der Aufrufer das langsamere Modell einsetzt.
            val fNegative = input.decayNegativeDrive?.let { maxOf(f, it.factorAt(sMin)) } ?: f
            val dMean = decayed(input.drive.meanMgdlPerMin, f, fNegative)
            val dLower = decayed(input.drive.lowerMgdlPerMin, f, fNegative)
            val dLowerPriorFree = decayed(input.drive.safetyLowerMgdlPerMin, f, fNegative)

            // ============================================================
            // KEIN COB-TERM. Bewusste Entscheidung, und sie steht HIER, weil
            // die naechste Zeile die Stelle ist, an der man ihn addieren wuerde.
            //
            // `dMean` stammt aus rSigned = Theil-Sen-Steigung der BGI-BEREINIGTEN
            // Reihe (BgiAdjustedSeries.adjust/theilSen). Das ist die GEMESSENE
            // Netto-Stoerung, ursachenagnostisch: Kohlenhydrate, Dawn/EGP,
            // Stress, Sport, Basalabweichung, Sensordrift. Eine laufende
            // Mahlzeit steckt darin bereits, sobald sie sich in der BG zeigt.
            //
            // AM AAPS-QUELLCODE NACHGEPRUEFT, und es ist dort dieselbe Groesse:
            //   IobCobOref1Worker.kt:145-146  bgi = -iob.activity*sens*5
            //                                 deviation = delta - bgi
            //   :224   ci = max(deviation, totalMinCarbsImpact)
            //   :226   this5MinAbsorption = ci * getIc / sens
            //   :228   cob = max(previous.cob - this5MinAbsorption, 0)
            // (IobCobOrefWorker.kt:133-134 / 220 / 222 / 224 — dieselbe Regel,
            //  andere Herkunft der Untergrenze.)
            //
            // Praezise, damit der Satz nicht angreifbar ist: COB ist die
            // GETIPPTE Menge minus einem Abbau, der aus `deviation` gerechnet
            // wird. Nur der ABBAU ist dieselbe Messung wie rSigned; die Zunahme
            // ist eine Eingabe, keine zweite Beobachtung. Ein `+ carbImpact(t)`
            // hier speist damit fuer jede bereits sichtbare Mahlzeit dieselbe
            // Messung ein zweites Mal ein.
            //
            // DIE EINE ECHTE LUECKE, beziffert statt behauptet: auf den
            // gelockten Konstanten (Fenster 18 min, Paarabstand >= 2 min) folgt
            // der Median einem Steigungssprung mit 0 % bis Minute 5, 50 % bei
            // Minute 9 und 100 % ab Minute 13. Dazu kommen Q1-Lag und
            // Resorptionsbeginn, beide NICHT gemessen. In diesem Fenster traegt
            // COB tatsaechlich Information bei. Und `dMean = drive * factorAt`
            // kann nie wachsen (factorAt <= 1 in allen drei Modellen, live
            // verdrahtet ist das monoton fallende M1) — eine beginnende
            // Resorption ist als Beschleunigung nicht ausdrueckbar. Die BAHN
            // kann sehr wohl steiler werden, weil bgiRate mit abklingender
            // Insulinaktivitaet gegen 0 laeuft; der ANTRIEBSTERM kann es nicht.
            //
            // Trotzdem nicht gebaut, vier Gruende:
            //  (i)   Es waere ein Prebolus auf eine getippte Zahl. Eine Dosis aus
            //        Minute 0-13 wird von keiner Messung mehr korrigiert, bevor
            //        sie wirkt — und im FCL ist die Carb-Eingabe der
            //        unzuverlaessigste Eingang des Systems.
            //  (ii)  Es fehlte die Untergrenze, die den Irrtum auffinge. Einen
            //        AUFWAERTS wirkenden Modellterm in eine Bahn ohne
            //        Sicherheitsband zu haengen ist die falsche Reihenfolge.
            //  (iii) Die nicht doppelzaehlende Bauform waere eine ZERLEGUNG von
            //        rSigned, keine Addition — das aendert `adjust()`, und
            //        BgiAdjustedSeries sagt ausdruecklich, jede Abweichung dort
            //        sei ein NEUER, ungelockter Kandidat.
            //  (iv)  Eine dritte Bauform gaebe es noch: COB als FORM-Information
            //        fuer `input.decay` statt als Summand. Auch die faellt aus —
            //        sie aendert die Predictor-Identitaet, und eine gemessene
            //        Resorptionskurve gibt es nicht.
            //
            // Und der Bezugsweg selbst waere unsauber: `getCobInfo` liest
            // `ads.getLastAutosensData`, und dessen Rueckfall
            // `storedLastAutosensResult` liefert einen Wert GENAU DANN, wenn er
            // aelter als 11 Minuten ist (AutosensDataStoreObject.kt:129) — also
            // einen stillen Altwert ohne Kennzeichnung. Das verletzt die Regel
            // "UNKNOWN ist ein Zustand, kein Wert" unmittelbar.
            //
            // Wiedervorlage: wenn das Unsicherheitsband kalibriert ist UND die
            // Prognosefehler im Export zeigen, dass die ersten 13 Minuten nach
            // einer Carb-Eingabe systematisch danebenliegen. Zyklen ohne
            // predRelease/minLower (Abbruchzyklen) zaehlen dabei als LUECKE,
            // nicht als "kein Fehler".
            // ============================================================

            // C3: Wirkung der publizierten, im IOB noch unsichtbaren Menge.
            // Dieselbe Vorzeichenregel wie oben: bgi = -activity*isf, also
            // senkend. Ein POSITIVER Wert hiesse negative Aktivitaet - das waere
            // eine Dosis, die die Bahn ANHEBT und sich selbst rechtfertigt;
            // dagegen steht hier ein Riegel und keine Annahme ueber den
            // Aufrufer. Bewusst NICHT in `bgiRate` addiert: jenes Feld ist die
            // Wirkung des AAPS-IOB-ARRAYS und muss dagegen nachpruefbar bleiben.
            //
            // RECHTE REGEL AUCH HIER, und in der Lieferminute ist sie
            // GROSSZUEGIG: faellt die Lieferung mitten in ein Minutenintervall,
            // rechnet diese Minute trotzdem voll. Das UEBERschaetzt die Wirkung
            // um Sekundenbruchteile - also in die konservative Richtung (Bahn
            // tiefer). Die anteilige Rechnung von [CandidateSearch] waere hier
            // die OPTIMISTISCHERE, und bei den echten Insulinmodellen ist die
            // Aktivitaet zum Lieferzeitpunkt ohnehin 0.
            //
            // C3-01: die Posten werden EINZELN ausgewertet und erst danach
            // addiert. Jeder bringt seinen eigenen Lieferanker mit; die Summe
            // der Wirkungen ist etwas anderes als die Wirkung der Summe an
            // EINEM Anker (genau das war die Untererfassung aus G.2).
            var pendingBgi = 0.0
            for (d in pendingDoses) {
                val bgi = -d.activityAt(ts) * isf
                if (!bgi.isFinite() || bgi > 0.0)
                    return PredictorOutcome.Rejected(
                        PredictorReason.NON_FINITE_INPUT, "pending activity at $ts -> bgi=$bgi",
                    )
                pendingBgi += bgi
            }
            pendingDrop -= pendingBgi

            // RECHTE Regel: der Wert bei ts gilt fuer das Intervall (ts-1min, ts].
            // Dieselbe Konvention wie cumulativeBgi in K1 — eine zweite waere eine
            // Fehlerquelle ohne Nutzen.
            meanBg += (dMean + bgiRate + pendingBgi)
            lowerBg += (dLower + bgiRate + pendingBgi)
            lowerBgPriorFree += (dLowerPriorFree + bgiRate + pendingBgi)

            if (meanBg < minMean) minMean = meanBg
            if (lowerBg < minLower) { minLower = lowerBg; timeToMinLower = i }
            if (lowerBgPriorFree < minLowerPriorFree) minLowerPriorFree = lowerBgPriorFree

            pts.add(TrajectoryPoint(i, ts, meanBg, lowerBg, bgiRate, dMean, dLower, lowerBgPriorFree))
        }

        return PredictorOutcome.Ok(
            PredictorResult(
                points = pts,
                predictionAnchorTs = anchor,
                bgAtAnchor = input.bgAtAnchor,
                minMeanBg = minMean,
                minLowerBg = minLower,
                timeToMinLowerMin = timeToMinLower,
                bgAtHorizonMean = meanBg,
                bgAtHorizonLower = lowerBg,
                // IMMER gesetzt, auch ohne Zuschlag (dann identisch zur unteren
                // Bahn): ein null aus dem Kern waere nicht von "alter Build ohne
                // prior-freie Bahn" zu unterscheiden.
                minLowerBgPriorFree = minLowerPriorFree,
                bgAtHorizonLowerPriorFree = lowerBgPriorFree,
                lineageKind = t.lineage.lineageKind,
                trajectoryContentHash = t.contentHash,
                iobArraySpanMin = t.spanMin,
                iobArrayGridMin = t.gridMin,
                modelTailBeyondArrayMin = t.modelTailBeyondArrayMin,
                inputSkewMs = t.firstTs - anchor,
                // C3: was eingerechnet wurde, steht im Ergebnis - 0 heisst
                // "nichts unterwegs", nicht "nicht betrachtet".
                pendingTransportU = pendingDoses.sumOf { it.amountU },
                pendingDropAtHorizonMgdl = pendingDrop,
            )
        )
    }

    /**
     * Antrieb mal Zerfallsfaktor, VORZEICHENBEWUSST (C10).
     *
     * Positive Anteile (Stoerung nach oben) bekommen `fPositive` — dort ist ein
     * schnellerer Zerfall konservativ, er nimmt Bedarf weg. Negative Anteile
     * bekommen `fNegative` >= `fPositive` — ein schnellerer Zerfall waere dort
     * das Gegenteil von konservativ: er verkleinert den negativen Beitrag und
     * HEBT die untere Bahn.
     *
     * Bei `fNegative == fPositive` (Normalfall, kein Rebound-Fenster) ist das
     * bitgleich zur alten vorzeichenblinden Rechnung.
     */
    internal fun decayed(drive: Double, fPositive: Double, fNegative: Double): Double =
        drive * (if (drive < 0.0) fNegative else fPositive)

    /** Lineare Interpolation zwischen den 5-min-Stuetzstellen; ausserhalb des
     *  Arrays gibt es KEINEN Wert (kein Extrapolieren, Spec §1 P2). */
    internal fun interpolateActivity(points: List<IobPoint>, ts: Long): Double? {
        if (points.isEmpty() || ts < points.first().timeMs || ts > points.last().timeMs) return null
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].timeMs <= ts) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        if (ts == a.timeMs) return a.activity
        if (ts == b.timeMs) return b.activity
        val w = (ts - a.timeMs).toDouble() / (b.timeMs - a.timeMs).toDouble()
        return a.activity + (b.activity - a.activity) * w
    }

    /** ISF des absolut aufgeloesten Blocks; ein Profile-Switch im Horizont wird
     *  dadurch in Live und Replay gleich behandelt. */
    internal fun isfAt(slots: List<IsfSlot>, ts: Long): Double? =
        slots.firstOrNull { ts >= it.startTsInclusive && ts < it.endTsExclusive }?.isfMgdlPerU
}
