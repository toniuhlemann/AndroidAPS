package app.aaps.fuse.core.controller

/**
 * DER WIRKUNGS-WAECHTER (Toni 04.11.2026): steigt der Zucker lange, obwohl laut Modell deutlich Insulin wirken
 * muesste, meldet FUSE einen moeglichen Patch-Ausfall. Reine Meldung - er gibt kein Insulin und haelt keines zurueck.
 *
 * ANLASS: ein Patch gab nach der Anfangsportion des Fruehstuecks nichts mehr ab. Die Pumpe "lieferte" weiter, das
 * Reservoir sank, der Zucker stieg ueber zwei Stunden gleichmaessig - und die Pumpe meldete den Fehler erst Stunden
 * spaeter. Der Waechter des Viewers sah den Ausfall, war unter FUSE aber zu laut: er kannte nur COB und sein eigenes
 * Mahlzeitenfenster, beides gibt es unter FUSE nicht, und so sah jede groessere Mahlzeit wie ein toter Patch aus.
 *
 * DIE REGEL (Variante B, an 14 Tagen Trail geprueft: ein Fehlalarm, der Ausfall nach gut anderthalb Stunden):
 *  1. "Steigt trotz Insulin": der Zucker steigt um mindestens [MIN_RISE_MGDL_PER_MIN], und das Modell erwartet
 *     zugleich ein Absinken von mindestens [MIN_EXPECTED_FALL_MGDL_PER_MIN] (Insulinaktivitaet x ISF).
 *  2. Das gilt seit [RISE_MIN] am Stueck, es stehen mindestens [MIN_BOLUS_IOB_U] Bolus-IOB auf den Buechern, und
 *     der letzte Mahlzeiten-Marker liegt mindestens [AFTER_MARKER_MIN] zurueck. Eine echte Mahlzeit ueberholt
 *     wirkendes Insulin, aber nicht so lange: im Trail sah sie hoechstens eine knappe halbe Stunde so aus.
 *  3. Haelt die Regel [HOLD_MIN] am Stueck, schlaegt der Waechter an.
 *
 * DIE MELDUNG: sie oeffnet eine Episode. Laeuft der Alarm weiter, kommt nach [REMIND_MIN] eine neue Kennung - das
 * heisst fuer das Plugin "noch einmal melden". Die Episode endet erst, wenn der Zucker [END_FALL_MIN] am Stueck
 * faellt, also Insulin wieder sichtbar wirkt. Faellt nur die Regel weg (etwa weil das Modell-IOB abklingt, waehrend
 * der Patch weiter tot ist), bleibt die Meldung stehen, klingelt aber nicht neu.
 *
 * Eine Pause von mehr als [GAP_MIN] zwischen zwei Zyklen beginnt jede Zaehlung neu. Der Zustand ist prozesslokal:
 * ein Neustart beginnt von vorn, die Meldung kommt dann spaeter, nie frueher.
 */
object InsulinEffectWatch {

    /** So schnell muss der Zucker mindestens steigen [mg/dl/min], damit ein Zyklus zaehlt. */
    const val MIN_RISE_MGDL_PER_MIN = 0.5

    /** So stark muss das Modell mindestens fallen lassen [mg/dl/min] - Insulinaktivitaet x ISF. */
    const val MIN_EXPECTED_FALL_MGDL_PER_MIN = 3.0

    /** So viel Bolus-IOB [U] muss auf den Buechern stehen. */
    const val MIN_BOLUS_IOB_U = 3.0

    /** So lange [min] muss "steigt trotz Insulin" am Stueck gelten, bevor die Regel zaehlt. */
    const val RISE_MIN = 30

    /** So lange [min] nach dem letzten Mahlzeiten-Marker zaehlt die Regel nicht. */
    const val AFTER_MARKER_MIN = 60

    /** So lange [min] muss die Regel am Stueck halten, bevor der Alarm kommt. */
    const val HOLD_MIN = 30

    /** Eine laengere Pause [min] zwischen zwei Zyklen beginnt jede Zaehlung neu. */
    const val GAP_MIN = 10

    /** Nach dieser Zeit [min] kommt bei weiter anliegendem Alarm eine neue Kennung - die Meldung klingelt erneut. */
    const val REMIND_MIN = 60

