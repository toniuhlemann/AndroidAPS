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

    /** EINE Konfiguration fuer alle Faelle - dieselbe, die auch der Zyklus
     *  bekaeme. Seit dem 12.08. hat `step` keinen Default mehr, damit ein
     *  Replay nicht versehentlich gegen eine andere rechnet. */
    private val CFG = EvidenceStock.Config()

    /**
     * EINE gemeinsame bereinigte Reihe fuer alle Punkte eines Tests - genau
     * wie eine `adjust()`-Ausgabe. Das Intervall entsteht daraus, nicht aus
     * zwei Werten verschiedener Zyklen.
     */
    private val reihe = HashMap<Long, Double>()

    /**
     * EIN SEGMENTBRUCH heisst jetzt: der Anker steht nicht mehr in der
     * aktuellen `adjust()`-Ausgabe. Genau so sieht ihn der Kern - er kennt
     * keinen Segmentanker mehr, sondern nur noch ein bildbares oder nicht
     * bildbares Intervall.
     */
    private fun bruch() = reihe.clear()

    private fun schritt(prev: EvidenceStock.State, input: EvidenceStock.Input): EvidenceStock.Result {
        // Die Reihe geht durch die ECHTE Bereinigung - Aktivitaet 0, damit
        // `adjusted == q1` bleibt und die Testzahlen lesbar sind. Der
        // Intervalltyp ist nicht mehr von aussen baubar, und das ist der
        // Punkt: auch der Test muss ueber `adjust()` gehen.
        val serie = app.aaps.fuse.core.signal.BgiAdjustedSeries.adjust(
            reihe.keys.sorted().map {
                app.aaps.fuse.core.signal.BgiAdjustedSeries.Sample(it, reihe.getValue(it), 0.0, ISF)
            }
        )
        val iv = app.aaps.fuse.core.signal.BgiAdjustedSeries.AdjustedInterval.of(serie, prev.lastAcceptedTs)
            ?.takeIf { it.toSourceTs == input.sourceTs }
        return EvidenceStock.step(prev, input.copy(interval = iv), CFG)
    }

    private fun eingabe(
        minute: Int,
        adjusted: Double,
        driveLower: Double? = 1.0,
        committedU: Double = 0.0,
        healthReady: Boolean = true,
        measuredLow: Boolean = false,
        episodeId: Long = 1L,
        persistedStateKnown: Boolean = true,
        creditRevoked: Boolean = false,
    ) = eintragen(T0 + minute * 60_000L, adjusted).let { EvidenceStock.Input(
        nowMs = T0 + minute * 60_000L,
        sourceTs = T0 + minute * 60_000L,
        driveLowerMgdlPerMin = driveLower,
        healthReady = healthReady,
        measuredLow = measuredLow,
        episodeId = episodeId,
        episodeCommittedU = committedU,
        isfMgdlPerU = ISF,
        persistedStateKnown = persistedStateKnown,
        creditRevoked = creditRevoked,
        interval = null,
    ) }

    private fun eintragen(ts: Long, adjusted: Double) { reihe[ts] = adjusted }

    /** Erster Zyklus: es gibt keinen Bezugspunkt, also keinen Zufluss. Der
     *  Bestand entsteht frueestens beim ZWEITEN Messpunkt. */
    @Test
    fun `der erste Punkt liefert nur den Bezug, keinen Zufluss`() {
        val r = schritt(EvidenceStock.State(), eingabe(0, 100.0))
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.SEGMENT_BREAK, r.noInflow)
        assertEquals(T0, r.state.lastAcceptedTs)
    }

    /** Der Zuwachs der bereinigten Reihe IST die Stoerung - Tonis Herleitung. */
    @Test
    fun `der Zuwachs der bereinigten Reihe wird zum Bestand`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        val r = schritt(s, eingabe(1, 103.0))
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 103.0)).state
        val vorher = s.stockMgdl
        // Gleicher sourceTs, gleicher Wert - ein zweiter Reglerzyklus auf
        // demselben CGM-Punkt.
        val r = schritt(s, eingabe(1, 103.0))
        assertEquals(0.0, r.inflowMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.NO_NEW_SAMPLE, r.noInflow)
        assertTrue(r.state.stockMgdl <= vorher + 1e-9, "der Bestand darf nicht wachsen")
    }

    /** Zugesagtes Insulin verbraucht den Bestand sofort - nicht erst, wenn das
     *  Treatment sichtbar wird (gemessene Latenz p90 56 s, max 854 s). */
    @Test
    fun `zugesagtes Insulin verbraucht den Bestand sofort`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 130.0)).state          // +30 mg/dl
        val vorher = s.stockMgdl
        val r = schritt(s, eingabe(2, 130.0, committedU = 0.20))
        // 0,20 U x 90 = 18 mg/dl Abzug
        assertTrue(r.state.stockMgdl < vorher - 15.0, "18 mg/dl muessen abgezogen sein: ${r.state.stockMgdl}")
    }

    /** GEGENPROBE zur Bilanz: ohne Abzug wuerde der Bruttobestand nie
     *  verbraucht und koennte wiederholt lizenzieren. */
    @Test
    fun `ohne Abgabe bleibt der Bestand bestehen`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 130.0)).state
        val r = schritt(s, eingabe(2, 130.0))
        assertTrue(r.state.stockMgdl > 0.0)
    }

    /** Ein gemessenes Tief widerruft SOFORT und VOLLSTAENDIG. */
    @Test
    fun `ein gemessenes Tief loescht den Bestand`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 140.0)).state
        val r = schritt(s, eingabe(2, 140.0, measuredLow = true))
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
    }

    /** Signalfehler ebenso: ein Bestand ist eine Behauptung ueber die
     *  naechsten Minuten, und ohne gesundes Signal traegt sie nicht. */
    @Test
    fun `ein ungesundes Signal loescht den Bestand`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 140.0)).state
        assertEquals(0.0, schritt(s, eingabe(2, 140.0, healthReady = false)).state.stockMgdl, 1e-9)
    }

    /** Ein Segmentbruch macht die Differenz bedeutungslos - `cumulativeBgi`
     *  startet je Segment neu bei 0. Dann wird ausgesetzt, nicht geschaetzt. */
    @Test
    fun `ueber einen Segmentbruch fliesst nichts zu`() {
        val s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        bruch()
        val r = schritt(s, eingabe(1, 900.0))
        assertEquals(0.0, r.inflowMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.SEGMENT_BREAK, r.noInflow)
    }

    /** Das Evidenztor: ohne positive konservative Untergrenze entsteht kein
     *  Bestand, auch wenn die Reihe steigt. */
    @Test
    fun `ohne positiven Antrieb entsteht kein Bestand`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        val r = schritt(s, eingabe(1, 130.0, driveLower = -0.2))
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 120.0)).state
        val nachAnstieg = s.stockMgdl
        val r = schritt(s, eingabe(2, 115.0))     // -5
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 140.0)).state
        val r = schritt(s, eingabe(EvidenceStock.Config().maxEpisodeMin + 1, 200.0))
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.EPISODE_EXPIRED, r.noInflow)
    }

    /** Ohne Episode gibt es nichts - der Bestand ist an die Mahlzeit gebunden,
     *  nicht an jede steigende Kurve. */
    @Test
    fun `ohne Episode entsteht kein Bestand`() {
        val r = schritt(EvidenceStock.State(), eingabe(0, 100.0, episodeId = 0L))
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 130.0)).state
        // EIN ZYKLUS SPAETER: der Zufluss von Minute 1 ist versiegelt und
        // damit kreditfaehig (Stufe 3, Ein-Zyklus-Verzug).
        val r = schritt(s, eingabe(2, 130.0))
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 140.0)).state
        val vorher = s.stockMgdl
        // Flache Reihe ueber den halben Verfallszeitraum.
        s = schritt(s, eingabe(1 + EvidenceStock.Config().decayMin / 2, 140.0)).state
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 160.0)).state
        s = schritt(s, eingabe(2, 160.0, committedU = 0.20)).state
        val nachAbzug = s.stockMgdl
        // Derselbe kumulative Stand, drei Zyklen lang.
        repeat(3) { i -> s = schritt(s, eingabe(3 + i, 160.0, committedU = 0.20)).state }
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 200.0)).state
        s = schritt(s, eingabe(2, 200.0, committedU = 0.20)).state
        val vorher = s.stockMgdl
        val r = schritt(s, eingabe(3, 200.0, committedU = 0.40))
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 120.0)).state
        // Tal: dieselbe Episode, aber lange nichts.
        s = schritt(s, eingabe(100, 120.0)).state
        // Zweite Welle, dieselbe episodeId - kurz VOR dem Deckel. Sie wird
        // wieder ACTIVE, ohne dass Uhr oder Budget neu starten.
        val deckel = EvidenceStock.Config().maxEpisodeMin
        var vorDeckel = schritt(s, eingabe(deckel - 10, 140.0))
        assertTrue(vorDeckel.noInflow != EvidenceStock.NoInflow.EPISODE_EXPIRED)
        vorDeckel = schritt(vorDeckel.state, eingabe(deckel - 9, 140.0))
        assertEquals(EvidenceStock.Phase.ACTIVE, vorDeckel.phase, "neue Evidenz weckt dieselbe Episode")
        // Und kurz danach ist Schluss, gerechnet ab MINUTE 0, nicht ab 100.
        val nachDeckel = schritt(vorDeckel.state, eingabe(deckel + 5, 160.0))
        assertEquals(EvidenceStock.NoInflow.EPISODE_EXPIRED, nachDeckel.noInflow)
        assertEquals(EvidenceStock.Phase.EXPIRED, nachDeckel.phase)
    }

    /** Eine ANDERE Episode erbt nichts - weder Bestand noch Uhr noch
     *  Abgabestand. */
    @Test
    fun `eine neue Episode erbt nichts`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 200.0)).state
        assertTrue(s.stockMgdl > 0.0)
        val r = schritt(s, eingabe(2, 200.0, episodeId = 2L))
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 200.0)).state
        val vorLuecke = s.stockMgdl
        // 6 Minuten Luecke, danach neues Segment.
        bruch()
        val r = schritt(s, eingabe(7, 300.0))
        assertEquals(EvidenceStock.NoInflow.SEGMENT_BREAK, r.noInflow)
        assertEquals(0.0, r.inflowMgdl, 1e-9, "ueber die Luecke keine Differenz")
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9, "Ausgabe waehrend des Bruchs gesperrt")
        assertTrue(
            r.state.stockMgdl < vorLuecke * 0.4,
            "der Bestand muss waehrend der Luecke abgebaut haben: $vorLuecke -> ${r.state.stockMgdl}",
        )
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 200.0)).state
        val r = schritt(s, eingabe(2, 220.0, persistedStateKnown = false))
        assertEquals(EvidenceStock.NoInflow.EVIDENCE_STATE_UNKNOWN, r.noInflow)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
    }

    // ---- Monotonie des Abgabestands ---------------------------------------

    /** GLEICHER WERT IST IDEMPOTENT - ein Replay oder ein zweiter Zyklus auf
     *  demselben Stand darf nichts veraendern ausser dem Verfall. */
    @Test
    fun `derselbe Abgabestand ist idempotent`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 200.0, committedU = 0.20)).state
        val r = schritt(s, eingabe(1, 200.0, committedU = 0.20))
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 200.0, committedU = 0.40)).state
        val r = schritt(s, eingabe(2, 220.0, committedU = 0.20))
        assertEquals(EvidenceStock.NoInflow.EVIDENCE_STATE_UNKNOWN, r.noInflow)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
    }

    /** Eine NEUE Episode startet den Zaehler bei null - dort ist ein
     *  kleinerer Wert normal und kein Fehler. */
    @Test
    fun `eine neue Episode startet den Abgabezaehler neu`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 200.0, committedU = 0.40)).state
        val r = schritt(s, eingabe(2, 200.0, committedU = 0.05, episodeId = 2L))
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
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0, episodeId = 5L)).state
        s = schritt(s, eingabe(1, 200.0, episodeId = 5L)).state
        val r = schritt(s, eingabe(2, 220.0, episodeId = 3L))
        assertEquals(EvidenceStock.NoInflow.EVIDENCE_STATE_UNKNOWN, r.noInflow)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
    }
    // ---- Die dynamische Laufzeit: ACTIVE / DORMANT / EXPIRED --------------

    /**
     * DER SNACK ERLEDIGT SICH SELBST.
     *
     * Er war der Grund gegen eine feste Laufzeit: nach ein bis zwei Stunden
     * ist er durch, waehrend die Uhr noch stundenlang weiterlief und
     * lizenzierte. Ohne Zufluss traegt der Verfall den Bestand in wenigen
     * Minuten auf 0 - die Episode faellt von allein auf DORMANT, und niemand
     * muss eine Dauer schaetzen.
     */
    @Test
    fun `ohne Zufluss faellt die Episode von selbst auf DORMANT`() {
        var r = schritt(EvidenceStock.State(), eingabe(0, 100.0))
        r = schritt(r.state, eingabe(1, 130.0))
        assertEquals(EvidenceStock.Phase.PENDING_SEAL, r.phase, "frischer Zufluss ist noch nicht versiegelt")
        r = schritt(r.state, eingabe(2, 130.0))
        assertEquals(EvidenceStock.Phase.ACTIVE, r.phase, "einen Zyklus spaeter aktiv")

        // Flach weiter - kein Anstieg mehr, nur Verfall.
        var t = 2
        repeat(30) { r = schritt(r.state, eingabe(t++, 130.0)) }

        assertEquals(EvidenceStock.Phase.DORMANT, r.phase)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9, "kein Kredit ohne Bestand")
        assertTrue(r.state.episodeId > 0L, "die Episode bleibt erinnerbar")
    }

    /**
     * DIE ZWEITE WELLE IN STUNDE VIER weckt dieselbe Episode wieder.
     *
     * Das ist die andere Haelfte des Arguments: eine fett-/proteinreiche
     * Mahlzeit kann nach einem Tal erneut Bedarf erzeugen. Mit einer festen
     * Laufzeit waere sie entweder abgeschnitten oder die ganze Zeit ueber
     * unnoetig lizenziert gewesen.
     */
    @Test
    fun `neue Evidenz nach einem Tal weckt dieselbe Episode`() {
        var r = schritt(EvidenceStock.State(), eingabe(0, 100.0))
        r = schritt(r.state, eingabe(1, 130.0))
        val start = r.state.episodeStartTs

        // Langes Tal, flach - bis der Verfall den Bestand unter die Schwelle
        // getragen hat. 20 Minuten reichen dafuer NICHT (0,875^20 x 30 = 2,1
        // mg/dl); das war der erste Anlauf dieses Tests.
        var t = 2
        repeat(32) { r = schritt(r.state, eingabe(t++, 130.0)) }
        assertEquals(EvidenceStock.Phase.DORMANT, r.phase)

        // Stunde vier, neuer Anstieg - und ein Zyklus, bis er versiegelt ist.
        r = schritt(r.state, eingabe(220, 145.0))
        r = schritt(r.state, eingabe(221, 145.0))

        assertEquals(EvidenceStock.Phase.ACTIVE, r.phase)
        assertEquals(start, r.state.episodeStartTs, "die Uhr startet NICHT neu")
    }

    /**
     * POSITIVES `r` ALLEIN VERLAENGERT NICHTS - der Deckel ist ein Notaus.
     *
     * Eine schlechte Infusionsstelle, Gegenregulation oder Sensordrift sehen
     * wie Stoerung aus. Duerfte der Zufluss die Laufzeit verlaengern, hielte
     * jede davon die Mahlzeitenepisode beliebig offen.
     */
    @Test
    fun `dauernder Zufluss verlaengert die Episode nicht ueber den Deckel`() {
        var r = schritt(EvidenceStock.State(), eingabe(0, 100.0))
        var bg = 100.0
        var t = 1
        val deckel = EvidenceStock.Config().maxEpisodeMin
        // Ununterbrochen steigend bis ueber den Deckel hinaus.
        while (t <= deckel + 5) { bg += 1.0; r = schritt(r.state, eingabe(t, bg)); t += 5 }

        assertEquals(EvidenceStock.Phase.EXPIRED, r.phase)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
    }

    /** Und EXPIRED bleibt EXPIRED - kein Wiederaufleben durch neue Evidenz. */
    @Test
    fun `eine abgelaufene Episode lebt nicht wieder auf`() {
        var r = schritt(EvidenceStock.State(), eingabe(0, 100.0))
        val deckel = EvidenceStock.Config().maxEpisodeMin
        r = schritt(r.state, eingabe(deckel + 1, 200.0))
        assertEquals(EvidenceStock.Phase.EXPIRED, r.phase)

        r = schritt(r.state, eingabe(deckel + 2, 260.0))
        assertEquals(EvidenceStock.Phase.EXPIRED, r.phase, "auch ein steiler Anstieg weckt sie nicht")
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
    }
    /**
     * DIE SECHS PHASEN MUESSEN AUSEINANDERGEHALTEN WERDEN (Toni 12.08.).
     *
     * Der erste Wurf nannte alles ausser ACTIVE und EXPIRED schlicht DORMANT -
     * auch fehlende Episode, gemessenes Tief, Signalfehler, Segmentbruch und
     * unklare Persistenz. Fuer die Anzeige ist das eine Luege: DORMANT heisst
     * "die Mahlzeit ist gerade durch". Wer das liest, waehrend in Wahrheit das
     * Signal fehlt, zieht den falschen Schluss.
     */
    @Test
    fun `ohne Episode ist die Phase NONE`() {
        val r = schritt(EvidenceStock.State(), eingabe(1, 100.0, episodeId = 0L))
        assertEquals(EvidenceStock.Phase.NONE, r.phase)
    }

    @Test
    fun `ein gemessenes Tief sperrt statt einzuschlafen`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 140.0)).state
        val r = schritt(s, eingabe(2, 140.0, measuredLow = true))
        assertEquals(EvidenceStock.Phase.SUSPENDED, r.phase)
    }

    @Test
    fun `ein ungesundes Signal sperrt statt einzuschlafen`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 140.0)).state
        val r = schritt(s, eingabe(2, 140.0, healthReady = false))
        assertEquals(EvidenceStock.Phase.SUSPENDED, r.phase)
    }

    @Test
    fun `ein Segmentbruch sperrt statt einzuschlafen`() {
        val s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        bruch()
        val r = schritt(s, eingabe(1, 140.0))
        assertEquals(EvidenceStock.Phase.SUSPENDED, r.phase)
    }

    /** Unklare Buchfuehrung ist etwas anderes als eine beendete Mahlzeit. */
    @Test
    fun `unbekannte Persistenz meldet UNKNOWN`() {
        val r = schritt(EvidenceStock.State(), eingabe(1, 100.0, persistedStateKnown = false))
        assertEquals(EvidenceStock.Phase.UNKNOWN, r.phase)
    }

    /** Ebenso ein sinkender kumulativer Abgabestand - das kann nur heissen,
     *  dass Zustand verlorenging. */
    @Test
    fun `ein sinkender Abgabestand meldet UNKNOWN`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 140.0, committedU = 0.30)).state
        val r = schritt(s, eingabe(2, 140.0, committedU = 0.10))
        assertEquals(EvidenceStock.Phase.UNKNOWN, r.phase)
    }
    // ---- Nach einer Sperre wird NICHTS nachgeholt -------------------------

    /**
     * DER TEUERSTE FEHLER DIESES KERNS, gefunden von Toni am 12.08.
     *
     * Bei Tief oder Signalfehler wurde der Bestand geloescht, `lastAcceptedTs`
     * und `lastAdjusted` blieben aber stehen. Der erste gesunde Zyklus danach
     * haette die GANZE Differenz ueber die Sperrzeit als frischen Zufluss
     * verbucht - also genau die Evidenz nachgeholt, die wir eben fuer
     * ungueltig erklaert hatten. Nach einem gemessenen Tief ist das die
     * teuerste Richtung, die es gibt.
     */
    @Test
    fun `nach einem Tief holt der erste gesunde Punkt nichts nach`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 120.0)).state
        // Tief - und waehrenddessen steigt die bereinigte Reihe kraeftig.
        s = schritt(s, eingabe(2, 120.0, measuredLow = true)).state
        assertTrue(s.rebaseRequired, "die Messbasis gilt als ungueltig")

        // Erster gesunder Punkt, 60 mg/dl hoeher als vor der Sperre.
        val basis = schritt(s, eingabe(20, 180.0))
        assertEquals(0.0, basis.inflowMgdl, 1e-9, "kein Nachholen")
        assertEquals(0.0, basis.creditMgdlPerMin, 1e-9)
        assertEquals(EvidenceStock.NoInflow.REBASE_AFTER_SUSPEND, basis.noInflow)
        assertEquals(EvidenceStock.Phase.SUSPENDED, basis.phase)

        // Erst der DARAUFFOLGENDE Punkt erzeugt wieder Evidenz - und nur
        // seinen eigenen Zuwachs.
        val danach = schritt(basis.state, eingabe(21, 183.0))
        assertEquals(3.0, danach.inflowMgdl, 1e-9)
    }

    /** Dasselbe fuer den Signalfehler - eine Sperre ist eine Sperre. */
    @Test
    fun `nach einem Signalfehler holt der erste gesunde Punkt nichts nach`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 120.0)).state
        s = schritt(s, eingabe(2, 120.0, healthReady = false)).state
        val basis = schritt(s, eingabe(20, 180.0))
        assertEquals(0.0, basis.inflowMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.REBASE_AFTER_SUSPEND, basis.noInflow)
    }

    // ---- Der Widerruf erreicht den Kern ----------------------------------

    /**
     * WIDERRUF SPERRT DEN KREDIT AUCH BEI VOLLEM BESTAND.
     *
     * Er wurde persistiert und exportiert, war aber kein EINGANG des Kerns
     * (Toni 12.08.). Mit positivem Bestand haette der Kern weiter ACTIVE
     * gemeldet und Kredit geliefert, obwohl der Nutzer den Marker
     * zurueckgenommen hat - der Widerrufsvertrag waere genau dort
     * wirkungslos gewesen, wo er zaehlt.
     */
    @Test
    fun `ein Widerruf sperrt den Kredit trotz Bestand`() {
        var r = schritt(EvidenceStock.State(), eingabe(0, 100.0))
        r = schritt(r.state, eingabe(1, 140.0))
        r = schritt(r.state, eingabe(2, 140.0))
        assertEquals(EvidenceStock.Phase.ACTIVE, r.phase)
        assertTrue(r.creditMgdlPerMin > 0.0)

        val w = schritt(r.state, eingabe(3, 145.0, creditRevoked = true))

        assertEquals(EvidenceStock.Phase.SUSPENDED, w.phase)
        assertEquals(EvidenceStock.NoInflow.CREDIT_REVOKED, w.noInflow)
        assertEquals(0.0, w.creditMgdlPerMin, 1e-9)
        assertEquals(0.0, w.inflowMgdl, 1e-9)
    }

    /** Die Buchfuehrung laeuft im Widerruf WEITER - sonst wuerde derselbe
     *  kumulative Abgabestand danach ein zweites Mal abgezogen. */
    @Test
    fun `im Widerruf laeuft die Buchfuehrung weiter`() {
        var r = schritt(EvidenceStock.State(), eingabe(0, 100.0))
        r = schritt(r.state, eingabe(1, 140.0))
        val w = schritt(r.state, eingabe(2, 145.0, creditRevoked = true, committedU = 0.30))
        assertEquals(0.30, w.state.lastCommittedU, 1e-9)
        assertEquals(r.state.episodeId, w.state.episodeId, "die Episode bleibt")
    }

    /** Und beim erneuten Armen wird der Widerrufszeitraum NICHT nachgeholt. */
    @Test
    fun `nach dem Widerruf setzt der erste Punkt nur die Basis`() {
        var r = schritt(EvidenceStock.State(), eingabe(0, 100.0))
        r = schritt(r.state, eingabe(1, 140.0))
        r = schritt(r.state, eingabe(2, 145.0, creditRevoked = true))
        // Waehrend des Widerrufs steigt die Reihe weiter.
        r = schritt(r.state, eingabe(10, 200.0, creditRevoked = true))

        val wieder = schritt(r.state, eingabe(11, 205.0))
        assertEquals(0.0, wieder.inflowMgdl, 1e-9, "kein Nachholen des Widerrufszeitraums")
        assertEquals(EvidenceStock.NoInflow.REBASE_AFTER_SUSPEND, wieder.noInflow)
    }
    // ---- Unmoegliche persistierte Zustaende (Toni 12.08.) ----------------

    /**
     * BESTAND OHNE EPISODENSTART SCHENKT EINEN FRISCHEN DECKEL.
     *
     * Der Kern setzt den Start sonst auf `now` - ein Bestand aus einer sechs
     * Stunden alten Episode bekaeme damit den vollen Sicherheitsdeckel neu.
     * Der Codec sieht das nicht: beide Felder sind fuer sich gueltig.
     */
    @Test
    fun `Bestand ohne Episodenstart gilt als unmoeglich`() {
        val kaputt = EvidenceStock.State(
            stockMgdl = 30.0, episodeId = 1L, episodeStartTs = 0L,
            lastAcceptedTs = T0, lastDecayTs = T0,
        )
        val r = schritt(kaputt, eingabe(1, 130.0))
        assertEquals(EvidenceStock.Phase.UNKNOWN, r.phase)
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
        assertTrue(r.state.rebaseRequired, "und die Messbasis gilt als ungueltig")
    }

    /** Ohne Messbasis kann ein Bestand nicht entstanden sein - er wuerde
     *  sofort Kredit erzeugen, ohne dass je ein Punkt verbucht wurde. */
    @Test
    fun `Bestand ohne Messbasis gilt als unmoeglich`() {
        val kaputt = EvidenceStock.State(
            stockMgdl = 30.0, episodeId = 1L, episodeStartTs = T0,
            lastAcceptedTs = 0L, lastDecayTs = T0,
        )
        assertEquals(EvidenceStock.Phase.UNKNOWN, schritt(kaputt, eingabe(1, 130.0)).phase)
    }

    /**
     * EIN ZEITSTEMPEL AUS DER ZUKUNFT FRIERT DEN VERFALL EIN.
     *
     * `dtMin` klemmt auf 0, der Bestand altert bis dahin nicht - eine
     * Behauptung, die sich selbst am Leben haelt.
     */
    @Test
    fun `ein Verfallszeitpunkt aus der Zukunft gilt als unmoeglich`() {
        val kaputt = EvidenceStock.State(
            stockMgdl = 30.0, episodeId = 1L, episodeStartTs = T0,
            lastAcceptedTs = T0,
            lastDecayTs = T0 + 120 * 60_000L,
        )
        assertEquals(EvidenceStock.Phase.UNKNOWN, schritt(kaputt, eingabe(1, 130.0)).phase)
    }

    /**
     * DIE HARTE PLAUSIBILITAETSGRENZE.
     *
     * "endlich und >= 0" laesst 5.000 mg/dl durch - nach dem Neustart waere
     * das sofort ein Kredit von 166 mg/dl/min. Der Bestand strebt bei
     * konstantem Zufluss gegen Zuflussrate x decayMin, also rund 40 mg/dl bei
     * der hoechsten je gemessenen Stoerung.
     */
    @Test
    fun `ein absurd grosser Bestand gilt als unmoeglich`() {
        val kaputt = EvidenceStock.State(
            stockMgdl = CFG.maxStockMgdl + 1.0, episodeId = 1L, episodeStartTs = T0,
            lastAcceptedTs = T0, lastDecayTs = T0,
        )
        val r = schritt(kaputt, eingabe(1, 130.0))
        assertEquals(EvidenceStock.Phase.UNKNOWN, r.phase)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9, "nicht geklemmt, sondern verworfen")
    }

    /** GEGENPROBE: ein vollstaendiger, plausibler Zustand laeuft normal
     *  weiter - sonst waere die Pruefung nur ein Totalausfall. */
    @Test
    fun `ein vollstaendiger persistierter Zustand traegt weiter`() {
        // Der Anker muss in der Ausgabe stehen - sonst gaebe es kein
        // Intervall, und der Kern setzte nur die Basis neu.
        eintragen(T0, 130.0)
        val gut = EvidenceStock.State(
            stockMgdl = 20.0, episodeId = 1L, episodeStartTs = T0,
            lastAcceptedTs = T0, lastDecayTs = T0,
        )
        val r = schritt(gut, eingabe(1, 133.0))
        assertEquals(EvidenceStock.Phase.ACTIVE, r.phase)
        assertTrue(r.creditMgdlPerMin > 0.0)
        assertEquals(3.0, r.inflowMgdl, 1e-9)
    }
    // ---- Stufe 3: der Ein-Zyklus-Verzug ----------------------------------

    /**
     * FRISCHER ZUFLUSS IST NOCH KEIN KREDIT.
     *
     * Der Zufluss dieses Zyklus steht noch nicht auf Platte. Ihn sofort
     * auszuschuetten hiesse, Insulin auf eine Messinformation zu geben, die
     * ein Stromausfall eine Sekunde spaeter spurlos verschwinden liesse -
     * danach waere Insulin unterwegs, das keine Buchung mehr hat.
     */
    @Test
    fun `der Zufluss eines Zyklus ist in diesem Zyklus noch kein Kredit`() {
        val s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        val r = schritt(s, eingabe(1, 130.0))

        assertEquals(30.0, r.inflowMgdl, 1e-9, "zugeflossen ist er")
        assertTrue(r.state.stockMgdl > 0.0, "und im Bestand steht er")
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9, "kreditfaehig ist er noch nicht")
        assertEquals(EvidenceStock.Phase.PENDING_SEAL, r.phase, "aber auch nicht schlafend")
    }

    /** Und im naechsten Zyklus IST er es - der Verzug ist eine Minute, kein
     *  Verlust. */
    @Test
    fun `im Folgezyklus ist derselbe Zufluss kreditfaehig`() {
        var s = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        s = schritt(s, eingabe(1, 130.0)).state
        val r = schritt(s, eingabe(2, 130.0))

        assertEquals(0.0, r.inflowMgdl, 1e-9, "nichts Neues")
        assertTrue(r.creditMgdlPerMin > 0.0, "aber der versiegelte Bestand traegt")
        assertEquals(EvidenceStock.Phase.ACTIVE, r.phase)
    }

    /**
     * EIN GELADENER BESTAND IST PER DEFINITION VERSIEGELT.
     *
     * Sonst waere nach jedem Neustart eine Minute lang kein Kredit moeglich,
     * obwohl der Bestand nachweislich auf Platte stand - der Verzug soll
     * ungesicherte Evidenz aufhalten, nicht gesicherte.
     */
    @Test
    fun `ein geladener Bestand ist sofort kreditfaehig`() {
        eintragen(T0, 130.0)
        val geladen = EvidenceStock.State(
            stockMgdl = 20.0, episodeId = 1L, episodeStartTs = T0,
            lastAcceptedTs = T0, lastDecayTs = T0,
        )
        val r = schritt(geladen, eingabe(1, 130.0))
        assertTrue(r.creditMgdlPerMin > 0.0)
        assertEquals(EvidenceStock.Phase.ACTIVE, r.phase)
    }
    // ---- Der Intervallvertrag (Toni 12.08.) ------------------------------

    /**
     * DERSELBE `sourceTs` FLIESST KEIN ZWEITES MAL ZU.
     *
     * Die Exactly-once-Regel ist jetzt im KERN pruefbar statt nur eine
     * Absprache: nach der Buchung rueckt der Anker auf `toSourceTs`, und
     * dasselbe Intervall passt danach nicht mehr.
     */
    @Test
    fun `dasselbe Intervall wird nicht zweimal verbucht`() {
        val basis = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        val erst = schritt(basis, eingabe(1, 130.0))
        assertEquals(30.0, erst.inflowMgdl, 1e-9)

        // Dasselbe Intervall ein zweites Mal - so weit ein Aufrufer es
        // ueberhaupt noch herstellen kann. Der Konstruktor ist privat, also
        // muss auch dieser Versuch durch die Fabrik: sie baut aus derselben
        // Reihe dasselbe Intervall (T0 -> T0+1min) noch einmal.
        val serie = app.aaps.fuse.core.signal.BgiAdjustedSeries.adjust(
            reihe.keys.sorted().map {
                app.aaps.fuse.core.signal.BgiAdjustedSeries.Sample(it, reihe.getValue(it), 0.0, ISF)
            }
        )
        val altesIntervall = app.aaps.fuse.core.signal.BgiAdjustedSeries.AdjustedInterval.of(serie, T0)
        val nochmal = EvidenceStock.step(erst.state, eingabe(1, 130.0).copy(interval = altesIntervall), CFG)
        assertEquals(0.0, nochmal.inflowMgdl, 1e-9) { "der Anker passt nicht mehr" }
    }

    /** MEHRERE neue Punkte auf einmal: EIN gemeinsames Delta, genau einmal -
     *  nicht je Punkt eines. */
    @Test
    fun `mehrere neue Messpunkte fliessen als ein Delta zu`() {
        val basis = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        eintragen(T0 + 60_000L, 110.0)
        eintragen(T0 + 120_000L, 125.0)
        val r = schritt(basis, eingabe(3, 140.0))
        assertEquals(40.0, r.inflowMgdl, 1e-9) { "100 -> 140 ueber drei Punkte" }
    }

    /**
     * EINE KONSTANTE VERSCHIEBUNG DER GANZEN REIHE AENDERT NICHTS.
     *
     * Das ist der eigentliche Befund vom 12.08. in Testform: `adjust()` setzt
     * `cumulativeBgi` am wandernden Fensteranfang auf 0, die Reihe ist also
     * nur bis auf eine Konstante bestimmt. Ein Zufluss, der sich mit dieser
     * Konstante aendert, misst den Bezugspunkt statt der Stoerung.
     */
    @Test
    fun `eine konstante Verschiebung der Reihe aendert das Delta nicht`() {
        fun lauf(offset: Double): Double {
            reihe.clear()
            val basis = schritt(EvidenceStock.State(), eingabe(0, 100.0 + offset)).state
            return schritt(basis, eingabe(1, 130.0 + offset)).inflowMgdl
        }
        assertEquals(lauf(0.0), lauf(-5_000.0), 1e-9)
        assertEquals(lauf(0.0), lauf(+5_000.0), 1e-9)
        assertEquals(30.0, lauf(0.0), 1e-9)
    }

    /**
     * FEHLT DER ANKER, WIRD NICHT NACHGEHOLT.
     *
     * Nach einer Luecke steht der alte Anker nicht mehr in der Ausgabe. Die
     * Differenz ueber die Luecke waere die groesste Zahl des Tages - und
     * genau die darf nicht als frische Evidenz erscheinen.
     */
    @Test
    fun `nach einer Luecke wird nichts nachgeholt`() {
        val basis = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        bruch()
        val r = schritt(basis, eingabe(30, 400.0))
        assertEquals(0.0, r.inflowMgdl, 1e-9)
        assertEquals(EvidenceStock.NoInflow.SEGMENT_BREAK, r.noInflow)
        assertEquals(T0 + 30 * 60_000L, r.state.lastAcceptedTs) { "nur Rebase" }
    }

    /**
     * INSULINWIRKUNG OHNE STOERUNG ERZEUGT KEINEN BESTAND.
     *
     * Die bereinigte Reihe ist genau dafuer gebaut: `dq1 = Stoerung -
     * Insulinwirkung`, `dadjusted = dq1 + Insulinwirkung = Stoerung`. Faellt
     * der Zucker allein durch Insulin, ist die BEREINIGTE Reihe flach - der
     * Zufluss also 0, obwohl q1 sinkt.
     *
     * Hier als Eigenschaft der EINGABE geprueft: eine flache bereinigte Reihe
     * darf keinen Bestand erzeugen, egal wie stark das Insulin wirkt.
     */
    @Test
    fun `eine flache bereinigte Reihe erzeugt keinen Bestand`() {
        var r = schritt(EvidenceStock.State(), eingabe(0, 100.0))
        repeat(5) { i -> r = schritt(r.state, eingabe(i + 1, 100.0, committedU = 0.05 * (i + 1))) }
        assertEquals(0.0, r.state.stockMgdl, 1e-9)
        assertEquals(0.0, r.creditMgdlPerMin, 1e-9)
    }

    /**
     * STOERUNG PLUS KOMPENSIERENDES INSULIN: das bereinigte Delta ist
     * weiterhin die STOERUNG.
     *
     * Der Zucker steht still - q1 flach -, weil Mahlzeit und Insulin sich
     * aufheben. Die bereinigte Reihe steigt trotzdem um die Stoerung, und
     * genau die soll der Bestand sehen. Die Bezahlseite zieht das abgegebene
     * Insulin danach wieder ab.
     */
    @Test
    fun `Stoerung mit kompensierendem Insulin liefert die Stoerung`() {
        val basis = schritt(EvidenceStock.State(), eingabe(0, 100.0)).state
        // 18 mg/dl Stoerung, gleichzeitig 0,20 U abgegeben (= 18 mg/dl bei
        // ISF 90). q1 stuende still; die BEREINIGTE Reihe steigt um 18.
        val r = schritt(basis, eingabe(1, 118.0, committedU = 0.20))
        assertEquals(18.0, r.inflowMgdl, 1e-9) { "die Stoerung, nicht die Netto-Null" }
        // Und die Bezahlung zieht sie wieder ab - unter die Bodenschwelle.
        assertEquals(0.0, r.state.stockMgdl, 1e-9) { "18 zugeflossen, 18 bezahlt" }
    }
}
