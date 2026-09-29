package app.aaps.fuse.core.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * DIE ZUSICHERUNGEN DES ENTLASTUNGS-BEWEISES.
 *
 * Die teuerste ist nicht "erkennt den Fall", sondern "schweigt im Zweifel":
 * jede faelschliche Entlastung laesst FUSE mit zu wenig Haftung rechnen und
 * damit ZU VIEL dosieren. Deshalb pruefen die meisten Tests hier, dass
 * `reasonFor` NICHTS zurueckgibt.
 */
class NotSentProofTest {

    private fun beobachtung(
        correlated: Boolean = true,
        ledgerPublishedU: Double? = 0.20,
        gateStripped: Boolean = false,
        gateSealed: Boolean = false,
        gatePersistFailed: Boolean = false,
        aapsConstrainedU: Double? = 0.20,
        smbSetByPumpPresent: Boolean? = true,
    ) = NotSentProof.Observation(
        correlated, ledgerPublishedU, gateStripped, gateSealed,
        gatePersistFailed, aapsConstrainedU, smbSetByPumpPresent,
    )

    /** DER GEMESSENE FALL: 19:07, Menge stand, Apply-Block nie betreten. */
    @Test
    fun `ein nie betretener Apply-Block beweist die Nichtausfuehrung`() {
        val o = beobachtung(smbSetByPumpPresent = false, aapsConstrainedU = 0.20)
        assertEquals(QueueRejectReason.BOLUS_IN_QUEUE, NotSentProof.reasonFor(o))
    }

    @Test
    fun `ein gesiegelter Strip des eigenen Gates beweist es ebenfalls`() {
        val o = beobachtung(gateStripped = true, gateSealed = true)
        assertEquals(QueueRejectReason.GATE_BLOCKED, NotSentProof.reasonFor(o))
    }

    @Test
    fun `eine von AAPS genullte Menge beweist es ebenfalls`() {
        val o = beobachtung(aapsConstrainedU = 0.0)
        assertEquals(QueueRejectReason.CONSTRAINT_ZERO, NotSentProof.reasonFor(o))
    }

    /** DER NORMALFALL - ein regulaer abgegebener Bolus darf NIE entlasten. */
    @Test
    fun `ein normal ausgefuehrter Zyklus entlastet nichts`() {
        assertNull(NotSentProof.reasonFor(beobachtung()))
    }

    /**
     * OHNE KORRELATION GAR NICHTS. Ein fremder oder veralteter `lastRun`
     * beschreibt womoeglich einen ganz anderen Zyklus - er darf keine Haftung
     * loeschen, auch wenn alle uebrigen Anzeichen passen.
     */
    @Test
    fun `ohne Identitaetsprobe wird nie entlastet`() {
        for (o in listOf(
            beobachtung(correlated = false, smbSetByPumpPresent = false),
            beobachtung(correlated = false, gateStripped = true, gateSealed = true),
            beobachtung(correlated = false, aapsConstrainedU = 0.0),
        )) assertNull(NotSentProof.reasonFor(o))
    }

    /** Ein ungesiegelter Strip ist kein Beleg - der Beschluss steht dann nicht
     *  durabel fest. */
    @Test
    fun `ein ungesiegelter Strip beweist nichts`() {
        assertNull(NotSentProof.reasonFor(beobachtung(gateStripped = true, gateSealed = false)))
    }

    /** Ein gescheitertes Festschreiben laesst den Zustand ungewiss - dann gilt
     *  auch ein sonst gueltiger Beleg nicht. */
    @Test
    fun `bei gescheitertem Persist wird nie entlastet`() {
        for (o in listOf(
            beobachtung(gatePersistFailed = true, gateStripped = true, gateSealed = true),
            beobachtung(gatePersistFailed = true, smbSetByPumpPresent = false),
            beobachtung(gatePersistFailed = true, aapsConstrainedU = 0.0),
        )) assertNull(NotSentProof.reasonFor(o))
    }

    /** Ist die Angabe nicht auswertbar, gilt sie nicht als Beweis. */
    @Test
    fun `eine unbekannte Apply-Angabe beweist nichts`() {
        assertNull(NotSentProof.reasonFor(beobachtung(smbSetByPumpPresent = null)))
    }

    /** Ohne gebuchte Menge gibt es nichts zu entlasten. */
    @Test
    fun `ohne gebuchte Menge passiert nichts`() {
        for (m in listOf(null, 0.0, -0.1, Double.NaN)) {
            assertNull(NotSentProof.reasonFor(beobachtung(ledgerPublishedU = m, smbSetByPumpPresent = false)))
        }
    }

