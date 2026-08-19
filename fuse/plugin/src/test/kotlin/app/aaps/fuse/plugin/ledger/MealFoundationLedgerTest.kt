package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.MealFoundation
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PERSISTENZ UND BUCHFUEHRUNG DES MAHLZEITENFUNDAMENTS (Punkt 7, Toni 18.08.).
 *
 * Zwei Dinge muessen einen Neustart ueberleben, und beide nur GEMEINSAM:
 *
 *   die AUTORISIERUNG  - was beim Markerdruck freigegeben wurde;
 *   die BEZAHLUNG      - was seit der Uebergabe davon geflossen ist.
 *
 * Ginge die Autorisierung verloren, faende ein Neustart mitten in der
 * Mahlzeit sich unarmiert und Prime gaebe das VOLLE Budget erneut frei.
 * Ginge die Bezahlung verloren, faende Phase B eine unbezahlte Mahlzeit vor
 * und lieferte ihr Teilbudget ein zweites Mal. Beide Fehler geben zu VIEL
 * Insulin - deshalb stehen sie in einem Objekt, das nur ganz oder gar nicht
 * gelesen wird.
 */
class MealFoundationLedgerTest {

    private val t0 = 1_786_000_000_000L
    private val BUDGET = 3.0
    private val A_SHARE = 0.75
    private val A_BIS = 15
    private val B_BIS = 60

    private fun autorisierung(
        budget: Double = BUDGET,
        anteil: Double = A_SHARE,
    ) = MealFoundation.arm(
        markerTs = t0, foundationEnabled = true, totalBudgetU = budget, phaseAShare = anteil,
        primeWindowMin = A_BIS, wallCeilingMin = 45, phaseBUntilMin = B_BIS, markerAuthorized = true,
    )

    private fun adapter(
        auth: MealFoundation.Authorization = autorisierung(),
        bezahlt: Double = 0.0,
    ): FuseLedgerAdapter {
        val a = FuseLedgerAdapter()
        a.episodes.foundation = auth
        a.episodes.deliveredSinceHandoverU = bezahlt
        return a
    }

    private fun rundlauf(e: EpisodeBudgets): EpisodeBudgets =
        LedgerCodec.decodeEpisodes(JSONObject(LedgerCodec.encodeEpisodes(e).toString()))

    // ---- Persistenz -------------------------------------------------------

    @Test
    fun `Autorisierung und Bezahlung ueberleben den Rundlauf`() {
        val e = adapter(autorisierung(), bezahlt = 0.40).episodes
        e.foundation = e.foundation.latchIfDue(t0 + A_BIS * 60_000L, 0L)
        val zurueck = rundlauf(e)

        assertTrue(zurueck.foundation.valid)
        assertEquals(t0, zurueck.foundation.armedTs)
        assertEquals(BUDGET, zurueck.foundation.totalBudgetU, 1e-9)
        assertEquals(2.25, zurueck.foundation.phaseABudgetU, 1e-9)
        assertEquals(0.75, zurueck.foundation.phaseBBudgetU, 1e-9)
        assertEquals(A_BIS, zurueck.foundation.pinnedPrimeWindowMin)
        assertEquals(45, zurueck.foundation.pinnedWallCeilingMin)
        assertEquals(t0 + B_BIS * 60_000L, zurueck.foundation.endTs)
        assertEquals(
            t0 + A_BIS * 60_000L, zurueck.foundation.latchedHandoverTs,
            "der gelatchte Anker MUSS mit - sonst wanderte er nach dem Neustart",
        )
        assertEquals(0.40, zurueck.deliveredSinceHandoverU, 1e-9)
    }

    /**
     * DIE MIGRATION ERFINDET KEIN FUNDAMENT.
     *
     * Eine Altdatei mitten in einer Mahlzeit liest sich als "keine
     * Autorisierung" - Prime finanziert wie bisher weiter, also heutiges
     * Verhalten. Wuerde hier aus Budget und Anteil eine Autorisierung
     * nachgebildet, entstuende eine Insulinfreigabe, die niemand erteilt hat.
     */
    @Test
    fun `eine Datei ohne Fundament ergibt keine Autorisierung`() {
        val o = LedgerCodec.encodeEpisodes(FuseLedgerAdapter().episodes)
        assertFalse(o.has("foundation"), "ohne laufende Autorisierung steht das Feld gar nicht drin")
        val zurueck = LedgerCodec.decodeEpisodes(JSONObject(o.toString()))
        assertFalse(zurueck.foundation.valid)
        assertEquals(0.0, zurueck.deliveredSinceHandoverU, 1e-9)
    }

