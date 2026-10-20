package app.aaps.fuse.core.controller

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * DER LANGSAME KORREKTURKANAL (Toni 20.10.2026) - eine positive TBR fuer Tage mit erhoehtem Bedarf.
 *
 * ===================================================================
 * WARUM ES IHN GIBT
 * ===================================================================
 * In langen Hochphasen haelt meist der Guard den SMB zurueck, und danach haetten Schwanz und Exposition fast immer
 * ebenfalls gesperrt; zeitweise laeuft dabei sogar eine Null-TBR. Der Bedarf ist dabei meist positiv - nur die
 * Sperren lassen ihn nicht hinaus. Bei Resistenz (etwa bei einem Infekt) wirkt das Insulin schwaecher - der BG bleibt
 * oben, und FUSE gibt nichts mehr. autoISF hat dafuer den langsamen Weg ueber eine High-Temp; FUSE hatte ihn nicht.
 *
 * ===================================================================
 * WAS ER TUT - UND WAS AUSDRUECKLICH NICHT
 * ===================================================================
 * Ein SCHALTER, ab Werk aus, gedacht fuer Tage, an denen Toni erhoehten Bedarf erwartet. Eingeschaltet laeuft die
 * positive TBR wie das Profilbasal nebenher - unterstuetzend, wenn SMB fliessen, als Ersatz, wenn sie gesperrt sind
 * (Toni). Sie deckt den RESTBEDARF: den gemeinsamen Bedarf von FUSE VOR jeder Sperrentscheidung
 * ([FuseController.jointDemandU]; IOB, unterwegs befindliche SMB und der geplante Rest dieser TBR stehen zeitabhaengig
 * in der Prognose) abzueglich des SMB dieses Zyklus - verteilt auf den 30-min-Block wie bei autoISF (Basal + 2 x
 * Restbedarf). Der Faktor ist NUR die Obergrenze: die Rate laeuft in Stufen ([STAGES]) zwischen Profilbasal und
 * `Faktor x Profilbasal`. Der Kanal darf - als einziger Weg - an Guard, Schwanz und Exposition vorbei, denn genau die
 * halten die Hochphasen. Die Sicherheit haengt an Messung und harten Grenzen:
 *
 *  - Einstieg und Stopp nur ueber den gemessenen Verlauf (q1, UKF-Trend);
 *  - harte Stopps (gemessene Lagen, Technik) kommen vom Aufrufer als [Input.hardStop];
 *  - maxIOB begrenzt SMB und TBR gemeinsam, und an dieser Grenze weicht die TBR: der SMB dieses Zyklus
 *    ([Input.smbThisCycleU]) zaehlt zuerst, den SMB fasst der Kanal nie an. Ein Kommando geht im selben Zyklus wie der
 *    SMB hinaus (`LoopPlugin` gibt den SMB nur ab, wenn das TBR-Kommando desselben Laufs geklappt hat);
 *  - kein staendiges Umstellen: waehrend ein Block laeuft, sinkt er nur, wenn Bedarf oder maxIOB mindestens eine
 *    halbe Stufe darunter liegen - abgestuft Richtung Profilbasal; hoeher geht es erst bei der Erneuerung.
 *
 * Wie die TBR gesetzt, bestaetigt, erneuert und abgebrochen wird, regelt nicht dieses Objekt, sondern derselbe
 * Lebenszyklus wie bei der Teilbasal-Stufe ([PartialTbrOwnership]) mit eigenem Zustand.
 */
object SlowCorrectionChannel {

    const val FACTOR_MIN = 1.2
    const val FACTOR_MAX = 5.0
    const val FACTOR_DEFAULT = 2.0
    const val THRESHOLD_MIN_MGDL = 140.0
    const val THRESHOLD_MAX_MGDL = 300.0
    const val THRESHOLD_DEFAULT_MGDL = 180.0
    const val ENTRY_MIN_MIN = 15
    const val ENTRY_MAX_MIN = 90
    const val ENTRY_DEFAULT_MIN = 30