    /**
     * REGEL C BRAUCHT EINE UEBRIGE MENGE. War die Menge ohnehin schon genullt,
     * greift Regel B - die Reihenfolge darf sich nicht verschieben, sonst
     * traegt der schwaechere Beleg einen Fall, den der staerkere erklaert.
     */
    @Test
    fun `die Reihenfolge der Belege bleibt stabil`() {
        // Strip schlaegt alles
        assertEquals(
            QueueRejectReason.GATE_BLOCKED,
            NotSentProof.reasonFor(beobachtung(gateStripped = true, gateSealed = true, aapsConstrainedU = 0.0, smbSetByPumpPresent = false)),
        )
        // ohne Strip: die Nullung vor dem Apply-Block
        assertEquals(
            QueueRejectReason.CONSTRAINT_ZERO,
            NotSentProof.reasonFor(beobachtung(aapsConstrainedU = 0.0, smbSetByPumpPresent = false)),
        )
    }

    /** Ohne auswertbare Constraints-Zahl traegt Regel C nicht: dann ist nicht
     *  bekannt, ob ueberhaupt eine Menge uebrig war. */
    @Test
    fun `ohne Constraints-Zahl traegt der Apply-Beleg nicht`() {
        assertNull(NotSentProof.reasonFor(beobachtung(aapsConstrainedU = null, smbSetByPumpPresent = false)))
        assertNull(NotSentProof.reasonFor(beobachtung(aapsConstrainedU = Double.NaN, smbSetByPumpPresent = false)))
    }

    // ---- Beleg D (KI-171): AAPS hat die Pumpe nie angesprochen ------------

    private fun nieGesendet(
        identityMatched: Boolean = true,
        ledgerPublishedU: Double? = 0.35,
        requestedU: Double? = 0.35,
    ) = NotSentProof.PumpNeverCalled(identityMatched, ledgerPublishedU, requestedU)

    /** DER GEMESSENE FALL der Nacht 25./26.09.: 0,35 U publiziert, von AAPS
     *  als "too old" verworfen, die Pumpe nie angesprochen. */
    @Test
    fun `eine nie an die Pumpe gegangene SMB entlastet ihre Zeile`() {
        assertEquals(NotSentProof.PUMP_NEVER_CALLED, NotSentProof.reasonForPumpNeverCalled(nieGesendet()))
    }

    /** Der Grund wird als OTHER persistiert - ein aelterer Build kann ihn lesen. */
    @Test
    fun `der Grund bleibt rueckflashfest`() {
        assertEquals(QueueRejectReason.OTHER, NotSentProof.PUMP_NEVER_CALLED)
    }

    /** OHNE IDENTITAET GAR NICHTS: eine Ablehnung, die nicht per Referenz zu
     *  dieser RT-Instanz gehoert, beschreibt womoeglich eine ganz andere Zeile. */
    @Test
    fun `ohne Identitaet wird nie entlastet`() {
        assertNull(NotSentProof.reasonForPumpNeverCalled(nieGesendet(identityMatched = false)))
    }

    @Test
    fun `ohne gebuchte oder angeforderte Menge passiert nichts`() {
        for (m in listOf(null, 0.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(NotSentProof.reasonForPumpNeverCalled(nieGesendet(ledgerPublishedU = m)))
            assertNull(NotSentProof.reasonForPumpNeverCalled(nieGesendet(requestedU = m)))
        }
    }

    /** AAPS darf nach seinen Constraints WENIGER senden wollen - dann ist fuer
     *  diese RT trotzdem nichts geflossen, die ganze Zeile wird frei. */
    @Test
    fun `eine von AAPS gekuerzte Anforderung entlastet die ganze Zeile`() {
        assertEquals(
            NotSentProof.PUMP_NEVER_CALLED,
            NotSentProof.reasonForPumpNeverCalled(nieGesendet(ledgerPublishedU = 0.35, requestedU = 0.30)),
        )
    }

    /** MEHR als publiziert passt nicht zu dieser Zeile: dann nichts beweisen. */
    @Test
    fun `eine groessere Anforderung als publiziert beweist nichts`() {
        assertNull(NotSentProof.reasonForPumpNeverCalled(nieGesendet(ledgerPublishedU = 0.35, requestedU = 0.40)))
    }
}
