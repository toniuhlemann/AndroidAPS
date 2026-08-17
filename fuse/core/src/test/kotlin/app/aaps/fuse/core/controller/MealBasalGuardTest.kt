package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE BASAL-GRUNDREGEL, ohne Runner (Tonis Vertrag 17.08.).
 *
 * Der Anlassfall stand am Geraet: Marker 18:56, Huelle 3,5 U, ab 19:05 SMBs
 * UND Guard-Null gleichzeitig - netto 3,10 statt 3,5 U. Um 19:30 wurde die
 * Null aus einer 6-Punkte-Reihe nach CGM-Luecke ERNEUERT (minLower 8,8 bei
 * BG 127 steigend). Jeder Testfall hier ist eine Zeile aus dem Vertrag.
 */
class MealBasalGuardTest {

    private fun modellNull(limit: String = "guardFloor=70.0") = FuseController.Decision(
        smbU = 0.15, tbr = FuseController.TbrAction.ZERO_TEMP,
        block = FuseController.Block.GUARD_FLOOR,
        insulinReqU = null, predAtReleaseMgdl = null, minLowerMgdl = 69.4,
        bindingLimit = limit, zeroTempModelOnly = true,
    )

    private fun lage(
        schutz: Boolean = true,
        tief: Boolean = false,
        nahTief: Boolean = false,
        kontrollierbar: Boolean = true,
        reif: Boolean = true,
    ) = MealBasalGuard.Input(
        protectionActive = schutz, measuredLow = tief, nearLowFalling = nahTief,
        tbrControllable = kontrollierbar, segmentMature = reif,
    )

    /** Zeile 1 des Vertrags: Mahlzeit + nur Modellwarnung -> Profilbasal. */
    @Test
    fun `die modellbedingte Null weicht in der geschuetzten Lage`() {
        val d = MealBasalGuard.apply(modellNull(), lage())
        assertEquals(FuseController.TbrAction.KEEP_CURRENT, d.tbr, "Profilbasal bleibt erhalten")
        assertEquals(0.15, d.smbU, 1e-9, "die Menge bleibt unangetastet")
        assertFalse(d.zeroTempModelOnly, "das Bit beschreibt die aktuelle Aktion - keine Null mehr")
        assertTrue(d.mealBasalProtected, "der Stempel traegt die Lage zum Translator (C7c)")
        assertTrue(d.bindingLimit.startsWith(MealBasalGuard.TBR_LIFTED_MARK), d.bindingLimit)
        assertEquals(
            69.4, d.minLowerMgdl!!, 1e-9,
            "die Bahn bleibt unveraendert exportiert - sie ist Diagnose, nicht Aktion",
        )
    }

