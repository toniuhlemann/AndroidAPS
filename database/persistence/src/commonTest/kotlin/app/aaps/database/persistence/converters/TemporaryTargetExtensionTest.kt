package app.aaps.database.persistence.converters

import app.aaps.core.data.model.TT
import app.aaps.database.entities.TemporaryTarget
import kotlin.test.Test
import kotlin.test.assertEquals

internal class TemporaryTargetExtensionTest {

    @Test
    fun reasonRoundTripFromDomain() {
        TT.Reason.entries.forEach {
            assertEquals(it, it.toDb().fromDb())
        }
    }

    @Test
    fun reasonRoundTripFromEntity() {
        TemporaryTarget.Reason.entries.forEach {
            assertEquals(it, it.fromDb().toDb())
        }
    }

    @Test
    fun forkReasonsReadByTheirStoredName() {
        // Fork (IOB-Action viewer): a fork database already holds these names. Reading them must not throw.
        listOf("PEAK_STOP", "CORRECTION", "REBOUND", "BRAKE", "MEAL", "LOW_PROTECT").forEach {
            assertEquals(it, TemporaryTarget.Reason.valueOf(it).fromDb().name)
        }
    }
}
