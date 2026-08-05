package app.aaps.fuse.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.plugins.insulin.InsulinOrefRapidActingPlugin
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

/**
 * KC2-61: der Einheitskern deckt sich an ALLEN Stuetzstellen mit dem aktiven
 * AAPS-Insulinplugin — geprueft gegen die ECHTE Klasse, nicht gegen eine Kopie.
 *
 * Und KC2-60 von der anderen Seite: hier ist der Beweis, dass
 * `iobCalcForTreatment` vor der Lieferung KEINE Null liefert, sondern eine
 * falsch gerichtete Wirkung. Deshalb setzt der pure Kern sie selbst.
 */
class AapsUnitInsulinSamplerTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var config: Config
    @Mock lateinit var hardLimits: HardLimits
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var insulin: InsulinOrefRapidActingPlugin

    private val dia = 9.0
    private val deliveryTs = 1_700_000_000_000L

    @BeforeEach
    fun setup() {
        whenever(rh.gs(org.mockito.kotlin.any<Int>())).thenReturn("")
        insulin = InsulinOrefRapidActingPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
    }

    private fun sampler(ts: Long = deliveryTs) = AapsUnitInsulinSampler(insulin, dia, ts)

    @Test
    fun `KC2-61 der Kern gibt an jeder Stuetzstelle den Wert des aktiven Plugins wieder`() {
        val model = InsulinModelProvenance("OREF_RAPID_ACTING", dia, insulin.peak, "InsulinOrefRapidActingPlugin")
        val out = UnitInsulinKernelBuilder.build(sampler(), deliveryTs, model, "OREF_RAPID_ACTING")
        assertTrue(out is KernelOutcome.Ok, "builder rejected: $out")
        val kernel = (out as KernelOutcome.Ok).kernel

        assertEquals(540, kernel.supportMin)
        for (m in 0..540) {
            val direct = insulin.iobCalcForTreatment(
                BS(timestamp = deliveryTs, amount = 1.0, type = BS.Type.SMB),
                deliveryTs + m * 60_000L, dia
            )
            assertEquals(direct.iobContrib, kernel.iobAt(deliveryTs + m * 60_000L, 1.0), 1e-12, "iob@$m")
            assertEquals(direct.activityContrib, kernel.activityAt(deliveryTs + m * 60_000L, 1.0), 1e-12, "activity@$m")
        }

        // Plausibilitaet des echten Modells: voll bei 0, leer am DIA-Ende,
        // Aktivitaetsmaximum um den Peak.
        assertEquals(1.0, kernel.iobAt(deliveryTs, 1.0), 1e-9)
        assertEquals(0.0, kernel.iobAt(deliveryTs + 540 * 60_000L, 1.0), 0.0)
        val peakMinute = (0..540).maxByOrNull { kernel.activityAt(deliveryTs + it * 60_000L, 1.0) }!!
        assertEquals(insulin.peak, peakMinute)
    }

    @Test
    fun `KC2-60 das aktive Plugin liefert VOR der Lieferung eine falsch gerichtete Wirkung`() {
        // 30 min vor der Abgabe: die oref-Formel prueft nur `t < td`, nicht
        // `t >= 0`. Ergebnis ist eine grosse NEGATIVE Aktivitaet -> ueber
        // bgi = -activity*isf ein kraeftiger ANSTIEG. Eine Kandidatendosis
        // wuerde die Bahn anheben, bevor sie geliefert ist.
        val before = insulin.iobCalcForTreatment(
            BS(timestamp = deliveryTs, amount = 1.0, type = BS.Type.SMB),
            deliveryTs - 30 * 60_000L, dia
        )
        assertTrue(before.activityContrib < -0.001, "erwartet negative Aktivitaet, war ${before.activityContrib}")

        // GEMESSEN, nicht angenommen: der Fixblock C1 nennt nur die Aktivitaet,
        // aber auch die IOB-Seite ist vor der Lieferung falsch — 30 min VOR der
        // Abgabe meldet das Modell rund 0,89 U als bereits an Bord. Das ist ein
        // Phantom-IOB, das den Spielraum zusaetzlich verfaelscht (hier zufaellig
        // in die vorsichtige Richtung, was es nicht besser macht: es ist
        // schlicht keine Aussage ueber die Wirklichkeit).
        assertTrue(before.iobContrib > 0.5, "erwartet Phantom-IOB vor der Lieferung, war ${before.iobContrib}")
        assertTrue(before.iobContrib < 1.0)

        // Der Kern setzt die Null selbst - und fragt gar nicht erst zurueck.
        val model = InsulinModelProvenance("OREF_RAPID_ACTING", dia, insulin.peak, "InsulinOrefRapidActingPlugin")
        val kernel = (UnitInsulinKernelBuilder.build(sampler(), deliveryTs, model, "x") as KernelOutcome.Ok).kernel
        assertEquals(0.0, kernel.activityAt(deliveryTs - 30 * 60_000L, 1.0), 0.0)
        assertEquals(0.0, kernel.iobAt(deliveryTs - 30 * 60_000L, 1.0), 0.0)
    }

    @Test
    fun `der Sampler fragt niemals vor der Lieferung an`() {
        val ex = runCatching { sampler().sampleAfterDelivery(1.0, -1) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "erwartet IllegalArgumentException, war $ex")
    }

    @Test
    fun `die Dosis skaliert linear - das ist die Voraussetzung des Einheitskerns`() {
        val s = sampler()
        for (m in listOf(0, 10, 75, 200, 539)) {
            val one = s.sampleAfterDelivery(1.0, m)
            val quarter = s.sampleAfterDelivery(0.25, m)
            assertEquals(0.25 * one.iobU, quarter.iobU, 1e-15, "iob@$m")
            assertEquals(0.25 * one.activityUPerMin, quarter.activityUPerMin, 1e-15, "activity@$m")
        }
    }
}