    /** "Nicht gefallen" ueber die Einstiegszeit: hoechstens so viel unter dem Wert von damals [mg/dl]. */
    const val NOT_FALLING_TOLERANCE_MGDL = 5.0

    /** Einstieg nur, wenn der UKF-Trend gerade nicht deutlich faellt [mg/dl/min]. */
    const val ENTRY_TREND_MIN_MGDL_PER_MIN = -0.5

    /** Stopp: der UKF-Trend faellt so steil [mg/dl/min] ... */
    const val STOP_TREND_MGDL_PER_MIN = -1.0

    /** ... in so vielen Zyklen nacheinander - ein einzelner Wert ist kein Trend. */
    const val STOP_FALL_CYCLES = 2

    /** Stopp, sobald der BG so weit unter die Schwelle faellt [mg/dl]. */
    const val STOP_BELOW_THRESHOLD_MGDL = 20.0

    /** Blocklaenge [min] - die Medtrum kann nur 30-min-TBR. */
    const val BLOCK_MIN = 30

    /**
     * Erneuern erst bei hoechstens so viel Restzeit [min]. `LoopPlugin` stellt dieselbe Rate nicht neu ein,
     * solange die laufende TBR mehr als 5 min hat (let_temp_basal_run) - eine fruehere Erneuerung kaeme nie an,
     * und der Lebenszyklus wartete auf eine Bestaetigung, die nicht kommen kann.
     */
    const val RENEW_REMAINING_MIN = 5

    /** Laenger als so viele Minuten ohne Zyklus: Hochphase und Fallzaehler beginnen neu. */
    const val GAP_RESET_MIN = 6

    /**
     * Die Stufen der TBR: der Zusatz bis zur Obergrenze zu 1/4, 2/4, 3/4 oder ganz, darunter Profilbasal. Jede
     * Aenderung kostet ein Kommando und bewegt mindestens eine Stufe - stufenlos folgte die Rate jedem Zucken des
     * Bedarfs, und die Pumpe bekaeme fast jede Minute ein neues Kommando.
     */
    const val STAGES = 4

    data class Settings(
        val enabled: Boolean,
        /** Die OBERGRENZE der Gesamtrate als Vielfaches des Profilbasals - nicht die Rate selbst. */
        val factor: Double = FACTOR_DEFAULT,
        val thresholdMgdl: Double = THRESHOLD_DEFAULT_MGDL,
        val entryMin: Int = ENTRY_DEFAULT_MIN,
    ) {

        val stopBelowMgdl: Double get() = thresholdMgdl - STOP_BELOW_THRESHOLD_MGDL

        fun violation(): String? = when {
            !factor.isFinite() || factor < FACTOR_MIN || factor > FACTOR_MAX -> "factor=$factor"
            !thresholdMgdl.isFinite() || thresholdMgdl < THRESHOLD_MIN_MGDL || thresholdMgdl > THRESHOLD_MAX_MGDL ->
                "threshold=$thresholdMgdl"
            entryMin < ENTRY_MIN_MIN || entryMin > ENTRY_MAX_MIN -> "entryMin=$entryMin"
            else -> null
        }
    }

    /**
     * Was von Zyklus zu Zyklus mitgefuehrt wird. Bewusst NICHT persistiert: nach einem Neustart beginnt die
     * Einstiegszeit neu, und eine noch laufende Kanal-TBR beendet der Normalpfad als fremde positive TBR - beides
     * in die sichere Richtung.
     */
    data class Memory(
        /** Seit wann liegt q1 ununterbrochen ueber der Schwelle; 0 = nicht. */
        val highSinceTs: Long = 0L,
        /** Zyklen in Folge mit steil fallendem Trend. */
        val fallStreak: Int = 0,
        val lastTs: Long = 0L,
    )

