package app.aaps.fuse.core.controller

import app.aaps.fuse.core.controller.PartialTbrOwnership.Ending
import app.aaps.fuse.core.controller.PartialTbrOwnership.Identity
import app.aaps.fuse.core.controller.PartialTbrOwnership.State
import app.aaps.fuse.core.controller.PartialTbrOwnership.View
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EINE MAHLZEITENDOSIS DARF NICHT AN EINER PHANTOM-TEILSTUFE STERBEN.
 *
 * ===================================================================
 * DER BEOBACHTETE FEHLER
 * ===================================================================
 * Ein neuer Mahlzeitenmarker gab die vorgesehene Direktdosis frei, Pumpen-
 * und Publikationstor waren offen. Danach setzte die Teilbasal-Logik die
 * Bolusmenge auf null - obwohl im selben Zyklus
 *
 *   - die Suche bereits das VOLLE Profilbasal trug,
 *   - autoritativ KEINE Temp-Basalrate lief,
 *   - und KEIN eigener Teilbasal-Vorgang offen war.
 *
 * Die Tabelle antwortete folgerichtig mit `PARTIAL_ALREADY_AT_PROFILE`,
 * also mit gar keinem Kommando. Der Bolus war da schon weg: das
 * Teilstufen-Flag nullte ihn, BEVOR feststand, ob die Stufe ueberhaupt
 * etwas anfordert.
 *
 * ===================================================================
 * WAS DIESE DATEI FESTHAELT
 * ===================================================================
 * Die Bedingung "die Stufe erzeugt in diesem Zyklus kein Kommando" als
 * reine Funktion, mit allen Gegenfaellen, die sie NICHT erfuellen duerfen.
 * Sie ist ausdruecklich KEINE "Mahlzeit darf immer"-Ausnahme: eine echte
 * laufende Teilrate, eine offene Anforderung, eine unbestaetigte
 * Abbruchphase und eine unbrauchbare Pumpensicht behalten ihren Schutz.
 *
 * Alle Werte hier sind synthetisch.
 */
class MahlzeitTrotzTeilstufeTest {

    private val schritt = 0.05
    private val profil = 0.60
    private val dauer = 30

    private fun ohneAktion(
        wunsch: Double = profil,
        basis: Double = profil,
        view: View = View.Authoritative(null),
        state: State = State(),
    ) = PartialTbrOwnership.ohneAktion(
        wunschRateUPerH = wunsch,
        aapsBasisUPerH = basis,
        basalStepUPerH = schritt,
        durationMin = dauer,
        view = view,
        state = state,
    )

    private fun laufend(rate: Double) = TbrPolicy.Current(
        absoluteRateUPerH = rate, remainingMin = 20,
        sourceType = TbrPolicy.SourceType.TEMP_BASAL,
    )

    // =====================================================================
    // DER FALL, UM DEN ES GEHT
    // =====================================================================

    @Test
    fun `Profil erreicht, keine TBR, leerer Besitz - die Stufe fordert nichts an`() {
        assertTrue(ohneAktion()) {
            "genau die beobachtete Lage: nichts zu tun, also nichts zu schuetzen"
        }
    }

    @Test
    fun `auch eine Rate innerhalb eines Pumpenschritts unter Profil zaehlt als Profil`() {
        // Die Tabelle misst gegen dieselbe Schwelle - `abs(rate - basis) < schritt`.
        assertTrue(ohneAktion(wunsch = profil - schritt / 2.0))
        assertFalse(ohneAktion(wunsch = profil - schritt)) {
            "genau einen Schritt darunter ist eine echte Teilrate"
        }
    }

    // =====================================================================
    // DIE GEGENFAELLE - JEDER BEHAELT SEINEN SCHUTZ
    // =====================================================================

    @Test
    fun `eine echte Teilrate ist keine Nichtaktion`() {
        assertFalse(ohneAktion(wunsch = 0.35)) {
            "hier geht ein Kommando hinaus - der Riegel bleibt"
        }
    }

