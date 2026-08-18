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
    private fun rund(state: ExpectationLedger.State) =
        (FuseExpectationCodec.decode(FuseExpectationCodec.encode(state))
            as FuseExpectationCodec.Decoded.Valid).state

    @Test
    fun `der Zustand ueberlebt den Rundlauf vollstaendig`() {
        val vorher = voll()
        val nachher = rund(vorher)
        assertEquals(vorher.entries, nachher.entries)
        assertEquals(vorher.consumed, nachher.consumed)
        assertEquals(vorher.outcomes, nachher.outcomes)
    }

    @Test
    fun `der leere Zustand ueberlebt ebenfalls`() {
        assertTrue(rund(ExpectationLedger.State()).isEmpty)
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
        val zurueck = rund(ExpectationLedger.State(entries = listOf(knapp)))
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
    /**
     * FEHLT IST NICHT BESCHAEDIGT (Toni, P0).
     *
     * Der erste Wurf gab immer den leeren Zustand zurueck. Damit haette der
     * Store eine kaputte Zieldatei als gueltigen Leerstand akzeptiert, statt
     * die `.bak`-Generation zu ziehen. Die Unterscheidung ist die halbe
     * Sicherung.
     */
    @Test
    fun `fehlend und beschaedigt sind verschiedene Ergebnisse`() {
        for (nichts in listOf(null, "", "   ")) assertTrue(
            FuseExpectationCodec.decode(nichts) is FuseExpectationCodec.Decoded.Missing,
            "muss Missing sein: '$nichts'",
        )
        for (kaputt in listOf(
            "kein json",
            "{}",
            """{"schema":1}""",
            """{"schema":999,"entries":[],"consumed":[],"outcomes":[]}""",
            """{"entries":[],"consumed":[],"outcomes":[]}""",
        )) {
            val d = FuseExpectationCodec.decode(kaputt)
            assertTrue(d is FuseExpectationCodec.Decoded.Invalid, "muss Invalid sein: $kaputt")
            assertTrue((d as FuseExpectationCodec.Decoded.Invalid).reason.isNotBlank(), "mit Grund")
        }
        // Und eine gueltige LEERE Generation ist Valid, nicht Missing.
        val leer = FuseExpectationCodec.decode(FuseExpectationCodec.encode(ExpectationLedger.State()))
        assertTrue(leer is FuseExpectationCodec.Decoded.Valid, "gueltig leer ist Valid")
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
        assertTrue(
            FuseExpectationCodec.decode(kaputt) is FuseExpectationCodec.Decoded.Invalid,
            "auch die intakten Teile duerfen nicht durchkommen",
        )
    }

    /** Ein unbekanntes Verdikt darf nicht still zu etwas Harmlosem werden. */
    @Test
    fun `ein unbekanntes Verdikt verwirft die Generation`() {
        val kaputt = FuseExpectationCodec.encode(voll()).replace("MISSED", "VIELLEICHT")
        assertTrue(FuseExpectationCodec.decode(kaputt) is FuseExpectationCodec.Decoded.Invalid)
    }

    /** NaN und Unendlich sind keine Messwerte - sie durchzulassen hiesse,
     *  spaeter mit ihnen zu rechnen. */
    @Test
    fun `nicht endliche Zahlen verwerfen die Generation`() {
        for (kaputt in listOf(
            FuseExpectationCodec.encode(voll()).replace(""""anchor":200""", """"anchor":"NaN""""),
            FuseExpectationCodec.encode(voll()).replace(""""mean":150""", """"mean":"Infinity""""),
        )) assertTrue(FuseExpectationCodec.decode(kaputt) is FuseExpectationCodec.Decoded.Invalid, kaputt.take(80))
    }

    /** Eine leere Konfigurationskennung ist keine - sie soll
     *  Vergleichbarkeit garantieren. */
    @Test
    fun `eine leere Konfigurationskennung verwirft die Generation`() {
        val kaputt = FuseExpectationCodec.encode(voll()).replace(""""cfg":"cfg#1"""", """"cfg":""""")
        assertTrue(FuseExpectationCodec.decode(kaputt) is FuseExpectationCodec.Decoded.Invalid)
    }

    // ---- Semantisch unmoegliche Zustaende (Toni, P0) ---------------------

    /**
     * SYNTAKTISCH GUELTIG IST NICHT SEMANTISCH MOEGLICH.
     *
     * Eine beschaedigte oder manipulierte Datei kann ein MISSED mit
     * plausiblen Zahlen enthalten - und das erzeugt unmittelbar
     * lambda-Evidenz. Der Kern prueft deshalb die Bedeutung: dass eine
     * Faelligkeit nach ihrer Quelle liegt, dass ein Messurteil einen
     * Messwert hat und ein Nicht-Urteil keinen, dass der Messwert im
     * Zuordnungsfenster lag.
     *
     * Jede dieser Bedingungen kann eine echte Rechnung gar nicht verletzen -
     * wer sie verletzt, kommt nicht aus einer Rechnung.
     *
     * DIE FAELLE WERDEN ALS OBJEKTE GEBAUT, nicht per Textersetzung: die
     * Feldreihenfolge von org.json ist nicht zugesichert, und eine Mutation,
     * die am Layout scheitert, prueft nichts. Der erste Anlauf tat genau das
     * und wurde von der eigenen Zusicherung `text != gut` gefangen.
     */
    @Test
    fun `semantisch unmoegliche Zustaende werden verworfen`() {
        val e = eintrag()
        val faelle = mapOf(
            "MISSED ohne Messwert" to ExpectationLedger.State(
                outcomes = listOf(ExpectationLedger.Outcome(e, ExpectationLedger.Verdict.MISSED)),
            ),
            "MET ohne Messwert" to ExpectationLedger.State(
                outcomes = listOf(ExpectationLedger.Outcome(e, ExpectationLedger.Verdict.MET)),
            ),
            "UNVERIFIABLE MIT Messwert" to ExpectationLedger.State(
                outcomes = listOf(
                    ExpectationLedger.Outcome(e, ExpectationLedger.Verdict.UNVERIFIABLE, e.dueTs, 205.0),
                ),
            ),
            "INTERVENED MIT Messwert" to ExpectationLedger.State(
                outcomes = listOf(
                    ExpectationLedger.Outcome(e, ExpectationLedger.Verdict.INTERVENED, e.dueTs, 205.0),
                ),
            ),
            "Faelligkeit vor der Quelle" to ExpectationLedger.State(
                entries = listOf(e.copy(dueTs = e.sourceTs - 1000L)),
            ),
            "Senkung zu klein" to ExpectationLedger.State(
                entries = listOf(e.copy(meanPredictedMgdl = 199.0)),
            ),
            "leere Konfigurationskennung" to ExpectationLedger.State(
                entries = listOf(e.copy(configGeneration = "")),
            ),
            "Messwert ausserhalb der Zuordnungstoleranz" to ExpectationLedger.State(
                outcomes = listOf(
                    ExpectationLedger.Outcome(
                        e, ExpectationLedger.Verdict.MISSED, e.dueTs + 60 * 60_000L, 205.0,
                    ),
                ),
            ),
        )
        for ((name, zustand) in faelle) {
            val d = FuseExpectationCodec.decode(FuseExpectationCodec.encode(zustand))
            assertTrue(d is FuseExpectationCodec.Decoded.Invalid, "$name muss Invalid sein, war $d")
            assertTrue((d as FuseExpectationCodec.Decoded.Invalid).reason.isNotBlank(), "$name ohne Grund")
        }
    }

    /** Doppelte Kennungen sind unmoeglich - `add` verhindert sie, also kann
     *  eine Datei mit Duplikaten nicht aus einer Rechnung stammen. */
    @Test
    fun `doppelte Kennungen werden verworfen`() {
        val doppelt = ExpectationLedger.State(entries = listOf(eintrag(), eintrag()))
        val d = FuseExpectationCodec.decode(FuseExpectationCodec.encode(doppelt))
        assertTrue(d is FuseExpectationCodec.Decoded.Invalid)
        assertTrue((d as FuseExpectationCodec.Decoded.Invalid).reason.contains("doppelte"), d.reason)
    }

    /** Die Gegenprobe: ein moeglicher Zustand kommt unveraendert durch -
     *  sonst wuerde die Pruefung gueltige Generationen wegwerfen. */
    @Test
    fun `ein moeglicher Zustand bleibt gueltig`() {
        val d = FuseExpectationCodec.decode(FuseExpectationCodec.encode(voll()))
        assertTrue(d is FuseExpectationCodec.Decoded.Valid, "war $d")
    }
}