    data class Input(
        val nowTs: Long,
        val q1Mgdl: Double?,
        /** q1 vor [Settings.entryMin] Minuten; null = nicht bekannt (dann kein Einstieg). */
        val q1EntryAgoMgdl: Double?,
        val trendMgdlPerMin: Double?,
        val iobU: Double,
        val maxIobU: Double,
        val scheduledBasalUPerH: Double,
        val basalStepUPerH: Double,
        /** Harte Sperre dieses Zyklus (gemessene Lage, Technik); null = keine. Beendet einen laufenden Kanal. */
        val hardStop: String?,
        /**
         * Der SMB, den der Normalpfad in diesem Zyklus gibt [U]. Er zaehlt zuerst - im Restbedarf und in maxIOB: ein
         * laufender Block bleibt nur, wenn sein Rest daneben passt, und ein neuer Block wird nach ihm bemessen.
         */
        val smbThisCycleU: Double = 0.0,
        /**
         * Der gemeinsame Bedarf VOR jeder Sperrentscheidung [U] ([FuseController.jointDemandU]); null = nicht
         * berechenbar. IOB, unterwegs befindliche SMB und [plannedRemainderU] stehen darin zeitabhaengig.
         */
        val demandU: Double? = null,
        /** Der geplante, noch nicht abgegebene Zusatz der eigenen Kanal-TBR [U] - so, wie er in der Prognose steht. */
        val plannedRemainderU: Double = 0.0,
        /** Laeuft, steht oder endet schon eine Kanal-TBR? Dann gilt Fortsetzen statt Einstieg. */
        val active: Boolean,
        /**
         * Eine andere eigene Basalaktion laeuft (Teilbasal-Stufe). Dann wartet der Kanal - zwei eigene TBR
         * duerfen nie gegeneinander kommandieren.
         */
        val otherOwnBasalActive: Boolean = false,
        /** Die Rate der Kanal-TBR, die die Pumpe gerade nachweislich als unsere fuehrt [U/h]; null = keine. */
        val runningRateUPerH: Double? = null,
        /** Ihre Restlaufzeit laut Pumpe [min]. */
        val runningRemainingMin: Int? = null,
    )

    enum class Reason {
        OFF,
        INVALID_SETTINGS,
        HARD_STOP,
        NO_SIGNAL,
        STOP_FALLING,
        STOP_BELOW,
        PARTIAL_ACTIVE,
        NO_BASAL,
        WAIT_ENTRY,
        /** Der Restbedarf traegt keine Stufe. */
        NO_DEMAND,
        /** Kein Bedarf berechenbar - nichts Neues; ein laufender Block laeuft aus. */
        NO_DEMAND_ESTIMATE,
        MAX_IOB,
        START,
        CONTINUE,
    }

    data class Step(
        /** Will der Kanal in diesem Zyklus laufen? false = nicht starten bzw. beenden. */
        val want: Boolean,
        /** Die gewuenschte GESAMTrate [U/h] (auf den Basalschritt abgerundet); 0, wenn [want] false. */
        val rateUPerH: Double,
        val reason: Reason,
        val detail: String? = null,
        val memory: Memory,
        /** Minuten ununterbrochen ueber der Schwelle (fuer Trail und Viewer). */
        val highForMin: Double,
        /** Die Stufe der gewuenschten Rate, 1..[STAGES]; 0 = keine TBR. */
        val stage: Int = 0,
    ) {
        /** Die Zusatzrate ueber dem Profilbasal [U/h]. */
        fun extraUPerH(scheduledBasalUPerH: Double): Double = max(0.0, rateUPerH - scheduledBasalUPerH)
    }

