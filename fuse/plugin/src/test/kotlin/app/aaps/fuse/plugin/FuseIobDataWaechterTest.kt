package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * QUELL-WAECHTER fuer den Fixvertrag 30.08. (Nightscout `openaps.iob`).
 *
 * Der DeviceStatus bekommt seinen `openaps.iob`-Block aus
 * `lastRun.request?.iob` = `APSResult.iobData[0]` (LoopPlugin,
 * buildAndStoreDeviceStatus). FUSE muss das im Zyklus gerechnete
 * typisierte IobTotal deshalb beim Publizieren in `iobData` uebernehmen -
 * faellt die Zuweisung einem Refactoring zum Opfer, verliert Nightscout
 * den Block STILL und rechnet aus den Bolus-Treatments ein eigenes IOB
 * (Befund 30.08.: 4,90 U bei echtem Netto-IOB 0,07; NS-Bolus-Assistent
 * unbrauchbar). Kein Unit-Test faehrt FusePlugin bis zu dieser Zeile,
 * darum haelt sie dieser Waechter - dasselbe Idiom wie
 * LoopPluginAnnahmeWaechterTest.
 *
 * SCHLAEGT ER FEHL: die Uebergabe outcome.iobTotal -> APSResult.iobData
 * an der lastAPSResult-Zuweisung wiederherstellen (valid-geprueft, KEINE
 * zweite Semantik aus RT.IOB) - nicht den Waechter anpassen.
 */
class FuseIobDataWaechterTest {

    private fun fusePluginQuelle(): String {
        val kandidaten = listOf(
            "src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
            "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
        )
        val f = kandidaten.map { File(it) }.firstOrNull { it.exists() }
        requireNotNull(f) { "FusePlugin.kt nicht gefunden - Waechter kann die Zuweisung nicht pruefen" }
        return f.readText()
    }

    @Test
    fun `das publizierte APSResult uebernimmt das typisierte Zyklus-IobTotal`() {
        val code = fusePluginQuelle()

        val publish = code.indexOf("lastAPSResult = apsResultProvider.get().with(publishRt)")
        assertTrue(publish >= 0) {
            "Die lastAPSResult-Publikationsstelle fehlt - ist der Publikationspfad umgezogen, " +
                "muss die iobData-Uebergabe mitziehen."
        }

        val zuweisung = Regex(
            """outcome\?\.iobTotal\?\.takeIf \{ it\.valid \}\?\.let \{ \w+\.iobData = arrayOf\(it\) \}""",
        ).find(code)?.range?.first ?: -1
        assertTrue(zuweisung >= 0) {
            "Die Uebergabe outcome.iobTotal -> APSResult.iobData fehlt (valid-geprueft, arrayOf). " +
                "Ohne sie verliert der DeviceStatus den openaps.iob-Block und Nightscout " +
                "rechnet ein falsches Treatment-IOB."
        }
        assertTrue(zuweisung > publish && zuweisung - publish < 1200) {
            "Die iobData-Uebergabe haengt nicht mehr an der lastAPSResult-Publikation - " +
                "sie muss auf dem publizierten Ergebnis DIESES Zyklus stehen."
        }
    }
}
