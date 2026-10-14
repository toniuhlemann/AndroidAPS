package app.aaps.fuse.core.controller

/**
 * DER MARKER-PRIME-AUFSCHUB - was davon bleibt: der PIN (Punkt 6).
 *
 * Am Marker werden Horizont und Ablauffrist GEPINNT; eine spaetere Einstellungsaenderung verschiebt eine laufende
 * Frist nicht. Der Pin traegt im Runner die Kontopause DESCENT_RISK_MARKER und das Liveness-Tor: beide pruefen das
 * Abwaertsrisiko ueber den gepinnten Horizont - nur fuer markerautorisiertes Insulin, reine Korrekturen behalten
 * ihren eigenen Riegel. Ein neuer Marker, ein Widerruf oder das Abschalten beenden den Pin mit typisiertem Verfall,
 * nach der Frist verfaellt er sichtbar ([LapseReason]); der Zustand ueberlebt den Neustart im Ledger.
 *
 * DER GEMESSENE ANLASS: ein Abendessen-Marker bei fallendem, bolus-ueberdecktem BG - markerautorisiertes Insulin
 * floss in den Fall, und es folgte ein Tief; die Mahlzeit kam erst deutlich nach dem Marker. GRENZE, ehrlich
 * benannt (Toni): der Pin schuetzt nur vor Ueberversorgung bei bereits GEMESSENEM Fallen.
 *
 * Konsolidierung 2.0: Rueckhalt in einen offenen Betrag und die Freigabe danach sind entfallen; [State.openU] wird
 * nicht mehr gebucht (0) und bleibt nur fuer den Ledger (Rueckweg auf 1.x).
 */
object DeferredPrime {

    enum class LapseReason { EXPIRED, NEW_MARKER, REVOKED, DISABLED }

    /**
     * Restartfester Zustand. [openU] ist der offene AUFSCHUB - autorisiert,
     * nicht versprochen. Die letzte Verfallsbuchung bleibt stehen, damit der
     * Trail einen verfallenen Rest ZEIGEN kann statt ihn verschwinden zu
     * lassen.
     */
    data class State(
        val openU: Double = 0.0,
        val pinnedForMarkerTs: Long = 0L,
        val deadlineTs: Long = 0L,
        val horizonMin: Int = 0,
        val lastLapseReason: LapseReason? = null,
        val lastLapseU: Double = 0.0,
        val lastLapseTs: Long = 0L,
    ) {
        val valid: Boolean
            get() = openU.isFinite() && openU >= 0.0 &&
                lastLapseU.isFinite() && lastLapseU >= 0.0 &&
                (pinnedForMarkerTs > 0L || (openU == 0.0 && deadlineTs == 0L && horizonMin == 0)) &&
                (pinnedForMarkerTs <= 0L || (deadlineTs > pinnedForMarkerTs && horizonMin > 0))
    }

    /**
     * Pinnt Horizont und Frist an EINEN Marker. Ein neuer Marker laesst einen
     * offenen Rest des alten SICHTBAR verfallen (Vertrag 8) - er erbt nichts.
     * Derselbe Marker pinnt idempotent: die laufende Frist bleibt stehen,
     * auch wenn sich die Einstellung inzwischen geaendert hat (Vertrag 2).
     */
    fun pin(state: State, markerTs: Long, horizonMin: Int, endMin: Int): State {
        require(markerTs > 0L) { "pin needs a marker" }
        require(horizonMin > 0 && endMin > 0) { "pin needs positive bounds" }
        if (state.pinnedForMarkerTs == markerTs) return state
        val abgeraeumt = if (state.openU > 0.0) lapse(state, LapseReason.NEW_MARKER, markerTs) else state
        return abgeraeumt.copy(
            openU = 0.0,
            pinnedForMarkerTs = markerTs,
            deadlineTs = markerTs + endMin * 60_000L,
            horizonMin = horizonMin,
        )
    }

    /** Typisierter Verfall - der Rest verschwindet nie stumm (Vertrag 10). */
    fun lapse(state: State, reason: LapseReason, nowTs: Long): State = State(
        openU = 0.0,
        pinnedForMarkerTs = 0L,
        deadlineTs = 0L,
        horizonMin = 0,
        lastLapseReason = reason,
        lastLapseU = state.openU,
        lastLapseTs = nowTs,
    )

    fun expireIfDue(state: State, nowTs: Long): State =
        if (state.pinnedForMarkerTs > 0L && state.deadlineTs in 1..nowTs)
            lapse(state, LapseReason.EXPIRED, nowTs)
        else state

    // Konsolidierung 2.0: Rueckhalt (`withhold`), Klemmung an die Huelle (`clampToHull`), Freigabe
    // (`releaseStep`, `Release`, `Denial`) und Abbuchung (`consume`) sind entfallen.
}