    /** Zeile 2: reale kurzfristige Low-Gefahr -> die Null bleibt. */
    @Test
    fun `ein nahes fallendes Tief laesst die Null stehen`() {
        val d = MealBasalGuard.apply(modellNull(), lage(nahTief = true))
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr, "gemessene Gefahr ist nicht ueberstimmbar")
        assertFalse(d.mealBasalProtected, "und der Stempel darf den Abbruch nicht freigeben")
    }

    /** Das GEMESSENE Tief schaltet die Schutzlage zentral ab - nicht nur
     *  seine eigene Null (die traegt das Bit ohnehin nicht). */
    @Test
    fun `ein gemessenes Tief schaltet den Schutz ab`() {
        val d = MealBasalGuard.apply(modellNull(), lage(tief = true))
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr)
        assertFalse(d.mealBasalProtected)
    }

    /**
     * FAKE_EXTENDED: kann FUSE die TBR-Achse nicht kontrollieren, hebt es
     * nichts. Die Hebung wuerde den Zyklus als "sicher" ausweisen (Intent
     * KEEP statt SAFETY_ZERO) und damit die C8-SMB-Sperre des
     * Extended-Bolus aushebeln - genau die Regression, die der
     * Verdrahtungstest im ersten Anlauf gefangen hat.
     */
    @Test
    fun `ohne kontrollierbare TBR-Achse bleibt alles stehen`() {
        val d = MealBasalGuard.apply(modellNull(), lage(kontrollierbar = false))
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr, "die Null-Absicht traegt die C8-Sperre")
        assertTrue(d.zeroTempModelOnly, "und die Herkunft bleibt lesbar")
        assertFalse(d.mealBasalProtected)
    }

    /** Ohne Mahlzeiten-/Absorptionslage gilt der normale TBR-Vertrag. */
    @Test
    fun `ohne Schutzlage bleibt die Null`() {
        val d = MealBasalGuard.apply(modellNull(), lage(schutz = false))
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr)
        assertFalse(d.mealBasalProtected)
    }

    /**
     * ZEILE 3 DES VERTRAGS - der 19:30-Fall: nach der CGM-Luecke stand
     * minLower aus 6 Punkten bei 8,8 mg/dl, waehrend der reale BG 127 zeigte
     * und stieg. Aus so einer Reihe wird keine Null GESETZT oder ERNEUERT -
     * auch AUSSERHALB der Schutzlage. Eine laufende Null laeuft aus; sie wird
     * weder erneuert noch blind beendet.
     */
    @Test
    fun `eine unreife Reihe setzt und erneuert keine Null`() {
        val d = MealBasalGuard.apply(modellNull(), lage(schutz = false, reif = false))
        assertEquals(FuseController.TbrAction.NO_NEW_POSITIVE, d.tbr, "keine neue Null aus unreifer Bahn")
        assertTrue(d.bindingLimit.startsWith(MealBasalGuard.IMMATURE_MARK), d.bindingLimit)
        assertFalse(d.mealBasalProtected, "ausserhalb der Lage kein Stempel")
    }

    /** In der Schutzlage gewinnt die HEBUNG vor der Unreife-Sperre - beide
     *  enden ohne Null, aber nur die Hebung stempelt und haelt Profilbasal. */
    @Test
    fun `in der Schutzlage gewinnt die Hebung auch bei unreifer Reihe`() {
        val d = MealBasalGuard.apply(modellNull(), lage(reif = false))
        assertEquals(FuseController.TbrAction.KEEP_CURRENT, d.tbr)
        assertTrue(d.mealBasalProtected)
    }

    /** Unter FAKE_EXTENDED greift auch die Unreife-Sperre NICHT - die
     *  Null-Absicht muss stehen bleiben, damit die C8-SMB-Sperre haelt. */
    @Test
    fun `die Unreife-Sperre respektiert FAKE_EXTENDED`() {
        val d = MealBasalGuard.apply(modellNull(), lage(schutz = false, reif = false, kontrollierbar = false))
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr)
    }

    /**
     * DIE LUECKE AUS DER RESET-ANALYSE (P0, 17.08. nachgezogen).
     *
     * Der erste Wurf gab die Wirklichkeits-Ausnahme nur Zweig 1. Bei realem
     * Nah-Tief ODER gemessenem Tief auf unreifer Reihe unterdrueckte Zweig 2
     * damit eine Null, die vor dem ganzen Patch gelaufen waere - die
     * Umkehrung des eigenen Vertrags, und zwar in der gefaehrlichen Richtung.
     *
     * Die Unreife entwertet die gerechnete TIEFE, nicht den gemessenen
     * Zustand: als binaerer Detektor ist die unreife Bahn gemessen genauso
     * gut wie die reife (46,3 % vs 46,8 % realer BG < 80 in 120 min).
     */
    @Test
    fun `die Unreife-Sperre weicht der Wirklichkeit`() {
        for (l in listOf(
            lage(schutz = false, reif = false, nahTief = true),
            lage(schutz = false, reif = false, tief = true),
            lage(schutz = true, reif = false, nahTief = true),
        )) {
            val d = MealBasalGuard.apply(modellNull(), l)
            assertEquals(
                FuseController.TbrAction.ZERO_TEMP, d.tbr,
                "eine gemessene Tiefgefahr darf die Unreife-Sperre nicht entwaffnen: $l",
            )
            assertTrue(!d.bindingLimit.contains(MealBasalGuard.IMMATURE_MARK), d.bindingLimit)
        }
    }

    /**
     * DER STEMPEL OHNE NULL: auch ein Zyklus, der selbst keine Null
     * entschieden hat (KEEP/NO_NEW_POSITIVE), traegt die Lage zum Translator -
     * sonst hinge der Abbruch einer LAUFENDEN Null aus dem Vorzyklus am
     * C7a-Veto, solange die Mahlzeit dosiert. Genau so lief die Null am
     * 17.08. ihre vollen 30 Minuten, und Tonis manueller Abbruch wurde im
     * Folgezyklus ueberschrieben.
     */
    @Test
    fun `auch ohne eigene Null wird die Lage gestempelt`() {
        val keep = modellNull().copy(
            tbr = FuseController.TbrAction.KEEP_CURRENT,
            zeroTempModelOnly = false,
            block = FuseController.Block.NONE,
        )
        assertTrue(MealBasalGuard.apply(keep, lage()).mealBasalProtected)
        assertFalse(MealBasalGuard.apply(keep, lage(nahTief = true)).mealBasalProtected)
    }

    /** Die Null eines gemessenen Tiefs (SAFETY_HOLD, ohne Bit) wird nie
     *  angefasst - das Modell ist ueberstimmbar, die Wirklichkeit nicht. */
    @Test
    fun `die Null eines gemessenen Tiefs wird nie gehoben`() {
        val hold = modellNull().copy(
            block = FuseController.Block.SAFETY_HOLD,
            zeroTempModelOnly = false,
        )
        val d = MealBasalGuard.apply(hold, lage())
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr)
    }

    // ---- das gemessene Nah-Tief ------------------------------------------

    /** BG und Trend muessen BEIDE sprechen - und Grenzfaelle zaehlen nicht. */
    @Test
    fun `das Nah-Tief verlangt tiefen BG UND fallenden Trend`() {
        assertTrue(MealBasalGuard.nearLowFalling(85.0, -0.5))
        assertFalse(MealBasalGuard.nearLowFalling(85.0, 0.0), "flach ist nicht fallend")
        assertFalse(MealBasalGuard.nearLowFalling(85.0, 0.5), "steigend erst recht nicht")
        assertFalse(MealBasalGuard.nearLowFalling(95.0, -2.0), "ueber der Schwelle entscheidet die Bahnregel")
        assertFalse(MealBasalGuard.nearLowFalling(MealBasalGuard.NEAR_LOW_BG_MGDL, -0.5), "die Schwelle selbst ist nicht darunter")
        assertFalse(MealBasalGuard.nearLowFalling(null, -0.5), "ohne Messwert keine Behauptung")
        assertFalse(MealBasalGuard.nearLowFalling(85.0, null))
        assertFalse(MealBasalGuard.nearLowFalling(Double.NaN, -0.5))
    }
}