    fun step(settings: Settings, input: Input, memory: Memory): Step {
        val luecke = memory.lastTs > 0L && input.nowTs - memory.lastTs > GAP_RESET_MIN * 60_000L
        val q1 = input.q1Mgdl?.takeIf { it.isFinite() }
        val hoch = q1 != null && q1 > settings.thresholdMgdl
        val highSince = when {
            !hoch -> 0L
            memory.highSinceTs > 0L && !luecke -> memory.highSinceTs
            else -> input.nowTs
        }
        val trend = input.trendMgdlPerMin?.takeIf { it.isFinite() }
        val fallStreak = if (trend != null && trend < STOP_TREND_MGDL_PER_MIN) (if (luecke) 1 else memory.fallStreak + 1) else 0
        val neu = Memory(highSinceTs = highSince, fallStreak = fallStreak, lastTs = input.nowTs)
        val highForMin = if (highSince > 0L) (input.nowTs - highSince) / 60_000.0 else 0.0

        fun nein(reason: Reason, detail: String? = null) =
            Step(want = false, rateUPerH = 0.0, reason = reason, detail = detail, memory = neu, highForMin = highForMin)

        if (!settings.enabled) return nein(Reason.OFF)
        settings.violation()?.let { return nein(Reason.INVALID_SETTINGS, it) }
        input.hardStop?.let { return nein(Reason.HARD_STOP, it) }
        if (q1 == null) return nein(Reason.NO_SIGNAL)
        if (fallStreak >= STOP_FALL_CYCLES) return nein(Reason.STOP_FALLING)
        if (q1 < settings.stopBelowMgdl) return nein(Reason.STOP_BELOW)
        if (input.otherOwnBasalActive) return nein(Reason.PARTIAL_ACTIVE)
        val basal = input.scheduledBasalUPerH
        val schritt = input.basalStepUPerH
        if (!basal.isFinite() || basal <= 0.0 || !schritt.isFinite() || schritt <= 0.0) return nein(Reason.NO_BASAL)

        if (!input.active) {
            val alt = input.q1EntryAgoMgdl?.takeIf { it.isFinite() }
            val einstieg = hoch && highForMin >= settings.entryMin && alt != null &&
                q1 >= alt - NOT_FALLING_TOLERANCE_MGDL &&
                (trend == null || trend >= ENTRY_TREND_MIN_MGDL_PER_MIN)
            if (!einstieg) return nein(Reason.WAIT_ENTRY)
        }

        // DIE STUFEN. Der Zusatz bis zur Obergrenze, je Stufe auf den Basalschritt abgerundet; Stufe 0 = Profilbasal.
        val zusatzMax = (settings.factor - 1.0) * basal
        fun rateVon(stufe: Int): Double =
            if (stufe <= 0) basal else floor((basal + zusatzMax * stufe / STAGES) / schritt + 1e-9) * schritt
        fun traegt(stufe: Int): Boolean = stufe > 0 && rateVon(stufe) - basal >= schritt - 1e-9
        fun passt(stufe: Int, spielraumU: Double): Boolean = (rateVon(stufe) - basal) * BLOCK_MIN / 60.0 <= spielraumU + 1e-9
        // Die hoechste Stufe, deren Zusatz eine Zusatzrate [U/h] traegt - dieselbe Rundung wie [rateVon].
        fun stufeFuer(zusatzUPerH: Double): Int =
            (STAGES downTo 1).firstOrNull { traegt(it) && rateVon(it) - basal <= zusatzUPerH + 1e-9 } ?: 0
        fun ja(stufe: Int, reason: Reason, detail: String?, rate: Double = rateVon(stufe)) = Step(
            want = true, rateUPerH = rate, reason = reason, detail = detail, memory = neu,
            highForMin = highForMin, stage = stufe,
        )

        val smb = max(0.0, input.smbThisCycleU.takeIf { it.isFinite() } ?: 0.0)
        val bedarf = input.demandU?.takeIf { it.isFinite() }
        val geplant = max(0.0, input.plannedRemainderU.takeIf { it.isFinite() } ?: 0.0)
        val laufend = input.runningRateUPerH?.takeIf { it.isFinite() && it > 0.0 }
        val restMin = input.runningRemainingMin ?: 0
        // Fuer einen FRISCHEN Block: maxIOB neben dem SMB dieses Zyklus.
        val spielraum = input.maxIobU - input.iobU - smb
        if (!spielraum.isFinite()) return nein(Reason.MAX_IOB, "spielraum=$spielraum")

        // ---- EIN BLOCK LAEUFT: halten oder abgestuft senken, nie mitten im Block anheben ------------------------
        if (input.active && laufend != null && restMin > RENEW_REMAINING_MIN) {
            val stufeLauf = (STAGES downTo 1).firstOrNull { traegt(it) && rateVon(it) <= laufend + schritt / 2.0 } ?: 0
            val offen = max(0.0, laufend - basal) * restMin / 60.0
            // Was die Restzeit tragen muss: der offene Rest plus der Restbedarf. Eine halbe Stufe Spiel, damit ein
            // Bedarf knapp unter der Deckung den Block nicht gleich umstellt. Ohne Bedarfsschaetzung bleibt er stehen.
            val stufeBedarf = if (bedarf == null) stufeLauf
            else stufeFuer((offen + bedarf - smb) / (restMin / 60.0) + zusatzMax / STAGES / 2.0)
            val iobPasst = input.iobU + smb + offen <= input.maxIobU + 1e-9
            val ueberGrenze = laufend > rateVon(STAGES) + schritt / 2.0
            // Gehalten wird die LAUFENDE Rate, nicht die Stufenrate: hat AAPS sie gekappt (Max-Basal), loeste eine
            // andere Zahl hier ein Kommando aus, das nichts aendert.
            if (!ueberGrenze && iobPasst && stufeBedarf >= stufeLauf)
                return ja(stufeLauf, Reason.CONTINUE, if (bedarf == null) "block ohne Bedarf" else "block", rate = laufend)
            // Senken: hoechstens auf die Bedarfsstufe; passt der Rest nicht in maxIOB, mindestens eine Stufe tiefer -
            // ein ganzer Block auf derselben Stufe ist nie kleiner als ihr Rest.
            val obergrenze = minOf(stufeBedarf, STAGES, if (iobPasst) STAGES else stufeLauf - 1)
            for (s in obergrenze downTo 1) if (traegt(s) && passt(s, spielraum))
                return ja(s, Reason.CONTINUE, "$s/$STAGES " + if (s < stufeBedarf || !iobPasst) "maxIob" else "bedarf")
            return if (stufeBedarf == 0) nein(Reason.NO_DEMAND) else nein(Reason.MAX_IOB, "spielraum=$spielraum")
        }

        // ---- START ODER ERNEUERUNG: ein frischer 30-min-Block traegt Restbedarf plus geplanten Rest -------------
        // Wie bei autoISF (Basal + 2 x Restbedarf); die Erneuerung ersetzt den laufenden Rest, deshalb zaehlt er mit.
        if (bedarf == null) return nein(Reason.NO_DEMAND_ESTIMATE)
        val stufeZiel = stufeFuer(2.0 * (geplant + bedarf - smb))
        if (stufeZiel == 0) return nein(Reason.NO_DEMAND, "bedarf=$bedarf")
        for (s in stufeZiel downTo 1) if (traegt(s) && passt(s, spielraum))
            return ja(
                s, if (input.active) Reason.CONTINUE else Reason.START,
                "$s/$STAGES " + if (s < stufeZiel) "maxIob" else "bedarf",
            )
        return nein(Reason.MAX_IOB, "spielraum=$spielraum")
    }

