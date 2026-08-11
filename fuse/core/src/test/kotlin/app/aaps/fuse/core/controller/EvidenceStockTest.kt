package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER QUELLEN- UND VERBRAUCHSVERTRAG, einzeln geprueft.
 *
 * Jede Zusicherung hier hat einen Anlass in der Messung oder in einem Fehler,
 * der schon passiert ist - deshalb steht sie einzeln und nicht in einer
 * Schleife ueber Faelle.
 */
class EvidenceStockTest {

    private val T0 = 1_700_000_000_000L
    private val ISF = 90.0

    private fun eingabe(
        minute: Int,
        adjusted: Double,
        driveLower: Double? = 1.0,
        committedU: Double = 0.0,
        healthReady: Boolean = true,
        measuredLow: Boolean = false,
        episodeId: Long = 1L,
        segmentStartTs: Long = T0,
        persistedStateKnown: Boolean = true,
    ) = EvidenceStock.Input(
        nowMs = T0 + minute * 60_000L,
        sourceTs = T0 + minute * 60_000L,
        adjusted = adjusted,
        segmentStartTs = segmentStartTs,
        driveLowerMgdlPerMin = driveLower,
        healthReady = healthReady,
        measuredLow = measuredLow,
        episodeId = episodeId,
        episodeCommittedU = committedU,
        isfMgdlPerU = ISF,
        persistedStateKnown = persistedStateKnown,
    )

