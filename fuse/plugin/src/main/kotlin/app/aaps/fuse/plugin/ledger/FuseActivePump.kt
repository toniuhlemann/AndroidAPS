package app.aaps.fuse.plugin.ledger

import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump

/**
 * WAS GERADE AN DER PUMPENSCHNITTSTELLE HAENGT - einmal je Zyklus gelesen.
 *
 * ## Warum es diesen Typ gibt
 *
 * `Pump.model()` ist bei der [VirtualPump] KEINE Eigenschaft, sondern eine
 * EINSTELLUNG: `VirtualPumpPlugin.model()` gibt `pumpDescription.pumpType`
 * zurueck, und das folgt der Nutzerpreference `VirtualPumpType`
 * (`VirtualPumpPlugin.kt:340`, `refreshConfiguration()`). Auf dem Testgeraet
 * steht dort `MEDTRUM_NANO` - absichtlich, damit der Bolusschritt 0,05 dem
 * echten Geraet entspricht.
 *
 * Damit beantwortet der Typname die Frage "haengt hier physisches Insulin
 * dran?" NICHT. Wer nur ihn liest, haelt eine Emulation fuer eine Patchpumpe,
 * verlangt eine Patch-Epoche, findet keine (es gibt keinen CANNULA_CHANGE) und
 * sperrt den gesamten Entwicklungspfad - fail-closed, aber gegen den falschen
 * Gegner.
 *
 * Das ist dieselbe Fehlerklasse wie `MEDTRUM_UNTESTED` in [FusePumpGate], nur
 * spiegelverkehrt: dort sah ein UNBEKANNTER Typ nach einem gueltigen aus, hier
 * sieht ein EMULIERTER nach einem echten aus. Beide Male hilft keine
 * Wertpruefung am Typ, sondern nur ein zweites, getrennt erhobenes Merkmal.
 *
 * ## Warum als eigener Typ und nicht als zwei lose Parameter
 *
 * Typ und Emulationsflag muessen AUS DEMSELBEN LESEVORGANG stammen. Zwei
 * getrennte `activePlugin.activePump`-Zugriffe koennten - nach einem
 * Pumpenwechsel mitten im Zyklus - einen Typ mit dem Flag eines anderen
 * Moments paaren. Genau diese Paarungsfalle ist beim Serial schon einmal
 * aufgetreten (s. `FusePlugin`, Kommentar zu `pumpTypeName`).
 *
 * Der SERIAL steht bewusst NICHT hier. Er wird nach einem Prozessstart
 * asynchron nachgeladen (s. [LedgerFacts.serialHashOf]) und soll deshalb so
 * SPAET wie moeglich im Zyklus gelesen werden - ihn hierher zu ziehen wuerde
 * das Fenster vergroessern, in dem ein leerer Serial gepinnt wird.
 */
data class FuseActivePump(
    /** `PumpType.name` der aktiven Pumpe, `null` = nicht lesbar. */
    val pumpTypeName: String?,
    /**
     * Ist das die [VirtualPump]? `null` heisst UNBEKANNT (Pumpe nicht lesbar) -
     * und unbekannt ist hier nicht `false`, sonst waere die Emulation wieder
     * nicht von einem echten Geraet zu unterscheiden.
     */
    val virtualPump: Boolean?,
) {

    /**
     * Haengt gerade eine PHYSISCHE Patchpumpe dran?
     *
     * Dreiwertig, und das ist der Punkt:
     *  - `true`  - echte Patchpumpe: Epoche ist Pflicht.
     *  - `false` - keine Patchpumpe ODER Emulation: Epoche ist keine Kategorie.
     *  - `null`  - unbekannt: der Aufrufer haelt an, statt zu raten.
     *
     * Die Reihenfolge der Zweige ist wesentlich. Ein Typ, der gar keine
     * Patchpumpe ist, bleibt auch bei unbekanntem Emulationsstatus `false` -
     * sonst ginge eine unlesbare Fremdpumpe unnoetig in den Hold. Umgekehrt
     * wird ein MEDTRUM-Name mit unbekanntem Flag NICHT zu `false` aufgeloest.
     */
    val realPatchPump: Boolean?
        get() = when {
            pumpTypeName == null                                 -> null
            !ProposalPumpEpoch.appliesTo(pumpTypeName)           -> false
            virtualPump == null                                  -> null
            else                                                 -> !virtualPump
        }

    /**
     * Muss die Publikation gesperrt werden, weil die Patch-Epoche einer ECHTEN
     * Patchpumpe unbekannt ist?
     *
     * Steht hier und nicht als Ausdruck im Zyklus, damit die Bedingung geprueft
     * werden kann. Als Zeile im Plugin waere sie nur ueber den ganzen
     * Zyklusaufbau erreichbar, und eine Regel, die man nicht direkt pruefen
     * kann, ist frueher oder ergaenzt und niemandem faellt es auf.
     *
     * `virtualPump != true` und nicht `== false`: bei UNBEKANNTER Pumpenklasse
     * gilt die Pumpe als echt und wird gesperrt - die vorsichtige Richtung.
     *
     * ABSICHTLICH NICHT `realPatchPump == true`, obwohl es aehnlich aussieht.
     * Ist die Pumpe GAR NICHT lesbar, sagt `realPatchPump` "unbekannt"; hier
     * ist das Ergebnis dann `false`, also KEINE Sperre. Das ist richtig, denn
     * in dem Fall greift schon der Pumpenriegel (`BLOCKED_UNKNOWN_PUMP`) und
     * FUSE aktuiert ohnehin nicht - eine zweite Sperre mit eigenem Grund wuerde
     * den Trail nur verschleiern. Die Migration dagegen hat keinen solchen
     * Riegel vor sich und muss bei "unbekannt" anhalten; deshalb fragt SIE
     * ueber `realPatchPump` und diese Stelle nicht.
     */
    fun realPumpEpochUnknown(epochKnown: Boolean): Boolean =
        virtualPump != true && ProposalPumpEpoch.appliesTo(pumpTypeName) && !epochKnown

    companion object {

        /** Pumpe nicht lesbar - alles unbekannt. */
        val UNKNOWN = FuseActivePump(null, null)

        /**
         * Aus dem aktiven Pumpenplugin ableiten.
         *
         * `is VirtualPump` VOR `model()`, genau wie [FusePumpGate] es tut - die
         * Klassenzugehoerigkeit ist die Eigenschaft, der Typname nur die
         * Einstellung. Beide Abfragen in `runCatching`: ein zickender Treiber
         * degradiert zu "unbekannt", er wirft den Zyklus nicht ab.
         */
        fun of(pump: Pump?): FuseActivePump {
            if (pump == null) return UNKNOWN
            return FuseActivePump(
                pumpTypeName = runCatching { pump.model().name }.getOrNull()?.takeIf { it.isNotBlank() },
                virtualPump = pump is VirtualPump,
            )
        }
    }
}
