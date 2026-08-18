package app.aaps.fuse.plugin.expectation

import app.aaps.fuse.core.controller.ExpectationLedger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER CODEC - und seine eine tragende Eigenschaft.
 *
 * Tonis Persistenzauflage: "Schema-/Ladefehler duerfen weiterhin keinen
 * lambda-Nachweis erzeugen." Deshalb pruefen diese Tests vor allem, WAS BEI
 * FEHLERN PASSIERT - nicht nur, dass ein sauberer Rundlauf funktioniert.
 */
class FuseExpectationCodecTest {

    private val t0 = 1_787_000_000_000L

    private fun eintrag(source: Long = t0, seg: Long = 1L) = ExpectationLedger.Entry(
        sourceTs = source, dueTs = source + 30 * 60_000L, segmentId = seg,
        anchorMgdl = 200.0, meanPredictedMgdl = 150.0,
        configGeneration = "cfg#1", interventionRevision = 42L,
        safetyLowerPredictedMgdl = 40.0, lambda = 1.0,
        discountMgdl = -110.8, bgiMgdl = -127.7,
    )

    private fun voll() = ExpectationLedger.State(
        entries = listOf(eintrag(), eintrag(source = t0 + 60_000L)),
        consumed = setOf(
            ExpectationLedger.SampleId(1L, t0 + 120_000L),
            ExpectationLedger.SampleId(2L, t0 + 180_000L),
        ),
        outcomes = listOf(
            ExpectationLedger.Outcome(
                eintrag(), ExpectationLedger.Verdict.MISSED, t0 + 30 * 60_000L, 205.0,
            ),
            ExpectationLedger.Outcome(eintrag(source = t0 - 60_000L), ExpectationLedger.Verdict.UNVERIFIABLE),
        ),
    )

    // ---- Rundlauf ---------------------------------------------------------

    /** Alle drei Teile muessen den Rundlauf unveraendert ueberstehen -
     *  sie sind EINE Generation. */
    @Test
    fun `der Zustand ueberlebt den Rundlauf vollstaendig`() {
        val vorher = voll()
        val nachher = FuseExpectationCodec.decode(FuseExpectationCodec.encode(vorher))
        assertEquals(vorher.entries, nachher.entries)
        assertEquals(vorher.consumed, nachher.consumed)
        assertEquals(vorher.outcomes, nachher.outcomes)
    }

    @Test
    fun `der leere Zustand ueberlebt ebenfalls`() {
        val leer = ExpectationLedger.State()
        assertTrue(FuseExpectationCodec.decode(FuseExpectationCodec.encode(leer)).isEmpty)
    }

    /** Die optionalen Felder duerfen nicht zu 0 werden - `null` heisst
     *  "nicht gerechnet", und das muss den Rundlauf ueberleben. */
    @Test
    fun `nicht gesetzte Felder bleiben null`() {
        val knapp = ExpectationLedger.Entry(
            sourceTs = t0, dueTs = t0 + 60_000L, segmentId = 1L,
            anchorMgdl = 200.0, meanPredictedMgdl = 150.0,
            configGeneration = "cfg#1", interventionRevision = 1L,
        )
        val zurueck = FuseExpectationCodec
            .decode(FuseExpectationCodec.encode(ExpectationLedger.State(entries = listOf(knapp))))
        assertEquals(knapp, zurueck.entries[0])
        assertEquals(null, zurueck.entries[0].lambda)
    }

    // ---- FAIL-CLOSED: der eigentliche Zweck -------------------------------

    /**
     * JEDER FEHLER ERGIBT DEN LEEREN ZUSTAND, nie einen teilweise gelesenen.
     *
     * Eine halbe Generation koennte offene Prognosen gegen bereits
     * verbrauchte Messwerte pruefen oder eine Strecke fortschreiben, deren
     * Anfang fehlt - beides erfindet einen Nachweis. Ein leerer Zustand
     * verzoegert ihn nur.
     */
    @Test
    fun `jeder Lesefehler ergibt den leeren Zustand`() {
        val kaputt = listOf(
            null,
            "",
            "   ",
            "kein json",
            "{}",
            """{"schema":1}""",
            """{"schema":999,"entries":[],"consumed":[],"outcomes":[]}""",
            """{"entries":[],"consumed":[],"outcomes":[]}""",
        )
        for (t in kaputt) assertTrue(
            FuseExpectationCodec.decode(t).isEmpty,
            "muss leer ergeben: $t",
        )
    }

    /**
     * EIN EINZIGER UNLESBARER EINTRAG VERWIRFT DIE GANZE GENERATION - kein
     * "so viel wie moeglich retten". Die drei Teile sind nur zusammen
     * stimmig; Teilrettung waere die bequeme und falsche Loesung.
     */
    @Test
    fun `ein einzelner kaputter Eintrag verwirft die ganze Generation`() {
        val gut = FuseExpectationCodec.encode(voll())
        // Ein Pflichtfeld aus dem ERSTEN Eintrag entfernen.
        val kaputt = gut.replace(""""mean":150""", """"mn":150""")
        assertTrue(kaputt != gut, "die Mutation muss greifen")
        val zurueck = FuseExpectationCodec.decode(kaputt)
        assertTrue(zurueck.isEmpty, "auch die intakten Teile duerfen nicht durchkommen")
    }

    /** Ein unbekanntes Verdikt darf nicht still zu etwas Harmlosem werden. */
    @Test
    fun `ein unbekanntes Verdikt verwirft die Generation`() {
        val kaputt = FuseExpectationCodec.encode(voll()).replace("MISSED", "VIELLEICHT")
        assertTrue(FuseExpectationCodec.decode(kaputt).isEmpty)
    }

    /** NaN und Unendlich sind keine Messwerte - sie durchzulassen hiesse,
     *  spaeter mit ihnen zu rechnen. */
    @Test
    fun `nicht endliche Zahlen verwerfen die Generation`() {
        for (kaputt in listOf(
            FuseExpectationCodec.encode(voll()).replace(""""anchor":200""", """"anchor":"NaN""""),
            FuseExpectationCodec.encode(voll()).replace(""""mean":150""", """"mean":"Infinity""""),
        )) assertTrue(FuseExpectationCodec.decode(kaputt).isEmpty, kaputt.take(80))
    }

    /** Eine leere Konfigurationskennung ist keine - sie soll
     *  Vergleichbarkeit garantieren. */
    @Test
    fun `eine leere Konfigurationskennung verwirft die Generation`() {
        val kaputt = FuseExpectationCodec.encode(voll()).replace(""""cfg":"cfg#1"""", """"cfg":""""")
        assertTrue(FuseExpectationCodec.decode(kaputt).isEmpty)
    }
}
