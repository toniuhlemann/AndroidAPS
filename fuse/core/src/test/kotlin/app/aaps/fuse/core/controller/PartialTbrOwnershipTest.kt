package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BESITZ UND ENDE DER EIGENEN TEIL-TBR - der geschlossene Zustandsvertrag.
 *
 * Zwei Fehlerrichtungen, und sie sind NICHT gleich schlimm:
 *  - eine FREMDE Absenkung faelschlich beenden = ungefragt Insulin
 *    erhoehen. Das darf nie passieren.
 *  - eine EIGENE faelschlich stehen lassen = laenger weniger geben, und
 *    der SMB bleibt dabei gesperrt. Unschoen, aber sicher.
 * Jede Zweifelsentscheidung faellt deshalb auf "fremd".
 */
class PartialTbrOwnershipTest {

    private val schritt = 0.05
    private val t0 = 1_700_000_000_000L
    private val cfg = TbrPolicy.Config(basalStepUPerH = schritt)
    private val profil = 0.60

    private fun eigen(rate: Double = 0.30, ts: Long = t0, dauer: Int = 30) =
        PartialTbrOwnership.Own(rate, ts, dauer)

    private fun laufend(rate: Double, restMin: Int, typ: TbrPolicy.SourceType = TbrPolicy.SourceType.TEMP_BASAL) =
        TbrPolicy.Current(rate, restMin, typ)

    private fun min(n: Int) = t0 + n * 60_000L

    // =====================================================================
    // DIE ERKENNUNG
    // =====================================================================

    @Test
    fun `unsere eigene Teilrate wird an Rate UND Restlaufzeit erkannt`() {
        // 10 min nach dem Setzen: 20 min Rest bei 30 min Dauer.
        assertTrue(PartialTbrOwnership.isOurs(eigen(), laufend(0.30, 20), min(10), schritt))
    }

    @Test
    fun `eine fremde Absenkung mit GLEICHER Rate ist nicht unsere`() {
        // Dieselbe Rate, aber sie laeuft seit einer anderen Zeit - genau
        // dafuer ist die Restlaufzeit die zweite Bedingung. Ohne sie waere
        // hier eine fremde TBR beendet worden.
        assertFalse(PartialTbrOwnership.isOurs(eigen(), laufend(0.30, 5), min(10), schritt)) {
            "20 min erwartet, 5 min gefunden - das ist nicht dieselbe TBR"
        }
        assertFalse(PartialTbrOwnership.isOurs(eigen(), laufend(0.30, 28), min(10), schritt))
    }

    @Test
    fun `eine abweichende Rate ist nicht unsere`() {
        assertFalse(PartialTbrOwnership.isOurs(eigen(0.30), laufend(0.20, 20), min(10), schritt))
        // aber innerhalb eines halben Pumpenschritts schon - die Pumpe
        // rundet, und 0,001 Unterschied ist dieselbe Rate.
        assertTrue(PartialTbrOwnership.isOurs(eigen(0.30), laufend(0.31, 20), min(10), schritt))
    }

    @Test
    fun `die Toleranz der Restlaufzeit ist eng und benannt`() {
        assertEquals(3, PartialTbrOwnership.REMAINING_TOLERANCE_MIN)
        assertTrue(PartialTbrOwnership.isOurs(eigen(), laufend(0.30, 23), min(10), schritt)) { "+3" }
        assertTrue(PartialTbrOwnership.isOurs(eigen(), laufend(0.30, 17), min(10), schritt)) { "-3" }
        assertFalse(PartialTbrOwnership.isOurs(eigen(), laufend(0.30, 24), min(10), schritt)) { "+4" }
        assertFalse(PartialTbrOwnership.isOurs(eigen(), laufend(0.30, 16), min(10), schritt)) { "-4" }
    }

    @Test
    fun `ohne Nachweis gehoert nichts uns`() {
        assertFalse(PartialTbrOwnership.isOurs(null, laufend(0.30, 20), min(10), schritt))
    }

    @Test
    fun `ein FAKE_EXTENDED ist nie unsere Teilrate`() {
        assertFalse(
            PartialTbrOwnership.isOurs(
                eigen(), laufend(0.30, 20, TbrPolicy.SourceType.FAKE_EXTENDED), min(10), schritt)
        ) { "ein als TBR gelesener Extended Bolus wird nur gelesen, nie gestellt" }
    }

