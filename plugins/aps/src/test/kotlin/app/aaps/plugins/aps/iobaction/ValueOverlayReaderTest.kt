package app.aaps.plugins.aps.iobaction

import app.aaps.core.interfaces.aps.AutoIsfOverrideState
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Der Lese-Pfad der Wert-Overlays — die Stelle, an der aus Infrastruktur Dosierung wird.
 *
 * Wichtigster Test ist der ERSTE: mit allen Schaltern aus muss jeder Leser exakt seine
 * Basis-Preference liefern. Diese Zusage traegt den ganzen Flash; bisher war sie begruendet,
 * hier ist sie belegt.
 */
class ValueOverlayReaderTest {

    private val base = mapOf(
        ValueOverlayPolicy.F_ACCE to 0.23,
        ValueOverlayPolicy.F_BRAKE to 0.08,
        ValueOverlayPolicy.F_PP to 0.03,
        ValueOverlayPolicy.F_DURA to 0.80,
        ValueOverlayPolicy.F_LOW to 0.70,
        ValueOverlayPolicy.F_HIGH to 0.20,
    )
    private val baseRatio = 0.20

    private fun snapshot(
        ratio: Double? = null,
        weights: Map<String, Double> = emptyMap(),
    ) = EffectiveAutoIsfSettingsProvider.Snapshot(
        iobThPercentBase = 50, iobThPercentEffective = 50,
        overrideState = AutoIsfOverrideState.NONE, leaseId = null, leaseVersion = null,
        expiresAtWallMs = null,
        smbRatioEffective = ratio, weightOverrides = weights,
    )

    // ---- OFF-Nachweis: der Kern ----

    @Test fun `ohne Snapshot liefert JEDER Leser exakt die Basis`() {
        base.forEach { (field, v) ->
            assertThat(ValueOverlayReader.weight(null, field, v)).isEqualTo(v)
        }
        assertThat(ValueOverlayReader.ratio(null, baseRatio)).isEqualTo(baseRatio)
    }

    @Test fun `Snapshot ohne Lease liefert ebenfalls exakt die Basis`() {
        // Genau das liefert der Koordinator bei ausgeschalteten Schaltern: null + leere Map.
        val s = snapshot()
        base.forEach { (field, v) ->
            assertThat(ValueOverlayReader.weight(s, field, v)).isEqualTo(v)
        }
        assertThat(ValueOverlayReader.ratio(s, baseRatio)).isEqualTo(baseRatio)
    }

    @Test fun `die echten Live-Werte gehen unveraendert durch`() {
        // Float-Rundtrip-Werte aus Tonis Config-Export — Bit fuer Bit identisch zurueck.
        val live = mapOf(
            ValueOverlayPolicy.F_ACCE to 0.23000000417232513,
            ValueOverlayPolicy.F_DURA to 0.800000011920929,
            ValueOverlayPolicy.F_PP to 0.029999999329447746,
        )
        live.forEach { (f, v) ->
            assertThat(ValueOverlayReader.weight(null, f, v)).isEqualTo(v)
            assertThat(ValueOverlayReader.weight(snapshot(), f, v)).isEqualTo(v)
        }
    }

    // ---- Wirkung mit Lease ----

    @Test fun `Teil-Nutzlast - nur das genannte Feld wird ueberlagert`() {
        val s = snapshot(weights = mapOf(ValueOverlayPolicy.F_ACCE to 0.40))
        assertThat(ValueOverlayReader.weight(s, ValueOverlayPolicy.F_ACCE, base[ValueOverlayPolicy.F_ACCE]!!))
            .isEqualTo(0.40)
        // alle uebrigen bleiben auf ihrer Preference
        (base - ValueOverlayPolicy.F_ACCE).forEach { (field, v) ->
            assertThat(ValueOverlayReader.weight(s, field, v)).isEqualTo(v)
        }
        // und die Ratio ist davon voellig unberuehrt
        assertThat(ValueOverlayReader.ratio(s, baseRatio)).isEqualTo(baseRatio)
    }

    @Test fun `alle sechs Gewichte gleichzeitig`() {
        val over = mapOf(
            ValueOverlayPolicy.F_ACCE to 0.40, ValueOverlayPolicy.F_BRAKE to 0.04,
            ValueOverlayPolicy.F_PP to 0.05, ValueOverlayPolicy.F_DURA to 1.20,
            ValueOverlayPolicy.F_LOW to 1.00, ValueOverlayPolicy.F_HIGH to 0.40,
        )
        val s = snapshot(weights = over)
        over.forEach { (field, v) ->
            assertThat(ValueOverlayReader.weight(s, field, base[field]!!)).isEqualTo(v)
        }
    }

    @Test fun `Ratio-Lease wirkt ohne die Gewichte anzufassen`() {
        val s = snapshot(ratio = 0.30)
        assertThat(ValueOverlayReader.ratio(s, baseRatio)).isEqualTo(0.30)
        base.forEach { (field, v) ->
            assertThat(ValueOverlayReader.weight(s, field, v)).isEqualTo(v)
        }
    }

    @Test fun `ein Overlay mit dem Basiswert ist vom Nicht-Overlay ununterscheidbar - und das ist gewollt`() {
        val s = snapshot(weights = mapOf(ValueOverlayPolicy.F_DURA to 0.80))
        assertThat(ValueOverlayReader.weight(s, ValueOverlayPolicy.F_DURA, 0.80)).isEqualTo(0.80)
    }

    @Test fun `Overlay 0 wird NICHT als fehlend gelesen`() {
        // Klassische Falle: 0.0 ist ein gueltiger Gewichtswert (alle Keys erlauben min 0).
        // Ein Null-Check statt eines Map-Lookups haette hier die Basis geliefert.
        val s = snapshot(weights = mapOf(ValueOverlayPolicy.F_BRAKE to 0.0))
        assertThat(ValueOverlayReader.weight(s, ValueOverlayPolicy.F_BRAKE, 0.08)).isEqualTo(0.0)
    }
}