    /** Erster Zyklus: es gibt keinen Bezugspunkt, also keinen Zufluss. Der
     *  Bestand entsteht frueestens beim ZWEITEN Messpunkt. */
    @Test
    fun `der erste Punkt liefert nur den Bezug, keinen Zufluss`() {
        val r = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0))
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.SEGMENT_BREAK, r.noInflow)
        assertEquals(T0, r.state.lastAcceptedTs)
        assertEquals(100.0, r.state.lastAdjusted, 1e-9)
    }

    /** Der Zuwachs der bereinigten Reihe IST die Stoerung - Tonis Herleitung. */
    @Test
    fun `der Zuwachs der bereinigten Reihe wird zum Bestand`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        val r = EvidenceStock.step(s, eingabe(1, 103.0))
        assertEquals(3.0, r.inflowMgdl, 1e-9)
        assertTrue(r.state.stockMgdl > 0.0)
    }

    /**
     * DIESELBE EVIDENZ WIRD NICHT ZWEIMAL FINANZIERT.
     *
     * Der Kern des Vertrags: bei 60 Zyklen pro Stunde wuerde ein r-gespeister
     * Bestand dieselbe beobachtete Stoerung sechzigmal verbuchen. Hier kommt
     * derselbe Messpunkt zweimal - der zweite Zyklus darf nichts hinzufuegen.
     */
    @Test
    fun `derselbe Messpunkt fliesst nur einmal zu`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 103.0)).state
        val vorher = s.stockMgdl
        // Gleicher sourceTs, gleicher Wert - ein zweiter Reglerzyklus auf
        // demselben CGM-Punkt.
        val r = EvidenceStock.step(s, eingabe(1, 103.0))
        assertEquals(0.0, r.inflowMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.NO_NEW_SAMPLE, r.noInflow)
        assertTrue(r.state.stockMgdl <= vorher + 1e-9, "der Bestand darf nicht wachsen")
    }

    /** Zugesagtes Insulin verbraucht den Bestand sofort - nicht erst, wenn das
     *  Treatment sichtbar wird (gemessene Latenz p90 56 s, max 854 s). */
    @Test
    fun `zugesagtes Insulin verbraucht den Bestand sofort`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 130.0)).state          // +30 mg/dl
        val vorher = s.stockMgdl
        val r = EvidenceStock.step(s, eingabe(2, 130.0, committedU = 0.20))
        // 0,20 U x 90 = 18 mg/dl Abzug
        assertTrue(r.state.stockMgdl < vorher - 15.0, "18 mg/dl muessen abgezogen sein: ${r.state.stockMgdl}")
    }

    /** GEGENPROBE zur Bilanz: ohne Abzug wuerde der Bruttobestand nie
     *  verbraucht und koennte wiederholt lizenzieren. */
    @Test
    fun `ohne Abgabe bleibt der Bestand bestehen`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 130.0)).state
        val r = EvidenceStock.step(s, eingabe(2, 130.0))
        assertTrue(r.state.stockMgdl > 0.0)
    }

    /** Ein gemessenes Tief widerruft SOFORT und VOLLSTAENDIG. */
    @Test
    fun `ein gemessenes Tief loescht den Bestand`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 140.0)).state
        val r = EvidenceStock.step(s, eingabe(2, 140.0, measuredLow = true))
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
    }

    /** Signalfehler ebenso: ein Bestand ist eine Behauptung ueber die
     *  naechsten Minuten, und ohne gesundes Signal traegt sie nicht. */
    @Test
    fun `ein ungesundes Signal loescht den Bestand`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 140.0)).state
        assertEquals(0.0, EvidenceStock.step(s, eingabe(2, 140.0, healthReady = false)).state.stockMgdl, 1e-9)
    }

    /** Ein Segmentbruch macht die Differenz bedeutungslos - `cumulativeBgi`
     *  startet je Segment neu bei 0. Dann wird ausgesetzt, nicht geschaetzt. */
    @Test
    fun `ueber einen Segmentbruch fliesst nichts zu`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        val r = EvidenceStock.step(s, eingabe(1, 900.0, segmentStartTs = T0 + 60_000L))
        assertEquals(0.0, r.inflowMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.SEGMENT_BREAK, r.noInflow)
    }

    /** Das Evidenztor: ohne positive konservative Untergrenze entsteht kein
     *  Bestand, auch wenn die Reihe steigt. */
    @Test
    fun `ohne positiven Antrieb entsteht kein Bestand`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        val r = EvidenceStock.step(s, eingabe(1, 130.0, driveLower = -0.2))
        assertEquals(0.0, r.inflowMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.DRIVE_NOT_POSITIVE, r.noInflow)
    }

    /**
     * EIN RUECKGANG NIMMT SCHNELLER WEG, ALS EIN ANSTIEG GIBT.
     *
     * Faellt die bereinigte Reihe, war die Stoerung kleiner als angenommen -
     * dann genuegt es nicht, nur nicht weiter zu fuellen. Die Fehlerrichtung
     * ist eindeutig: zu viel Bestand kostet Insulin, zu wenig kostet Wartezeit.
     */
    @Test
    fun `ein Rueckgang der Reihe raeumt den Bestand ueberproportional ab`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 120.0)).state
        val nachAnstieg = s.stockMgdl
        val r = EvidenceStock.step(s, eingabe(2, 115.0))     // -5
        assertEquals(EvidenceStock.NoInflow.NO_RISE, r.noInflow)
        assertTrue(
            r.state.stockMgdl < nachAnstieg - 9.0,
            "5 mg/dl Rueckgang muessen mehr als 5 kosten: $nachAnstieg -> ${r.state.stockMgdl}",
        )
    }

    /** DER HARTE DECKEL. Ein Bestand aus `Δadjusted` kann bei dauerhaft
     *  steigender Bahn unbegrenzt nachwachsen - Gegenregulation, Sensordrift,
     *  schlechte Infusionsstelle sehen alle wie Stoerung aus. */
    @Test
    fun `nach dem Maximalende gibt es keinen Bestand mehr`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 140.0)).state
        val r = EvidenceStock.step(s, eingabe(EvidenceStock.Config().maxEpisodeMin + 1, 200.0))
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.EPISODE_EXPIRED, r.noInflow)
    }

    /** Ohne Episode gibt es nichts - der Bestand ist an die Mahlzeit gebunden,
     *  nicht an jede steigende Kurve. */
    @Test
    fun `ohne Episode entsteht kein Bestand`() {
        val r = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0, episodeId = 0L))
        assertEquals(EvidenceStock.NoInflow.NO_EPISODE, r.noInflow)
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
    }

    /**
     * DER KREDIT IST EINE RATE, KEIN BESTAND. Ein Bestand von 30 mg/dl darf
     * nicht als Antrieb von 30 mg/dl/min herauskommen - das waere
     * physiologisch absurd und wuerde die Bahn in einem Zyklus um Hunderte
     * mg/dl heben.
     */
    @Test
    fun `der Kredit ist der Bestand ueber ein Fenster, nicht der Bestand`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        val r = EvidenceStock.step(s, eingabe(1, 130.0))
        assertTrue(r.creditMgdlPerMin > 0.0)
        assertTrue(
            r.creditMgdlPerMin < r.state.stockMgdl / 2.0,
            "Rate ${r.creditMgdlPerMin} gegen Bestand ${r.state.stockMgdl}",
        )
    }

    /** Der Verfall wirkt ohne neue Evidenz - die "begrenzte zeitliche
     *  Nachwirkung", kuerzer als der 10-min-Nachlauf von `r`. */
    @Test
    fun `ohne neue Evidenz verfaellt der Bestand`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 140.0)).state
        val vorher = s.stockMgdl
        // Flache Reihe ueber den halben Verfallszeitraum.
        s = EvidenceStock.step(s, eingabe(1 + EvidenceStock.Config().decayMin / 2, 140.0)).state
        assertTrue(s.stockMgdl < vorher * 0.75, "$vorher -> ${s.stockMgdl}")
    }

    // ---- Die drei strukturell erzwungenen Vertraege -----------------------

    /**
     * DER ABZUG IST DER ZUWACHS, NICHT DER STAND.
     *
     * `episodeCommittedU` ist kumulativ. Bleibt er zwischen zwei Zyklen
     * gleich, darf NICHTS abgezogen werden - ein Aufrufer, der den
     * Episodenstand durchreicht, wuerde sonst dieselbe Dosis jede Minute
     * erneut verbuchen und den Bestand in Sekunden leerraeumen.
     */
    @Test
    fun `ein unveraenderter Abgabestand wird nicht erneut abgezogen`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 160.0)).state
        s = EvidenceStock.step(s, eingabe(2, 160.0, committedU = 0.20)).state
        val nachAbzug = s.stockMgdl
        // Derselbe kumulative Stand, drei Zyklen lang.
        repeat(3) { i -> s = EvidenceStock.step(s, eingabe(3 + i, 160.0, committedU = 0.20)).state }
        // Nur Verfall darf gewirkt haben, kein weiterer Abzug von 18 mg/dl.
        assertTrue(
            s.stockMgdl > nachAbzug - 18.0,
            "der Abzug wurde wiederholt: $nachAbzug -> ${s.stockMgdl}",
        )
    }

    /** Und ein WACHSENDER Stand zieht die Differenz ab - sonst waere die
     *  Zusicherung oben nur "es wird nie abgezogen". */
    @Test
    fun `ein wachsender Abgabestand zieht die Differenz ab`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 200.0)).state
        s = EvidenceStock.step(s, eingabe(2, 200.0, committedU = 0.20)).state
        val vorher = s.stockMgdl
        val r = EvidenceStock.step(s, eingabe(3, 200.0, committedU = 0.40))
        assertTrue(
            r.state.stockMgdl < vorher - 15.0,
            "die zweiten 0,20 U muessen 18 mg/dl kosten: $vorher -> ${r.state.stockMgdl}",
        )
    }

    /**
     * DER DECKEL LAEUFT AB DEM URSPRUNG - eine zweite Welle startet ihn nicht
     * neu.
     *
     * Deshalb traegt die Eingabe eine Episoden-IDENTITAET und kein Bit
     * "aktiv": ein Wellental wuerde ein Bit auf false setzen, und die
     * naechste Welle begaenne als neue Episode mit frischen vier Stunden.
     * Der gemessene Lauf vom 11.08. hatte genau so ein Tal bei T+105.
     */
    @Test
    fun `eine zweite Welle startet den Episodendeckel nicht neu`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 120.0)).state
        // Tal: dieselbe Episode, aber lange nichts.
        s = EvidenceStock.step(s, eingabe(100, 120.0)).state
        // Zweite Welle, dieselbe episodeId - kurz VOR dem Deckel.
        val vorDeckel = EvidenceStock.step(s, eingabe(230, 140.0))
        assertTrue(vorDeckel.noInflow != EvidenceStock.NoInflow.EPISODE_EXPIRED)
        // Und kurz danach ist Schluss, gerechnet ab MINUTE 0, nicht ab 100.
        val nachDeckel = EvidenceStock.step(vorDeckel.state, eingabe(245, 160.0))
        assertEquals(EvidenceStock.NoInflow.EPISODE_EXPIRED, nachDeckel.noInflow)
    }

    /** Eine ANDERE Episode erbt nichts - weder Bestand noch Uhr noch
     *  Abgabestand. */
    @Test
    fun `eine neue Episode erbt nichts`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 200.0)).state
        assertTrue(s.stockMgdl > 0.0)
        val r = EvidenceStock.step(s, eingabe(2, 200.0, episodeId = 2L))
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
        assertEquals(2L, r.state.episodeId)
    }

    /**
     * BEI EINER LUECKE LAEUFT DIE WANDUHR WEITER.
     *
     * Der Bestand wird nicht eingefroren und kehrt nicht unveraendert
     * zurueck - er baut waehrend der Luecke ab, waehrend Zufluss UND Ausgabe
     * gesperrt sind. Das neue Segment setzt nur die Messbasis.
     */
    @Test
    fun `ein Segmentbruch friert den Bestand nicht ein`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 200.0)).state
        val vorLuecke = s.stockMgdl
        // 6 Minuten Luecke, danach neues Segment.
        val r = EvidenceStock.step(s, eingabe(7, 300.0, segmentStartTs = T0 + 7 * 60_000L))
        assertEquals(EvidenceStock.NoInflow.SEGMENT_BREAK, r.noInflow)
        assertEquals(0.0, r.inflowMgdl, 1e-9, "ueber die Luecke keine Differenz")
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9, "Ausgabe waehrend des Bruchs gesperrt")
        assertTrue(
            r.state.stockMgdl < vorLuecke * 0.4,
            "der Bestand muss waehrend der Luecke abgebaut haben: $vorLuecke -> ${r.state.stockMgdl}",
        )
        assertEquals(300.0, r.state.lastAdjusted, 1e-9, "nur die Messbasis wird neu gesetzt")
    }

    /**
     * UNKLARER RESTART-ZUSTAND: kein Kredit, mit eigenem Grund.
     *
     * Fail-closed ist hier richtig, weil der Bestand ausschliesslich eine
     * ZUSAETZLICHE Erlaubnis ist - der gewoehnliche Korrekturpfad laeuft
     * unveraendert weiter. Der eigene Grund ist wichtig, damit ein spaeteres
     * Nullfenster nicht faelschlich dem Guard zugeschrieben wird.
     */
    @Test
    fun `ein unklarer Restart-Zustand gibt keinen Kredit`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 200.0)).state
        val r = EvidenceStock.step(s, eingabe(2, 220.0, persistedStateKnown = false))
        assertEquals(EvidenceStock.NoInflow.EVIDENCE_STATE_UNKNOWN, r.noInflow)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
    }

    // ---- Monotonie des Abgabestands ---------------------------------------

    /** GLEICHER WERT IST IDEMPOTENT - ein Replay oder ein zweiter Zyklus auf
     *  demselben Stand darf nichts veraendern ausser dem Verfall. */
    @Test
    fun `derselbe Abgabestand ist idempotent`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 200.0, committedU = 0.20)).state
        val r = EvidenceStock.step(s, eingabe(1, 200.0, committedU = 0.20))
        assertTrue(r.noInflow != EvidenceStock.NoInflow.EVIDENCE_STATE_UNKNOWN)
        assertEquals(s.stockMgdl, r.state.stockMgdl, 1e-9)
    }

    /**
     * EIN KLEINERER STAND IST KEIN NEGATIVER ABZUG, SONDERN EIN UNBEKANNTER
     * ZUSTAND.
     *
     * Er kann nur aus einem verlorenen oder vertauschten Zustand kommen. Ihn
     * als "kein Abzug" zu schlucken waere die falsche Richtung: dann stuende
     * Bestand zur Verfuegung, dessen Bezahlung gerade vergessen wurde.
     */
    @Test
    fun `ein kleinerer Abgabestand sperrt den Kredit`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 200.0, committedU = 0.40)).state
        val r = EvidenceStock.step(s, eingabe(2, 220.0, committedU = 0.20))
        assertEquals(EvidenceStock.NoInflow.EVIDENCE_STATE_UNKNOWN, r.noInflow)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
    }

    /** Eine NEUE Episode startet den Zaehler bei null - dort ist ein
     *  kleinerer Wert normal und kein Fehler. */
    @Test
    fun `eine neue Episode startet den Abgabezaehler neu`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = EvidenceStock.step(s, eingabe(1, 200.0, committedU = 0.40)).state
        val r = EvidenceStock.step(s, eingabe(2, 200.0, committedU = 0.05, episodeId = 2L))
        assertTrue(r.noInflow != EvidenceStock.NoInflow.EVIDENCE_STATE_UNKNOWN)
        assertEquals(0.05, r.state.lastCommittedU, 1e-9)
    }

    /**
     * EINE ALTE EPISODE LEBT NICHT WIEDER AUF. Die Identitaet ist monoton
     * (in der Praxis der Markerzeitpunkt); springt sie rueckwaerts, ist der
     * persistierte Zustand nicht der, den wir zu haben glauben - und ein
     * frischer Vier-Stunden-Deckel auf einer alten Episode waere das
     * Gegenteil eines Deckels.
     */
    @Test
    fun `eine rueckwaerts springende Episode sperrt den Kredit`() {
        var s = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0, episodeId = 5L)).state
        s = EvidenceStock.step(s, eingabe(1, 200.0, episodeId = 5L)).state
        val r = EvidenceStock.step(s, eingabe(2, 220.0, episodeId = 3L))
        assertEquals(EvidenceStock.NoInflow.EVIDENCE_STATE_UNKNOWN, r.noInflow)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
    }
}
