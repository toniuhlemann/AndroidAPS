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
        episodeActive: Boolean = true,
        segmentStartTs: Long = T0,
    ) = EvidenceStock.Input(
        nowMs = T0 + minute * 60_000L,
        sourceTs = T0 + minute * 60_000L,
        adjusted = adjusted,
        segmentStartTs = segmentStartTs,
        driveLowerMgdlPerMin = driveLower,
        healthReady = healthReady,
        measuredLow = measuredLow,
        episodeActive = episodeActive,
        committedU = committedU,
        isfMgdlPerU = ISF,
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
        val r = EvidenceStock.step(EvidenceStock.State(), eingabe(0, 100.0, episodeActive = false))
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
}
