package app.aaps.plugins.aps.iobaction

import app.aaps.core.keys.DoubleKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Grenzen fuer die Double-wertigen Capabilities. Die Tests pinnen bewusst NICHT die Zahlen,
 * sondern die ABLEITUNG aus den Key-Definitionen — aendert ein Upstream-Merge eine Grenze,
 * wandert die Policy mit und der Hash-Test wird laut, statt dass eine Zahl still veraltet.
 */
class ValueOverlayPolicyTest {

    private val P = ValueOverlayPolicy

    @Test fun `Ratio-Grenzen kommen aus der Preference-Definition`() {
        val key = DoubleKey.ApsAutoIsfSmbDeliveryRatio
        assertThat(P.isRatioAllowed(P.scaled(key.min))).isTrue()
        assertThat(P.isRatioAllowed(P.scaled(key.max))).isTrue()
        assertThat(P.isRatioAllowed(P.scaled(key.min) - 1)).isFalse()
        assertThat(P.isRatioAllowed(P.scaled(key.max) + 1)).isFalse()
    }

    @Test fun `jedes Gewicht haelt seinen EIGENEN Bereich`() {
        P.WEIGHT_KEYS.forEach { (field, key) ->
            assertThat(P.areWeightsAllowed(mapOf(field to P.scaled(key.min)))).isTrue()
            assertThat(P.areWeightsAllowed(mapOf(field to P.scaled(key.max)))).isTrue()
            assertThat(P.areWeightsAllowed(mapOf(field to P.scaled(key.max) + 1))).isFalse()
            assertThat(P.areWeightsAllowed(mapOf(field to -1L))).isFalse()
        }
    }

    @Test fun `pp bleibt bei 0,15 - der Kanal baut die 0,2 der Automation NICHT nach`() {
        // ActionSetPpWeight bietet maxVal 0.2 an, DoubleKey deklariert 0.15, und nichts klemmt.
        // Der Kanal darf hier bewusst WENIGER als die Automation.
        assertThat(DoubleKey.ApsAutoIsfPpWeight.max).isEqualTo(0.15)
        assertThat(P.areWeightsAllowed(mapOf(P.F_PP to P.scaled(0.15)))).isTrue()
        assertThat(P.areWeightsAllowed(mapOf(P.F_PP to P.scaled(0.2)))).isFalse()
    }

    @Test fun `Teil-Nutzlast - ein einzelnes Gewicht genuegt`() {
        assertThat(P.areWeightsAllowed(mapOf(P.F_ACCE to 230L))).isTrue()
        assertThat(P.areWeightsAllowed(mapOf(P.F_DURA to 800L))).isTrue()
        assertThat(P.areWeightsAllowed(mapOf(P.F_ACCE to 230L, P.F_PP to 30L, P.F_DURA to 800L))).isTrue()
    }

    @Test fun `leere Nutzlast und unbekannte Felder werden abgelehnt`() {
        assertThat(P.areWeightsAllowed(emptyMap())).isFalse()
        assertThat(P.areWeightsAllowed(mapOf("gibtsNicht" to 100L))).isFalse()
        // ein gueltiges Feld heilt ein ungueltiges nicht
        assertThat(P.areWeightsAllowed(mapOf(P.F_ACCE to 230L, "gibtsNicht" to 100L))).isFalse()
    }

    @Test fun `Skalierung ist verlustfrei fuer die feinsten UI-Schritte`() {
        listOf(0.01, 0.03, 0.1, 0.15, 0.23, 0.8, 1.0, 2.0, 3.0).forEach { v ->
            assertThat(P.unscaled(P.scaled(v))).isWithin(1e-9).of(v)
        }
    }

    @Test fun `Toleranzvergleich ueberlebt den Float-Rundtrip aus den Prefs`() {
        // Reale Werte aus dem Config-Export: als Float gespeichert, als Double gelesen.
        assertThat(P.sameValue(0.23, 0.23000000417232513)).isTrue()
        assertThat(P.sameValue(0.8, 0.800000011920929)).isTrue()
        assertThat(P.sameValue(0.03, 0.029999999329447746)).isTrue()
        // ein echter Unterschied bleibt einer
        assertThat(P.sameValue(0.23, 0.24)).isFalse()
    }

    @Test fun `Policy-Hashes sind stabil, verschieden und tragen die Grenzen`() {
        assertThat(P.ratioHash()).hasLength(64)
        assertThat(P.weightsHash()).hasLength(64)
        assertThat(P.ratioHash()).isNotEqualTo(P.weightsHash())
        assertThat(P.ratioHash()).isNotEqualTo(LocalCommandIobthPolicy.hash())
        assertThat(P.weightsHash()).isNotEqualTo(LocalCommandAutoStatePolicy.hash())
        // Die kanonische Form nennt jedes Feld UND seinen Bereich — eine Grenzaenderung
        // upstream aendert damit zwingend den Hash (neue Kohorte statt stiller Drift).
        val canon = P.weightsCanonical()
        P.WEIGHT_KEYS.forEach { (field, key) ->
            assertThat(canon).contains("\"$field\"")
            assertThat(canon).contains("${P.scaled(key.max)}")
        }
        assertThat(P.ratioCanonical()).contains("\"${P.F_RATIO}\"")
    }
}