    @Test
    fun `ein unbrauchbarer Nachweis gilt als keiner`() {
        for ((was, own) in listOf(
            "Rate 0" to eigen(rate = 0.0),
            "Rate NaN" to eigen(rate = Double.NaN),
            "kein Zeitstempel" to eigen(ts = 0L),
            "Dauer 0" to eigen(dauer = 0),
        )) {
            assertFalse(own.valid, was)
            assertFalse(PartialTbrOwnership.isOurs(own, laufend(0.30, 20), min(10), schritt), was)
        }
    }

    @Test
    fun `nach Ablauf der Dauer gehoert dort nichts mehr uns`() {
        assertFalse(PartialTbrOwnership.isOurs(eigen(), laufend(0.30, 0), min(30), schritt))
        assertTrue(PartialTbrOwnership.expired(eigen(), min(34)))
        assertFalse(PartialTbrOwnership.expired(eigen(), min(32))) { "30 + 3 Toleranz" }
    }

    @Test
    fun `eine rueckwaerts laufende Uhr ergibt keinen Besitz`() {
        assertFalse(PartialTbrOwnership.isOurs(eigen(ts = min(10)), laufend(0.30, 20), t0, schritt))
    }

    // =====================================================================
    // DER AUSGANG IN DER TABELLE
    // =====================================================================

    private fun keepMit(current: TbrPolicy.Current?, own: PartialTbrOwnership.Own?, ts: Long) =
        TbrPolicy.decide(
            TbrPolicy.Intent.KEEP, current, profil, cfg,
            ownPartial = own, nowTs = ts,
        )