    /** So lange [min] muss der Zucker am Stueck fallen, bis die Episode endet. */
    const val END_FALL_MIN = 10

    private const val MIN_MS = 60_000L

    /**
     * Ein Zyklus.
     *
     * @param markerTs der Mahlzeiten-Marker dieses Zyklus, 0 = keiner. Der Waechter merkt sich den letzten selbst,
     *   damit ein spaeter geleerter Marker die Wartezeit nicht verkuerzt.
     */
    data class Sample(
        val ts: Long,
        val observedRateMgdlPerMin: Double,
        val expectedFallMgdlPerMin: Double,
        val bolusIobU: Double,
        val markerTs: Long,
    )

    data class State(
        val lastTs: Long? = null,
        /** Seit wann "steigt trotz Insulin" am Stueck gilt; `null` = gerade nicht. */
        val riseSinceTs: Long? = null,
        /** Seit wann die ganze Regel am Stueck haelt; `null` = gerade nicht. */
        val ruleSinceTs: Long? = null,
        val lastMarkerTs: Long? = null,
        val episodeOpen: Boolean = false,
        /** In einer offenen Episode: seit wann der Zucker am Stueck faellt. */
        val fallSinceTs: Long? = null,
        /** Kennung des laufenden Alarmtons; ein neuer Wert heisst "noch einmal melden", `null` = keine Episode. */
        val alarmKeyTs: Long? = null,
    )

    data class Result(
        val state: State,
        val risingDespiteInsulin: Boolean,
        val ruleHolds: Boolean,
        val alarm: Boolean,
    )

    fun step(s: State, x: Sample): Result {
        val last = s.lastTs
        // Derselbe Messwert noch einmal (etwa nach einem Neustart im selben Zyklus): nichts weiterzaehlen.
        if (last != null && x.ts <= last) return Result(s, s.riseSinceTs != null, s.ruleSinceTs != null, false)

        val luecke = last == null || x.ts - last > GAP_MIN * MIN_MS
        val marker = x.markerTs.takeIf { it in 1..x.ts }
        val lastMarker = listOfNotNull(s.lastMarkerTs, marker).maxOrNull()
        val werteDa = x.observedRateMgdlPerMin.isFinite() && x.expectedFallMgdlPerMin.isFinite() && x.bolusIobU.isFinite()

        val rising = werteDa && x.observedRateMgdlPerMin >= MIN_RISE_MGDL_PER_MIN &&
            x.expectedFallMgdlPerMin >= MIN_EXPECTED_FALL_MGDL_PER_MIN
        val riseSince = if (!rising) null else if (luecke || s.riseSinceTs == null) x.ts else s.riseSinceTs
        val markerAlt = lastMarker == null || x.ts - lastMarker >= AFTER_MARKER_MIN * MIN_MS
        val rule = riseSince != null && x.ts - riseSince >= RISE_MIN * MIN_MS &&
            x.bolusIobU >= MIN_BOLUS_IOB_U && markerAlt
        val ruleSince = if (!rule) null else if (luecke || s.ruleSinceTs == null) x.ts else s.ruleSinceTs
        val alarm = ruleSince != null && x.ts - ruleSince >= HOLD_MIN * MIN_MS

        var open = s.episodeOpen || alarm
        val falling = x.observedRateMgdlPerMin.isFinite() && x.observedRateMgdlPerMin < 0.0
        val fallSince = if (open && falling) (if (luecke || s.fallSinceTs == null) x.ts else s.fallSinceTs) else null
        if (open && !alarm && fallSince != null && x.ts - fallSince >= END_FALL_MIN * MIN_MS) open = false
        val key = when {
            !open                                                                       -> null
            alarm && (s.alarmKeyTs == null || x.ts - s.alarmKeyTs >= REMIND_MIN * MIN_MS) -> x.ts
            else                                                                        -> s.alarmKeyTs
        }
        return Result(
            State(
                lastTs = x.ts,
                riseSinceTs = riseSince,
                ruleSinceTs = ruleSince,
                lastMarkerTs = lastMarker,
                episodeOpen = open,
                fallSinceTs = if (open) fallSince else null,
                alarmKeyTs = key,
            ),
            risingDespiteInsulin = rising,
            ruleHolds = rule,
            alarm = alarm,
        )
    }
}
