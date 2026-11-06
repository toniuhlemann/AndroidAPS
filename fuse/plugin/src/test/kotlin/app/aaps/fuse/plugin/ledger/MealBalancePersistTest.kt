package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.ECarbs
import app.aaps.fuse.core.controller.ExpectationAccount
import app.aaps.fuse.core.controller.MealBalance
import app.aaps.fuse.core.controller.PartialTbrOwnership
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Entwurf B, Toni 05.11.2026: die gemeinsame Bilanz der Rechner-Mahlzeit und der Nachweis ihrer Basal-Ruecknahme sind
 * restartfest - verlustfrei, additiv ohne Schemasprung, und ein unlesbarer Eintrag sperrt nicht den ganzen Ledger.
 */
class MealBalancePersistTest {

    private val t = 1_800_000_000_000L

    private fun rund(e: EpisodeBudgets) = LedgerCodec.decodeEpisodes(JSONObject(LedgerCodec.encodeEpisodes(e).toString()))

    private val episode = MealBalance.Episode(
        authId = t,
        observation = ExpectationAccount.Observation(1.25, true, t + 600_000L, 142.5),
        attribution = ECarbs.CobAttribution(absorbedU = 0.4, maxExcessU = 0.7, lastArrivedU = 1.1),
    )

    @Test
    fun `die Episode ueberlebt den Neustart verlustfrei`() {
        assertEquals(episode, rund(EpisodeBudgets().also { it.mealBalance = episode }).mealBalance)
        // Ohne Hochwassermarke, mit ungueltiger Beobachtung und nicht endlichem Messwert, vorbei.
        val vorbei = MealBalance.Episode(
            authId = t, observation = ExpectationAccount.Observation(0.3, false, t + 600_000L, Double.NaN), done = true,
        )
        assertEquals(vorbei, rund(EpisodeBudgets().also { it.mealBalance = vorbei }).mealBalance)
    }

    /** Die eCarbs-Eintraege mit Herkunft (ueberlappende Rechner-Mahlzeiten); eine Altdatei ohne sie kennt keine. */
    @Test
    fun `die eCarbs-Eintraege mit Herkunft ueberleben den Neustart, eine Altdatei ohne sie kennt keine`() {
        val mit = episode.copy(eCarbs = listOf(MealBalance.ECarbsRef(11L, t - 7_200_000L), MealBalance.ECarbsRef(21L, t)))
        assertEquals(mit, rund(EpisodeBudgets().also { it.mealBalance = mit }).mealBalance)
        val alt = LedgerCodec.encodeEpisodes(EpisodeBudgets().also { it.mealBalance = mit })
        alt.getJSONObject("mealBalance").remove("eCarbs")
        assertEquals(episode, LedgerCodec.decodeEpisodes(JSONObject(alt.toString())).mealBalance)
    }

    @Test
    fun `der Nachweis der Ruecknahme ueberlebt den Neustart`() {
        val s = PartialTbrOwnership.State(
            confirmedRunning = PartialTbrOwnership.Identity(0.25, t, 30),
            pendingRequest = PartialTbrOwnership.Identity(0.1, t + 60_000L, 30),
            pendingAttempts = 1,
            ending = PartialTbrOwnership.Ending(t + 120_000L, attempts = 1, lastRequestTs = t + 120_000L),
        )
        assertEquals(s, rund(EpisodeBudgets().also { it.mealWithdrawalTbr = s }).mealWithdrawalTbr)
    }

    @Test
    fun `leer schreibt keinen Eintrag, und eine Altdatei ohne Felder heisst keine Episode und kein Nachweis`() {
        val leer = LedgerCodec.encodeEpisodes(EpisodeBudgets())
        assertTrue(leer.isNull("mealBalance"))
        assertTrue(leer.isNull("mealWithdrawalTbr"))
        val alt = JSONObject(leer.toString()).also { it.remove("mealBalance"); it.remove("mealWithdrawalTbr") }
        val e = LedgerCodec.decodeEpisodes(alt)
        assertNull(e.mealBalance)
        assertTrue(e.mealWithdrawalTbr.leer && e.mealWithdrawalTbr.ending == null)
    }

    @Test
    fun `ein unlesbarer Eintrag faellt weg, ohne den Ledger zu sperren`() {
        val json = LedgerCodec.encodeEpisodes(EpisodeBudgets().also { it.mealBalance = episode })
        json.getJSONObject("mealBalance").put("absorbedU", -1.0)
        assertNull(LedgerCodec.decodeEpisodes(JSONObject(json.toString())).mealBalance)
        // Ein Eintrag ohne gueltige Herkunft ebenso.
        val ohneHerkunft = LedgerCodec.encodeEpisodes(
            EpisodeBudgets().also { it.mealBalance = episode.copy(eCarbs = listOf(MealBalance.ECarbsRef(11L, 0L))) },
        )
        assertNull(LedgerCodec.decodeEpisodes(JSONObject(ohneHerkunft.toString())).mealBalance)
    }
}
