package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WAS VOM RUHE-AUSGANG AUS PHASE A BLEIBT.
 *
 * Konsolidierung 2.0: die Entscheidung (Gefahren, Ruhezaehlung, Freigabetypen)
 * ist mit dem Sofortanteil entfallen, und mit ihr ihre Tests. Geprueft wird
 * hier nur noch, was weiter gelesen wird: der fail-closed geladene Zaehler
 * (Ledger), die Parameterpruefung und der Fingerprint (Politik-Export) und
 * die Moduszahl aus der Einstellung.
 */
class UpfrontRecoveryTest {

    private val marker = 1_700_000_000_000L

    private val regelVersion = 31

    private fun ruhig(
        zyklen: Int = 3,
        version: Int = regelVersion,
    ) = UpfrontRecovery.Params.of(
        calmCycles = zyklen, minUkf = 0.05, minGuardDistanceMgdl = 5.0,
        calmTreatment = UpfrontRecovery.CalmTreatment.DEMAND_LIMITED,
        ruleSetVersion = version,
    )

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
            fingerprint = ruhig().fingerprint,
        )
        assertEquals(2, gut.streak)
        assertTrue(gut.consistent)
    }

    // ---- DIE PARAMETERPRUEFUNG ----------------------------------------

    @Test
    fun `unbrauchbare Parameter ergeben OFF statt einer erfundenen Kalibrierung`() {
        val schlecht = listOf(
            Triple(0, 0.05, 5.0), Triple(21, 0.05, 5.0),
            // EIN einzelner Ruhezyklus: der Codevertrag sagt ausdruecklich,
            // dass er nicht genuegt - eine Einstellung darf das nicht
            // unterlaufen (Toni 25.08. spaet).
            Triple(1, 0.05, 5.0),
            Triple(3, Double.NaN, 5.0), Triple(3, 2.0, 5.0), Triple(3, -2.0, 5.0),
            // NEGATIVE Mindestrate: `FLOOR_BEYOND_HORIZON` kann das aktuelle
            // Risiko aufheben, waehrend die UKF-Rate noch faellt. Eine
            // negative Schwelle liesse den Batch auf der fallenden Kurve
            // zuenden - auch knapp unter null.
            Triple(3, -0.01, 5.0),
            Triple(3, 0.05, -1.0), Triple(3, 0.05, 101.0), Triple(3, 0.05, Double.NaN),
            // BODENABSTAND unter dem untersuchten Kandidaten: bei 0 waere
            // eine Freigabe unmittelbar am Guard-Boden einstellbar.
            Triple(3, 0.05, 0.0), Triple(3, 0.05, 4.9),
        )
        schlecht.forEach { (n, u, g) ->
            val p = UpfrontRecovery.Params.of(
                n, u, g, UpfrontRecovery.CalmTreatment.DEMAND_LIMITED, regelVersion,
            )
            assertSame(UpfrontRecovery.Params.OFF, p, "($n, $u, $g) haette OFF ergeben muessen")
        }
        assertNotEquals(UpfrontRecovery.Params.OFF, ruhig(3))
        // UND DER KANDIDAT BLEIBT GUELTIG: CALM_BATCH, 3 Zyklen,
        // Mindestrate 0,00, Bodenabstand 5 - genau an den neuen Grenzen.
        val kandidat = UpfrontRecovery.Params.of(
            3, 0.0, 5.0, UpfrontRecovery.CalmTreatment.CALM_BATCH, regelVersion,
        )
        assertNotEquals(UpfrontRecovery.Params.OFF, kandidat)
        assertTrue(kandidat.enabled, "der Kandidat muss weiter einstellbar sein")
        assertEquals(2, UpfrontRecovery.Params.MIN_CALM_CYCLES)
        assertEquals(0.0, UpfrontRecovery.Params.MIN_CALM_UKF, 1e-12)
        assertEquals(5.0, UpfrontRecovery.Params.MIN_GUARD_DISTANCE_MGDL, 1e-12)
    }

    /**
     * DIE MODUSZAHL AUS DER EINSTELLUNG IST FAIL-CLOSED.
     *
     * Es gibt keinen `FuseStringKey`, die Wahl liegt also als Zahl vor. Ein
     * unbekannter oder beschaedigter Wert darf NICHT den dosierwirksamen
     * Modus ergeben - er ergibt den harmlosesten.
     */
    @Test
    fun `eine unbekannte Moduszahl ergibt den harmlosesten Modus`() {
        assertEquals(UpfrontRecovery.CalmTreatment.DEMAND_LIMITED,
                     UpfrontRecovery.CalmTreatment.ofSetting(0))
        assertEquals(UpfrontRecovery.CalmTreatment.SHIFT_TO_DEFERRED,
                     UpfrontRecovery.CalmTreatment.ofSetting(1))
        assertEquals(UpfrontRecovery.CalmTreatment.CALM_BATCH,
                     UpfrontRecovery.CalmTreatment.ofSetting(2))
        // Alles andere - negativ, zu gross, Muell aus einer Altdatei.
        listOf(-1, 3, 99, Int.MIN_VALUE, Int.MAX_VALUE).forEach {
            assertEquals(UpfrontRecovery.CalmTreatment.DEMAND_LIMITED,
                         UpfrontRecovery.CalmTreatment.ofSetting(it),
                         "Moduszahl $it darf nicht dosierwirksam werden")
        }
    }

    @Test
    fun `der Fingerprint trennt auch die Behandlung CALM_BATCH`() {
        val batch = UpfrontRecovery.Params.of(
            3, 0.0, 5.0, UpfrontRecovery.CalmTreatment.CALM_BATCH, regelVersion,
        )
        val bedarf = UpfrontRecovery.Params.of(
            3, 0.0, 5.0, UpfrontRecovery.CalmTreatment.DEMAND_LIMITED, regelVersion,
        )
        assertNotEquals(batch.fingerprint, bedarf.fingerprint) {
            "ein Moduswechsel MUSS den laufenden Ruhezaehler entwerten"
        }
    }
}