    @Test
    fun `eine laufende TBR ist keine Nichtaktion - auch nicht auf Profilhoehe`() {
        assertFalse(ohneAktion(view = View.Authoritative(laufend(profil)))) {
            "eine laufende TBR muss abgebrochen werden, das IST eine Aktion"
        }
        assertFalse(ohneAktion(view = View.Authoritative(laufend(0.30))))
        assertFalse(ohneAktion(view = View.Authoritative(laufend(0.0)))) {
            "eine laufende Schutz-Null erst recht"
        }
    }

    @Test
    fun `eine unbestaetigte Pumpensicht ist keine Nichtaktion`() {
        assertFalse(ohneAktion(view = View.Unknown)) {
            "UNKNOWN heisst nicht belastbar bekannt - das traegt keine Feststellung"
        }
    }

    @Test
    fun `ein eigener bestaetigter Besitz ist keine Nichtaktion`() {
        val besitz = State(confirmedRunning = Identity(0.35, 1_000L, dauer))
        assertFalse(ohneAktion(state = besitz))
    }

    @Test
    fun `eine offene Anforderung ist keine Nichtaktion`() {
        val offen = State(pendingRequest = Identity(0.35, 1_000L, dauer), pendingAttempts = 1)
        assertFalse(ohneAktion(state = offen)) {
            "ein unbestaetigtes SET darf nicht durch einen Bolus verdraengt werden"
        }
    }

    @Test
    fun `eine laufende Abbruchphase ist keine Nichtaktion`() {
        val ende = State(ending = Ending(sinceTs = 1_000L, attempts = 1))
        assertFalse(ohneAktion(state = ende)) {
            "der Cancel ist unbestaetigt - bis dahin bleibt der Schutz"
        }
    }

    // =====================================================================
    // UND DIE TABELLE MUSS DIESELBE ANTWORT GEBEN
    // =====================================================================
    //
    // Der Riegel sitzt an ZWEI Stellen: der Runner nullt den Bolus, und der
    // Translator nullt ihn ein zweites Mal anhand des Blockgrundes aus der
    // Tabelle. Ein Fix an nur einer Stelle waere wirkungslos - deshalb
    // steht der Blockgrund hier mit im Vertrag.

    private fun tabelle(ohneAktion: Boolean, current: TbrPolicy.Current? = null) =
        TbrPolicy.decide(
            intent = TbrPolicy.Intent.PARTIAL_BASAL,
            current = current,
            scheduledBasalUPerH = profil,
            cfg = TbrPolicy.Config(basalStepUPerH = schritt),
            partialRateUPerH = profil,
            pumpBaseBasalUPerH = profil,
            ohneAktion = ohneAktion,
        )

    @Test
    fun `ohne Aktion faellt der SMB-Blockgrund der Tabelle weg`() {
        val d = tabelle(ohneAktion = true)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome) { "es geht nichts hinaus" }
        assertTrue(d.reason.contains("ALREADY_AT_PROFILE")) { d.reason }
        assertEquals(TbrPolicy.SmbBlockCause.NONE, d.smbBlockCause) {
            "kein Kommando, kein Riegel - sonst nullt der Translator den Bolus doch"
        }
    }

    @Test
    fun `ohne die Feststellung bleibt der Blockgrund stehen - das war der Fehler`() {
        val d = tabelle(ohneAktion = false)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertEquals(TbrPolicy.SmbBlockCause.PARTIAL_RECOVERY, d.smbBlockCause) {
            "der alte Zustand, hier nur noch als Gegenprobe"
        }
    }

    @Test
    fun `mit laufender TBR bleibt der Riegel, auch wenn ohneAktion faelschlich gesetzt waere`() {
        // Doppelter Boden: die Tabelle entscheidet ueber `current` selbst und
        // erreicht den Zweig gar nicht erst.
        val d = tabelle(ohneAktion = true, current = laufend(0.30))
        assertTrue(d.smbBlockCause != TbrPolicy.SmbBlockCause.NONE) {
            "eine laufende Absenkung behaelt ihren Schutz: ${d.reason}"
        }
    }
}