    /**
     * DIE BUCHUNG DES KANALS - getrennt nach dem, was die Pumpe abgegeben hat, was ein laufender Block noch
     * reserviert und was verfallen ist (Review zu E-042: Reserviert ist nicht abgegeben, und ein abgebrochener Block
     * gibt seinen Rest nie ab).
     *
     * Gebucht wird nur, was die Pumpe ZEIGT: ein Block zaehlt ab seiner Anforderung, sobald sie ihn als unseren
     * fuehrt, und endet, sobald sie ihn nicht mehr fuehrt. Endet er vor seiner Zeit OHNE Nachfolger (Abbruch, Null,
     * Abbruch von Hand), verfaellt sein Rest. Loest ihn ein neuer Kanal-Block ab (Erneuerung, andere Rate), verfaellt
     * nichts: der neue Block gibt in derselben Zeit ab und reserviert neu. Zyklusluecken ueber [GAP_RESET_MIN] bucht
     * sie nicht - dort ist nicht bekannt, was geschah.
     */
    data class Ledger(
        /** Der Block beim letzten Zyklus - Rate [U/h], Anforderungszeit und Restlaufzeit laut Pumpe. */
        val blockRateUPerH: Double? = null,
        val blockSetAtTs: Long = 0L,
        val blockRemainingMin: Int = 0,
        /** Seine Zusatzrate ueber dem Profilbasal [U/h]. */
        val blockExtraUPerH: Double = 0.0,
        val lastTs: Long = 0L,
        /** Zusatzinsulin dieser Episode, das die Pumpe abgegeben hat [U]. */
        val deliveredU: Double = 0.0,
        /** Zusatzinsulin dieser Episode, das ein vorzeitig beendeter Block nicht mehr abgab [U]. */
        val cancelledU: Double = 0.0,
    ) {
        /** Was der laufende Block noch abgeben wird [U]. */
        val reservedU: Double get() = if (blockRateUPerH == null) 0.0 else blockExtraUPerH * blockRemainingMin / 60.0
    }