    /**
     * EIN HALB GESCHRIEBENES FELD IST KORRUPTION, KEINE FAELLIGE MIGRATION.
     *
     * Fehlt innen ein Pflichtfeld, wirft der Decoder und die ganze Generation
     * ist ungueltig. Das ist strenger als "Rest lesen, Fehlendes annehmen" -
     * und muss es sein: die uebrigen Felder ergaeben eine Autorisierung mit
     * geratenem Anteil oder geratener Decke.
     */
    @Test
    fun `ein fehlendes Pflichtfeld macht die Generation ungueltig`() {
        for (feld in listOf(
            "armedTs", "totalBudgetU", "phaseAShare", "pinnedPrimeWindowMin",
            "pinnedWallCeilingMin", "endTs", "latchedHandoverTs", "deliveredSinceHandoverU",
        )) {
            val o = LedgerCodec.encodeEpisodes(adapter(bezahlt = 0.4).episodes)
            o.getJSONObject("foundation").remove(feld)
            assertThrows(Exception::class.java, { LedgerCodec.decodeEpisodes(JSONObject(o.toString())) }, feld)
        }
    }

    /**
     * EINE WIDERSPRUECHLICHE GENERATION IST KORRUPTION - SIE FAELLT.
     *
     * DIESER TEST HAT DEN FEHLER ZUERST FESTGESCHRIEBEN (Toni 18.08., P0).
     * Er akzeptierte "Wurf ODER none()" als gleichermassen fail-closed. Das
     * ist es nicht: `none()` ist die Lesart fuer eine ALTDATEI, und wer sie
     * einer beschaedigten Datei gibt, laesst
     *
     *   Prime auf das aktuelle volle LIVE-Budget zurueckfallen statt auf das
     *   gepinnte Teilbudget - also MEHR Insulin;
     *
     *   und die beschaedigte NEUERE Generation gegen eine intakte aeltere
     *   gewinnen, weil kein Fehler gemeldet wurde.
     *
     * Die Felder sind hier alle da und einzeln plausibel - erst ihre
     * BEZIEHUNG ist kaputt. Genau dafuer ist `restore` die eine pruefende
     * Stelle; eine feldweise Pruefung saehe nichts.
     */
    @Test
    fun `widerspruechliche Felder machen die Generation ungueltig`() {
        val faelle = listOf<Triple<String, String, Any>>(
            Triple("Ende vor Marker", "endTs", t0 - 1000L),
            Triple("Latch vor Marker", "latchedHandoverTs", t0 - 1000L),
            Triple("Anteil ueber 1", "phaseAShare", 1.5),
            Triple("Anteil negativ", "phaseAShare", -0.1),
            Triple("Budget 0", "totalBudgetU", 0.0),
            Triple("Marker 0", "armedTs", 0L),
            Triple("negatives Prime-Fenster", "pinnedPrimeWindowMin", -1),
            Triple("negative Decke", "pinnedWallCeilingMin", -1),
        )
        for ((name, feld, wert) in faelle) {
            val o = LedgerCodec.encodeEpisodes(adapter(bezahlt = 0.4).episodes)
            o.getJSONObject("foundation").put(feld, wert)
            assertThrows(
                Exception::class.java,
                { LedgerCodec.decodeEpisodes(JSONObject(o.toString())) },
                "$name - eine vorhandene kaputte Autorisierung MUSS die Generation fallen lassen",
            )
        }
    }

