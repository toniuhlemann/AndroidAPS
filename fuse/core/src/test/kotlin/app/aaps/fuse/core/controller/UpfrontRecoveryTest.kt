package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WAS VOM RUHE-AUSGANG AUS PHASE A BLEIBT.
 *
 * Konsolidierung 2.0: die Entscheidung (Gefahren, Ruhezaehlung, Freigabetypen)
 * ist mit dem Sofortanteil entfallen, mit ihr Ruheparameter, Fingerprint und
 * Behandlungswahl samt Einstellungen - und ihre Tests. Geprueft wird nur noch,
 * was weiter gelesen wird: der fail-closed geladene Zaehler (Ledger).
 */
class UpfrontRecoveryTest {

    private val marker = 1_700_000_000_000L

    // ---- DER PERSISTENZVERTRAG (fail-closed) ---------------------------

    @Test
    fun `ein unvollstaendig geladener Zaehler wird verworfen, nicht geglaubt`() {
        // Streak ohne Identitaeten - genau das, was ein halber Codec liefert.
        assertSame(UpfrontRecovery.Track.EMPTY, UpfrontRecovery.Track.ofPersisted(
            markerIdentity = 0L, streak = 5, lastAcceptedSourceTs = 0L,
            lastEvaluationTs = 0L, mode = UpfrontRecovery.TrackMode.CALM, fingerprint = "x",
        ))
        // Identitaeten ohne Streak.
        assertSame(UpfrontRecovery.Track.EMPTY, UpfrontRecovery.Track.ofPersisted(
            markerIdentity = marker, streak = 0, lastAcceptedSourceTs = 1L,
            lastEvaluationTs = 1L, mode = UpfrontRecovery.TrackMode.CALM, fingerprint = "x",
        ))
        // Streak ohne Modus.
        assertSame(UpfrontRecovery.Track.EMPTY, UpfrontRecovery.Track.ofPersisted(
            markerIdentity = marker, streak = 2, lastAcceptedSourceTs = 1L,
            lastEvaluationTs = 1L, mode = UpfrontRecovery.TrackMode.NONE, fingerprint = "x",
        ))
        // Streak ohne Fingerprint - die sechste Identitaet fehlt.
        assertSame(UpfrontRecovery.Track.EMPTY, UpfrontRecovery.Track.ofPersisted(
            markerIdentity = marker, streak = 2, lastAcceptedSourceTs = 1L,
            lastEvaluationTs = 1L, mode = UpfrontRecovery.TrackMode.CALM, fingerprint = "",
        ))
        // Davongelaufener Zaehler.
        assertSame(UpfrontRecovery.Track.EMPTY, UpfrontRecovery.Track.ofPersisted(
            markerIdentity = marker, streak = UpfrontRecovery.Track.MAX_STREAK + 1,
            lastAcceptedSourceTs = 1L, lastEvaluationTs = 1L,
            mode = UpfrontRecovery.TrackMode.CALM, fingerprint = "x",
        ))
        // Und der vollstaendige Fall kommt durch.
        val gut = UpfrontRecovery.Track.ofPersisted(
            markerIdentity = marker, streak = 2, lastAcceptedSourceTs = 1_000L,
            lastEvaluationTs = 1_000L, mode = UpfrontRecovery.TrackMode.CALM,
            fingerprint = "fp",
        )
        assertEquals(2, gut.streak)
        assertTrue(gut.consistent)
    }
}