    /**
     * Schreibt die Buchung um einen Zyklus fort.
     *
     * @param runningRateUPerH die Kanal-TBR, die die Pumpe JETZT als unsere fuehrt; null = keine
     * @param newEpisode ein neuer Einstieg: ohne laufenden Block beginnen die Summen bei null
     */
    fun book(
        ledger: Ledger,
        nowTs: Long,
        runningRateUPerH: Double?,
        runningSetAtTs: Long,
        runningRemainingMin: Int?,
        scheduledBasalUPerH: Double,
        newEpisode: Boolean,
    ): Ledger {
        val neu = newEpisode && ledger.blockRateUPerH == null && runningRateUPerH == null
        var geliefert = if (neu) 0.0 else ledger.deliveredU
        var verfallen = if (neu) 0.0 else ledger.cancelledU
        val dtMin = (nowTs - ledger.lastTs) / 60_000.0
        val bekannt = ledger.lastTs > 0L && dtMin >= 0.0 && dtMin <= GAP_RESET_MIN
        val derselbe = ledger.blockRateUPerH != null && runningRateUPerH != null && ledger.blockSetAtTs == runningSetAtTs
        if (bekannt && ledger.blockRateUPerH != null) {
            when {
                derselbe -> geliefert += ledger.blockExtraUPerH * dtMin / 60.0
                // Ausgelaufen: der Rest ging noch hinaus.
                ledger.blockRemainingMin <= dtMin + 1e-9 -> geliefert += ledger.reservedU
                // Abgeloest: er gab bis zur Anforderung des neuen Blocks ab, den Rest ersetzt der neue.
                runningRateUPerH != null ->
                    geliefert += ledger.blockExtraUPerH * min(dtMin, max(0.0, (runningSetAtTs - ledger.lastTs) / 60_000.0)) / 60.0
                // Vorzeitig weg ohne Nachfolger - das Endkommando ging im letzten Zyklus hinaus: der Rest verfaellt.
                else -> verfallen += ledger.reservedU
            }
        }
        val extra = if (runningRateUPerH != null && scheduledBasalUPerH.isFinite())
            max(0.0, runningRateUPerH - scheduledBasalUPerH) else 0.0
        if (bekannt && runningRateUPerH != null && !derselbe)
            geliefert += extra * min(dtMin, max(0.0, (nowTs - runningSetAtTs) / 60_000.0)) / 60.0
        return Ledger(
            blockRateUPerH = runningRateUPerH,
            blockSetAtTs = if (runningRateUPerH != null) runningSetAtTs else 0L,
            blockRemainingMin = if (runningRateUPerH != null) runningRemainingMin ?: 0 else 0,
            blockExtraUPerH = extra,
            lastTs = nowTs,
            deliveredU = geliefert,
            cancelledU = verfallen,
        )
    }