    /**
     * DER UNTERSCHIED, UM DEN ES GEHT: fehlend gegen kaputt.
     *
     * Dieselbe Datei, einmal ohne das Objekt und einmal mit einem
     * beschaedigten. Das eine ist Legacy und laedt, das andere ist Korruption
     * und faellt. Wer beide gleich behandelt, hat kein fail-closed, sondern
     * eine stille Herabstufung.
     */
    @Test
    fun `fehlend laedt, kaputt faellt`() {
        val ohne = LedgerCodec.encodeEpisodes(adapter(bezahlt = 0.4).episodes)
        ohne.remove("foundation")
        val gelesen = LedgerCodec.decodeEpisodes(JSONObject(ohne.toString()))
        assertFalse(gelesen.foundation.valid, "fehlend = kein Fundament, kein Fehler")

        val kaputt = LedgerCodec.encodeEpisodes(adapter(bezahlt = 0.4).episodes)
        kaputt.getJSONObject("foundation").put("phaseAShare", 2.0)
        assertThrows(Exception::class.java, { LedgerCodec.decodeEpisodes(JSONObject(kaputt.toString())) })
    }

    // ---- Buchfuehrung -----------------------------------------------------

    private fun mitReservierung(
        menge: Double = 0.30,
        phase: MealFoundation.Phase = MealFoundation.Phase.PHASE_B,
        schonBezahlt: Double = 0.0,
    ): FuseLedgerAdapter {
        val a = adapter(bezahlt = schonBezahlt)
        if (phase == MealFoundation.Phase.PHASE_B)
            a.episodes.deliveredSinceHandoverU += menge
        a.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = t0, amountU = menge, prime = false, onset = false, mealTs = 0L,
            foundationPhase = phase,
        )
        return a
    }

    @Test
    fun `eine publizierte Menge bleibt in Phase B stehen`() {
        val a = mitReservierung(menge = 0.30)
        a.resolveReservation(t0, publishedU = 0.30)
        assertEquals(0.30, a.episodes.deliveredSinceHandoverU, 1e-9)
        assertNull(a.episodes.pendingReservation)
    }

    @Test
    fun `eine abgelehnte Menge wird exakt zurueckgedreht`() {
        val a = mitReservierung(menge = 0.30, schonBezahlt = 0.45)
        assertEquals(0.75, a.episodes.deliveredSinceHandoverU, 1e-9)
        a.resolveReservation(t0, publishedU = 0.0)
        assertEquals(
            0.45, a.episodes.deliveredSinceHandoverU, 1e-9,
            "genau die eigene Menge zurueck - nicht auf 0",
        )
    }

    @Test
    fun `eine teilweise publizierte Menge gibt nur die Differenz frei`() {
        val a = mitReservierung(menge = 0.30)
        a.resolveReservation(t0, publishedU = 0.10)
        assertEquals(0.10, a.episodes.deliveredSinceHandoverU, 1e-9)
    }

    /**
     * EIN UNGEKLAERTER AUSGANG BLEIBT BELASTET.
     *
     * Wird resolveReservation nie gerufen - Absturz, Ausnahme, fremder Pfad
     * -, bleibt die Bezahlung stehen. Phase B haelt sich dann faelschlich fuer
     * versorgt und gibt WENIGER. Das ist die richtige Fehlrichtung.
     */
    @Test
    fun `ein ungeklaerter Ausgang bleibt belastet`() {
        val a = mitReservierung(menge = 0.30)
        assertEquals(0.30, a.episodes.deliveredSinceHandoverU, 1e-9)
        assertTrue(a.episodes.pendingReservation != null, "die Haftung bleibt offen")
    }

    /** Zweimal aufloesen darf nicht zweimal freigeben. */
    @Test
    fun `das Aufloesen ist idempotent`() {
        val a = mitReservierung(menge = 0.30, schonBezahlt = 0.45)
        a.resolveReservation(t0, publishedU = 0.0)
        a.resolveReservation(t0, publishedU = 0.0)
        assertEquals(0.45, a.episodes.deliveredSinceHandoverU, 1e-9)
    }

    /** Ein fremder Zyklus fasst die Reservierung nicht an. */
    @Test
    fun `ein fremder Zyklus loest nicht auf`() {
        val a = mitReservierung(menge = 0.30)
        a.resolveReservation(t0 + 60_000L, publishedU = 0.0)
        assertEquals(0.30, a.episodes.deliveredSinceHandoverU, 1e-9)
        assertTrue(a.episodes.pendingReservation != null)
    }

    /**
     * PHASE A DREHT PHASE B NICHT ZURUECK.
     *
     * Eine in Phase A gebuchte und dann abgelehnte Menge hat den
     * Phase-B-Zaehler nie belastet. Wuerde sie ihn trotzdem senken, entstuende
     * dort ein Rueckstand, den niemand hat - und Phase B gaebe zu VIEL.
     */
    @Test
    fun `eine abgelehnte Phase-A-Menge laesst Phase B unberuehrt`() {
        val a = mitReservierung(
            menge = 0.30, phase = MealFoundation.Phase.PHASE_A, schonBezahlt = 0.45,
        )
        a.resolveReservation(t0, publishedU = 0.0)
        assertEquals(0.45, a.episodes.deliveredSinceHandoverU, 1e-9)
    }

    /**
     * NUR ECHTES PHASE_B BEWEGT DEN ZAEHLER - keine der anderen drei Phasen.
     *
     * Wuerde eine von ihnen ihn beim Ablehnen senken, entstuende dort ein
     * Rueckstand, den niemand hat, und Phase B gaebe zu VIEL. AFTER_WINDOW ist
     * dabei der juengste der drei Faelle (Toni 18.08., P0): vorher blieb die
     * Phase nach der Uebergabe unbegrenzt PHASE_B, es gab diese Lage also gar
     * nicht.
     */
    @Test
    fun `nur Phase B bewegt den Zaehler`() {
        for (phase in listOf(
            MealFoundation.Phase.NONE,
            MealFoundation.Phase.PHASE_A,
            MealFoundation.Phase.AFTER_WINDOW,
        )) {
            val a = mitReservierung(menge = 0.30, phase = phase, schonBezahlt = 0.45)
            assertEquals(
                0.45, a.episodes.deliveredSinceHandoverU, 1e-9,
                "$phase belastet nicht",
            )
            a.resolveReservation(t0, publishedU = 0.0)
            assertEquals(
                0.45, a.episodes.deliveredSinceHandoverU, 1e-9,
                "$phase entlastet auch nicht",
            )
        }
    }

    /**
     * EIN NEUSTART GIBT NICHTS ERNEUT FREI.
     *
     * Die Reservierung selbst ist bewusst nicht persistent: geht sie
     * verloren, bleibt die Belastung stehen. Nach dem Rundlauf darf also
     * weder der Zaehler sinken noch eine offene Haftung wiederauferstehen.
     */
    @Test
    fun `ein Neustart gibt eine offene Reservierung nicht frei`() {
        val a = mitReservierung(menge = 0.30, schonBezahlt = 0.45)
        val zurueck = rundlauf(a.episodes)
        assertEquals(
            0.75, zurueck.deliveredSinceHandoverU, 1e-9,
            "die Belastung ueberlebt - der konservative Ausgang",
        )
        assertNull(zurueck.pendingReservation, "die Haftung selbst ist nicht persistent")
    }

    /**
     * EINE ZWEITE WELLE OHNE NEUEN MARKER SETZT NICHTS ZURUECK.
     *
     * Der Zaehler haengt an der AUTORISIERUNG, nicht am Zyklus. Solange
     * dieselbe Autorisierung laeuft, addiert jede weitere Abgabe - sie
     * beginnt keine neue Rechnung.
     */
    @Test
    fun `eine zweite Welle addiert auf dieselbe Bezahlung`() {
        val a = adapter(bezahlt = 0.45)
        val vorher = a.episodes.foundation.armedTs
        a.episodes.deliveredSinceHandoverU += 0.30
        a.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = t0 + 60_000L, amountU = 0.30, prime = false, onset = false, mealTs = 0L,
            foundationPhase = MealFoundation.Phase.PHASE_B,
        )
        a.resolveReservation(t0 + 60_000L, publishedU = 0.30)
        assertEquals(0.75, a.episodes.deliveredSinceHandoverU, 1e-9)
        assertEquals(vorher, a.episodes.foundation.armedTs, "dieselbe Autorisierung")
    }

    /** Ein Zaehler kann durch Rueckdrehen nie unter null geraten. */
    @Test
    fun `das Zurueckdrehen bleibt bei null stehen`() {
        val a = adapter(bezahlt = 0.10)
        a.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = t0, amountU = 0.30, prime = false, onset = false, mealTs = 0L,
            foundationPhase = MealFoundation.Phase.PHASE_B,
        )
        a.resolveReservation(t0, publishedU = 0.0)
        assertEquals(0.0, a.episodes.deliveredSinceHandoverU, 1e-9)
    }
}
