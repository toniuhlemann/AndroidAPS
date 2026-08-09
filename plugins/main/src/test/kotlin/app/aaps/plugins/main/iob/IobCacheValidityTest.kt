package app.aaps.plugins.main.iob

import app.aaps.core.data.model.BS
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.plugins.main.iob.iobCobCalculator.IobCobCalculatorPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * DER CACHE DARF KEINE ERFUNDENE NULL KONSERVIEREN (Codex Re-Audit 3b5cadbf, F2).
 *
 * Die Wurzel: `calculateIobFromBolusToTime` gab ohne Profil ein vollstaendig
 * genulltes, aber ENDLICHES `IobTotal` zurueck, und
 * `calculateFromTreatmentsAndTemps` legte es fuer VERGANGENE Schluessel im
 * `iobTable` ab. Aus "ich kann es nicht rechnen" wurde damit ein dauerhaft
 * zwischengespeichertes "es ist kein Insulin an Bord" - und die Entwertung
 * (`newHistoryData`) hat keinen Anlass, es je zu entfernen.
 *
 * WARUM DIESER TEST NOETIG IST UND EINE CODEINSPEKTION NICHT REICHT: die
 * erfundene Null ist numerisch identisch mit einer echten. Der Beweis kann
 * deshalb nicht am WERT eines einzelnen Aufrufs haengen, sondern nur an der
 * FOLGE zweier Aufrufe auf DENSELBEN gerundeten Schluessel - vorher ohne
 * Profil, danach mit. Erholt sich der zweite Aufruf nicht, war der erste
 * gecacht.
 *
 * Der Schluessel liegt bewusst in der Vergangenheit (`now` der Testbasis ist
 * 2022): nur dort greift der Cache ueberhaupt (`time < System.currentTimeMillis()`).
 */
class IobCacheValidityTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var overviewData: OverviewData
    @Mock lateinit var calculationWorkflow: CalculationWorkflow
    @Mock lateinit var insulinProfileFunction: ProfileFunction
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var insulin: Insulin
    private lateinit var sut: IobCobCalculatorPlugin

    /** Der abgefragte Zeitpunkt - auf die Minute gerundet, damit
     *  `roundUpTime` ihn unveraendert laesst und Aufruf 1 und 2 sicher
     *  denselben Cache-Schluessel treffen. */
    private val toTime get() = now / 60_000L * 60_000L

    /** Ein Bolus, der zur Abfragezeit noch deutlich wirkt - er ist der
     *  Unterschied zwischen "unbekannt" und "wirklich nichts an Bord". */
    private val bolus get() = BS(timestamp = toTime - T.mins(10).msecs(), amount = 2.0, type = BS.Type.NORMAL)

    @BeforeEach
    fun setup() {
        insulin = InsulinLyumjevPlugin(rh, insulinProfileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
        whenever(activePlugin.activeInsulin).thenReturn(insulin)
        whenever(preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)).thenReturn(2.0)
        whenever(persistenceLayer.getBolusesFromTime(any(), any())).thenReturn(Single.just(listOf(bolus)))
        whenever(persistenceLayer.getExtendedBolusesStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        sut = IobCobCalculatorPlugin(
            aapsLogger, aapsSchedulers, rxBus, preferences, rh, profileFunction, activePlugin, fabricPrivacy,
            dateUtil, persistenceLayer, overviewData, calculationWorkflow, decimalFormatter, processedTbrEbData
        )
    }

    @Test
    fun `ohne Profil ist das Ergebnis ungueltig und wird nicht gecacht`() {
        whenever(profileFunction.getProfile()).thenReturn(null)
        val ohneProfil = sut.calculateFromTreatmentsAndTemps(toTime, validProfile)
        assertThat(ohneProfil.valid).isFalse()
        // Die Zahl selbst verraet den Fehler NICHT - genau das ist der Punkt.
        assertThat(ohneProfil.iob).isEqualTo(0.0)

        // RECOVERY: derselbe vergangene Schluessel, jetzt mit Profil. Waere der
        // ungueltige Wert gecacht worden, kaeme er hier unveraendert zurueck.
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        val mitProfil = sut.calculateFromTreatmentsAndTemps(toTime, validProfile)
        assertThat(mitProfil.valid).isTrue()
        assertThat(mitProfil.iob).isGreaterThan(0.5)
    }

    @Test
    fun `ein gueltiges Ergebnis wird weiterhin gecacht`() {
        // Gegenprobe: die Nicht-Cache-Regel darf nur den ungueltigen Fall
        // treffen. Ein zweiter Aufruf muss DASSELBE Objekt liefern - sonst
        // waere aus dem Sicherheitsfix eine Leistungsregression geworden.
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        val erst = sut.calculateFromTreatmentsAndTemps(toTime, validProfile)
        val zweit = sut.calculateFromTreatmentsAndTemps(toTime, validProfile)
        assertThat(erst.valid).isTrue()
        assertThat(zweit).isSameInstanceAs(erst)
    }

    @Test
    fun `die Ungueltigkeit ueberlebt die Zusammenfuehrung mit dem Basalanteil`() {
        // `calculateFromTreatmentsAndTemps` kombiniert Bolus- und Basalanteil.
        // Der Basalanteil ist hier gueltig (leere Liste); nur der Bolusanteil
        // ist unbekannt. Ohne die ansteckende Ungueltigkeit in `combine` waere
        // das Gesamtergebnis wieder gueltig - und der Fix waere wirkungslos.
        whenever(profileFunction.getProfile()).thenReturn(null)
        assertThat(sut.calculateIobFromBolus().valid).isFalse()
        assertThat(sut.calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime).valid).isTrue()
        assertThat(sut.calculateFromTreatmentsAndTemps(toTime, validProfile).valid).isFalse()
    }
}