    @Test
    fun `PARTIAL nach RELEASED beendet die EIGENE Teilrate und sperrt dabei den SMB`() {
        val d = keepMit(laufend(0.30, 20), eigen(), min(10))
        assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome) { "Rate 0, Dauer 0 = Abbruch, zurueck aufs Profil" }
        assertEquals(TbrPolicy.KEEP_END_OWN_PARTIAL_REASON, d.reason)
        assertEquals(TbrPolicy.SmbBlockCause.PARTIAL_ENDING, d.smbBlockCause) {
            "der Abbruch HEBT die Rate an - Basal anheben und SMB duerfen nicht denselben Zyklus verlassen"
        }
    }

    @Test
    fun `eine FREMDE Absenkung wird niemals beendet`() {
        for ((was, own) in listOf(
            "kein Nachweis" to null,
            "andere Rate" to eigen(0.45),
            "andere Restlaufzeit" to eigen(ts = min(-20)),
        )) {
            val d = keepMit(laufend(0.30, 20), own, min(10))
            assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome, was)
            assertEquals("KEEP", d.reason, was)
            assertEquals(TbrPolicy.SmbBlockCause.NONE, d.smbBlockCause, "$was: und der SMB bleibt frei")
        }
    }

    @Test
    fun `nach bestaetigtem Ende ist der SMB wieder frei`() {
        // Der Abbruch hat gewirkt: es laeuft nichts mehr. Der Nachweis
        // passt dann nicht mehr, also KEEP ohne Sperre.
        val d = keepMit(null, eigen(), min(11))
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertEquals(TbrPolicy.SmbBlockCause.NONE, d.smbBlockCause)
    }

    @Test
    fun `ein FEHLGESCHLAGENER Abbruch wiederholt sich und haelt den SMB gesperrt`() {
        // Zyklus fuer Zyklus dieselbe Antwort, solange unsere Rate laeuft.
        listOf(10 to 20, 11 to 19, 12 to 18).forEach { (m, rest) ->
            val d = keepMit(laufend(0.30, rest), eigen(), min(m))
            assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome) { "Minute $m" }
            assertEquals(TbrPolicy.SmbBlockCause.PARTIAL_ENDING, d.smbBlockCause) { "Minute $m" }
        }
    }

    @Test
    fun `eine arbeitende Pumpe unterdrueckt das Kommando, nicht die SMB-Sperre`() {
        val d = TbrPolicy.decide(
            TbrPolicy.Intent.KEEP, laufend(0.30, 20), profil, cfg,
            pumpBusy = true, ownPartial = eigen(), nowTs = min(10),
        )
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome) { "keine zweite Anweisung an eine arbeitende Pumpe" }
        assertEquals(TbrPolicy.SmbBlockCause.PUMP_BUSY, d.smbBlockCause) { "und gesperrt bleibt es erst recht" }
        assertTrue(d.reason.startsWith("PUMP_BUSY|"), d.reason)
        assertTrue(d.reason.contains(TbrPolicy.KEEP_END_OWN_PARTIAL_REASON), d.reason)
    }

    @Test
    fun `eine laufende NULL wird weiterhin als solche beendet, nicht als Teilrate`() {
        val d = keepMit(laufend(0.0, 20), eigen(), min(10))
        assertEquals(TbrPolicy.KEEP_CANCEL_STALE_ZERO_REASON, d.reason) {
            "die Null hat ihren eigenen Weg - der Besitzpfad darf ihn nicht ueberschreiben"
        }
    }

    @Test
    fun `eine POSITIVE TBR wird weiterhin als solche beendet`() {
        val d = keepMit(laufend(1.20, 20), eigen(), min(10))
        assertEquals("KEEP_CANCEL_POSITIVE", d.reason)
    }

    @Test
    fun `PARTIAL nach ZERO ersetzt im SELBEN Zyklus durch die Null`() {
        // Kein Auslaufen der Teilrate: SAFETY_ZERO zieht eine laufende
        // abgesenkte Rate sofort auf 0.
        val d = TbrPolicy.decide(
            TbrPolicy.Intent.SAFETY_ZERO, laufend(0.30, 20), profil, cfg,
            ownPartial = eigen(), nowTs = min(10),
        )
        assertEquals(TbrPolicy.Outcome.Request(0.0, cfg.defaultDurationMin), d.outcome)
        assertEquals("SAFETY_ZERO_REPLACE", d.reason)
        assertEquals(TbrPolicy.SmbBlockCause.SAFETY_ZERO, d.smbBlockCause)
    }

    @Test
    fun `Schalter aus unter aktivem Latch bleibt die Null - der Besitz aendert daran nichts`() {
        // Der Schalter wirkt im Tor, nicht in der Tabelle: ohne Teilstufe
        // kommt SAFETY_ZERO an, und das ist genau die geforderte Antwort.
        val d = TbrPolicy.decide(
            TbrPolicy.Intent.SAFETY_ZERO, laufend(0.30, 20), profil, cfg,
            ownPartial = eigen(), nowTs = min(10),
        )
        assertEquals(0.0, (d.outcome as TbrPolicy.Outcome.Request).rateUPerH, 1e-12)
    }

    @Test
    fun `Schalter aus nach Latch-Freigabe beendet die nachgewiesen eigene Teilrate`() {
        // Nach der Freigabe kommt KEEP - und dort greift der Besitzpfad.
        val d = keepMit(laufend(0.30, 20), eigen(), min(10))
        assertEquals(TbrPolicy.KEEP_END_OWN_PARTIAL_REASON, d.reason)
    }

    @Test
    fun `nach einem Neustart bleibt der Nachweis gueltig - sonst waere sie ploetzlich fremd`() {
        // Der Nachweis ist ein reiner Datensatz; ein Prozessstart aendert
        // nichts an ihm. Genau das ist der Grund, ihn zu persistieren.
        val nachNeustart = PartialTbrOwnership.Own(0.30, t0, 30)
        assertTrue(PartialTbrOwnership.isOurs(nachNeustart, laufend(0.30, 20), min(10), schritt))
        assertEquals(TbrPolicy.KEEP_END_OWN_PARTIAL_REASON, keepMit(laufend(0.30, 20), nachNeustart, min(10)).reason)
    }

    @Test
    fun `die Blockursachen sind vollstaendig aufgezaehlt`() {
        // Waechter: eine neue Ursache muss bewusst aufgenommen werden.
        assertEquals(9, TbrPolicy.SmbBlockCause.entries.size)
        assertTrue(TbrPolicy.SmbBlockCause.entries.contains(TbrPolicy.SmbBlockCause.PARTIAL_ENDING))
    }
}