    /** Laenge der Einzelgaben [min], in die der geplante Rest fuer die Prognose zerlegt wird. */
    const val CHUNK_MIN = 5

    /**
     * DER GEPLANTE REST EINES EIGENEN BLOCKS ALS EINZELGABEN fuer Bahn und Schwanz: je [CHUNK_MIN] Minuten der
     * Zusatz ueber dem Profilbasal, ab jetzt (oder ab der Anforderung) bis zum Blockende - Zeitpunkt und Menge [U].
     * So steht die geplante TBR zeitabhaengig in der Prognose wie ein SMB auf dem Transportweg; was schon abgegeben
     * ist, steht im IOB und zaehlt nicht noch einmal. Jede Gabe sitzt am Anfang ihres Abschnitts - ein wenig frueher
     * als wirklich, also eher zu viel Wirkung in der Bahn als zu wenig.
     */
    fun plannedChunks(
        rateUPerH: Double,
        setAtTs: Long,
        durationMin: Int,
        nowTs: Long,
        scheduledBasalUPerH: Double,
    ): List<Pair<Long, Double>> {
        val zusatz = rateUPerH - scheduledBasalUPerH
        if (!zusatz.isFinite() || zusatz <= 0.0 || durationMin <= 0) return emptyList()
        val ende = setAtTs + durationMin * 60_000L
        val gaben = ArrayList<Pair<Long, Double>>()
        var t = max(nowTs, setAtTs)
        while (t < ende) {
            val bis = min(ende, t + CHUNK_MIN * 60_000L)
            gaben += t to zusatz * (bis - t) / 3_600_000.0
            t = bis
        }
        return gaben
    }

    /**
     * DIE GEMEINSAME GRENZE FUER DEN SMB, wenn der Kanal in diesem Zyklus keinen Platz machen kann (kein Kommando):
     * hoechstens der maxIOB-Spielraum neben dem geplanten Rest, abgerundet auf den Bolusschritt; darunter 0. Passt
     * der SMB daneben, bleibt er unveraendert - das ist keine Sperre, nur die Grenze, die SMB und TBR sich teilen.
     */
    fun clampSmbU(smbU: Double, roomU: Double, bolusStepU: Double): Double {
        if (!smbU.isFinite() || smbU <= 0.0) return 0.0
        if (!roomU.isFinite()) return 0.0
        if (smbU <= roomU + 1e-9) return smbU
        if (!bolusStepU.isFinite() || bolusStepU <= 0.0) return 0.0
        return max(0.0, floor(roomU / bolusStepU + 1e-9) * bolusStepU)
    }

    /**
     * Braucht die gewuenschte Rate in diesem Zyklus ein Kommando? Neu, wenn nichts von uns laeuft; bei einer
     * bestaetigt laufenden Kanal-TBR nur fuer eine andere Rate oder zur Erneuerung kurz vor ihrem Ende.
     */
    fun needsCommand(
        wunschRateUPerH: Double,
        confirmedRateUPerH: Double?,
        currentRemainingMin: Int?,
        basalStepUPerH: Double,
    ): Boolean {
        if (confirmedRateUPerH == null) return true
        if (abs(wunschRateUPerH - confirmedRateUPerH) > basalStepUPerH / 2.0) return true
        return (currentRemainingMin ?: 0) <= RENEW_REMAINING_MIN
    }
}
