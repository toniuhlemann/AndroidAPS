package app.aaps.fuse.plugin

import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.fuse.plugin.ledger.FusePatchEpoch
import app.aaps.fuse.plugin.ledger.LedgerFacts
import app.aaps.fuse.plugin.ledger.ProposalPumpEpoch

/**
 * DER PUMPENSNAPSHOT - genau EINE Lesung je Zyklus, unveraenderlich.
 *
 * ## Warum es diesen Typ gibt (zwei Gruende, beide teuer)
 *
 * **1. `Pump.model()` ist bei der [VirtualPump] eine EINSTELLUNG, keine
 * Eigenschaft.** `VirtualPumpPlugin.model()` gibt `pumpDescription.pumpType`
 * zurueck, und das folgt der Nutzerpreference `VirtualPumpType`. Auf dem
 * Testgeraet steht sie auf `MEDTRUM_NANO` - absichtlich, damit der Bolusschritt
 * 0,05 dem echten Geraet entspricht. Der Typname beantwortet die Frage "haengt
 * hier physisches Insulin dran?" also NICHT. Wer nur ihn liest, haelt eine
 * Emulation fuer eine Patchpumpe und sperrt den gesamten Entwicklungspfad.
 *
 * Dieselbe Fehlerklasse wie `MEDTRUM_UNTESTED` in [FusePumpGate], nur
 * spiegelverkehrt: dort sah ein UNBEKANNTER Typ nach einem gueltigen aus, hier
 * ein EMULIERTER nach einem echten. Gegen beides hilft keine Wertpruefung am
 * Typ, sondern nur ein zweites, getrennt erhobenes Merkmal.
 *
 * **2. Es wurde DREIMAL je Zyklus gelesen** (Auditbefund 10.08.2026): im Plugin
 * fuer Typ/Emulation/Epoche/Pinnung, im Runner fuer den Riegel, und noch einmal
 * fuer den Bolusschritt. Ein Kommentar behauptete dabei ausdruecklich "EIN Lesen
 * der aktiven Pumpe je Zyklus" - er war schlicht falsch.
 *
 * Das ist nicht bloss unsauber. Wechselt die Pumpe waehrend eines Zyklus, kann
 * der Riegel eine ECHTE Medtrum sehen und durchlassen, waehrend die Ledgerzeile
 * mit `virtualPump=true` gepinnt wird - und damit ohne Patchpruefung bindet.
 * Genau das Loch, gegen das B3 gebaut wurde. Ein unveraenderlicher Snapshot
 * schliesst es strukturell: alle Merkmale stammen aus DEMSELBEN Augenblick.
 *
 * ## Was NICHT drinsteht
 *
 * Reservoir, Batterie, Verbindung und Suspend-Zustand. Die gehoeren AAPS: es
 * bleibt Eigentuemer des Pumpentransports und seiner Sicherheitspruefungen.
 * FUSE muss nicht wissen, ob die Pumpe erreichbar ist - es muss richtig damit
 * umgehen, dass eine Anforderung moeglicherweise nicht ausgefuehrt wird.
 */
data class FuseActivePump(
    /** `PumpType.name`, `null` = nicht lesbar. */
    val pumpTypeName: String?,
    /**
     * Ist das die [VirtualPump]? `null` heisst UNBEKANNT (Pumpe nicht lesbar) -
     * und unbekannt ist hier nicht `false`, sonst waere die Emulation wieder
     * nicht von einem echten Geraet zu unterscheiden.
     */
    val virtualPump: Boolean?,
    /**
     * Der Riegel - aus DERSELBEN Instanz wie alles andere hier.
     *
     * Die Vorgabewerte der folgenden Felder sind alle die UNBEKANNT-Seite
     * (Riegel zu, kein Temp-Basal, Bolusschritt NaN, keine Identitaet). Ein
     * unvollstaendig gebauter Snapshot ist damit fail-closed und nicht
     * versehentlich harmlos - in der Erzeugung ueber [of] wird ohnehin jedes
     * Feld gesetzt.
     */
    val gate: FusePumpGate.Result = FusePumpGate.Result(FusePumpGate.Verdict.BLOCKED_UNKNOWN_PUMP, "none"),
    /** Kann die Pumpe ueberhaupt Temp-Basale? Teil der Startverweigerung. */
    val tempBasalCapable: Boolean = false,
    /**
     * Bolusschritt. `NaN` heisst nicht lesbar - und NICHT 0.0: `floor(x/0)*0`
     * ergibt NaN, und `NaN < 0.0` ist false, der Zyklus fiele also durch statt
     * zu sperren. Der Aufrufer prueft auf `isFinite() && > 0`.
     */
    val bolusStepU: Double = Double.NaN,
    /** Basalschritt fuer die TBR-Quantisierung, `NaN` = nicht lesbar. */
    val basalStepUPerH: Double = Double.NaN,
    /**
     * DIE BASIS, GEGEN DIE AAPS ENTSCHEIDET - einmal gelesen.
     *
     * `LoopPlugin.applyAPSRequest` vergleicht die angeforderte Rate gegen
     * `pump.baseBasalRate`, NICHT gegen das Therapieprofil. Bei einem
     * Profilwechsel oder einer verzoegerten Pumpenuebernahme koennen beide
     * auseinanderlaufen - dann urteilte FUSEs Klassifikation anders als
     * AAPS' Ausfuehrung. `NaN` heisst nicht lesbar (nicht 0.0, s. oben).
     */
    val baseBasalRateUPerH: Double = Double.NaN,
    /**
     * Der kanonische Serial-Hash, EINMAL gelesen. `null` heisst unbekannt oder
     * leer.
     *
     * LEER IST NICHT "EIN ANDERES GERAET": direkt nach einem Prozessstart
     * liefert `serialNumber()` den leeren String, weil `InstanceId` auf die
     * asynchrone Firebase-Antwort wartet (gemessen: 6 von 169 Vorschlaegen).
     * Deshalb `null` statt eines Hashes ueber "" - ein `Sha("")` saehe wie eine
     * echte Identitaet aus und wurde genau so schon einmal blind gepinnt.
     */
    val serialHash: String? = null,
    /**
     * Die Patch-Epoche dieses Zyklus. Sie kommt aus der Datenbank und wird
     * deshalb NACH der Pumpenlesung angehaengt ([withPatchEpoch]) - die
     * Pumpenmerkmale stehen dann schon fest.
     */
    val patchEpoch: FusePatchEpoch.Result? = null,
) {

    fun withPatchEpoch(r: FusePatchEpoch.Result) = copy(patchEpoch = r)

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
            pumpTypeName == null                       -> null
            !ProposalPumpEpoch.appliesTo(pumpTypeName) -> false
            virtualPump == null                        -> null
            else                                       -> !virtualPump
        }

    /**
     * Darf die DESTRUKTIVE Ledger-Reparatur laufen?
     *
     * NUR bei nachgewiesener Emulation - und ausdruecklich NICHT `!realPump`.
     * Der Unterschied ist der P0 vom 10.08.: `realPump` ist auch false bei
     * unbekannter Pumpe, bei MEDTRUM_UNTESTED und bei einer fremden physischen
     * Pumpe. In allen dreien haette die Reparatur laufen duerfen.
     *
     * Die Eigenschaft hat einen NAMEN, damit sie geprueft werden kann. Als
     * Ausdruck an der Aufrufstelle waere sie nur ueber den ganzen Zyklusaufbau
     * erreichbar - dieselbe ungepruefte Ecke, aus der heute vier Fehler kamen.
     */
    val repairAllowed: Boolean get() = virtualPump == true

    /** Aktuiert dieser Zyklus gegen ECHTES Insulin? */
    val realPump: Boolean get() = virtualPump != true && gate.realPump

    /**
     * Muss die Publikation gesperrt werden, weil die Patch-Epoche einer ECHTEN
     * Patchpumpe unbekannt ist?
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
     * ueber [realPatchPump] und diese Stelle nicht.
     */
    val realPumpEpochUnknown: Boolean
        get() = virtualPump != true &&
            ProposalPumpEpoch.appliesTo(pumpTypeName) &&
            patchEpoch?.known != true

    /**
     * ECHTE, ERLAUBTE PUMPE - ABER WIR WISSEN NICHT, WELCHE.
     *
     * Ohne Serial entsteht ein Pin ohne Identitaet, und `matchesPinnedEpoch`
     * behandelt `pumpSerialHash == null` als WILDCARD: die Zeile bindet dann
     * jeden typgleichen Bolus. An der VirtualPump ist das harmlos und sogar
     * noetig (der leere Serial nach dem Prozessstart). An einer echten Medtrum
     * ist es ein Freibrief - eine Haftung koennte ueber einen fremden Fakt
     * ausgebucht werden.
     *
     * Die Sperre trifft deshalb NUR den positiven SMB. Eine Safety- oder
     * Cancel-TBR bleibt: ein Identitaetsproblem des BOLUS darf keine
     * Schutz-Null verhindern - dieselbe Trennung wie bei der unbekannten
     * Epoche.
     *
     * Zwei Bedingungen in dieselbe Richtung, absichtlich: [gate] schliesst die
     * VirtualPump schon ueber die Klassenpruefung aus, aber wer den Riegel
     * spaeter umbaut, soll die Emulation nicht versehentlich mit einsperren.
     */
    val realPumpIdentityUnknown: Boolean
        get() = virtualPump != true && gate.realPump && serialHash == null

    companion object {

        /** Pumpe nicht lesbar - alles unbekannt, Riegel zu. */
        val UNKNOWN = FuseActivePump(pumpTypeName = null, virtualPump = null)

        /**
         * Aus dem aktiven Pumpenplugin ableiten - der EINZIGE Ort, an dem der
         * Zyklus die Pumpe anfasst.
         *
         * Jede Teilabfrage in `runCatching`: ein zickender Treiber degradiert
         * das betroffene Merkmal zu "unbekannt", er wirft den Zyklus nicht ab.
         * Die Klassenpruefung steht vor `model()`, genau wie in [FusePumpGate] -
         * die Klassenzugehoerigkeit ist die Eigenschaft, der Typname nur die
         * Einstellung.
         */
        fun of(pump: Pump?): FuseActivePump {
            if (pump == null) return UNKNOWN
            // JEDE Quelle GENAU EINMAL. `model()` und `pumpDescription` sind
            // Treiberabfragen; sie zweimal zu stellen hiesse, zwei moeglicherweise
            // verschiedene Antworten in denselben Snapshot zu schreiben - und
            // genau das sollte der Snapshot beenden.
            val virtuell = pump is VirtualPump
            val typ = runCatching { pump.model() }.getOrNull()
            val beschreibung = runCatching { pump.pumpDescription }.getOrNull()
            val typName = typ?.name?.takeIf { it.isNotBlank() }
            return FuseActivePump(
                pumpTypeName = typName,
                virtualPump = virtuell,
                // decide() statt evaluate(): sonst laese der Riegel model() ein
                // zweites Mal.
                gate = FusePumpGate.decide(virtuell, typ, pump.javaClass.simpleName),
                tempBasalCapable = beschreibung?.isTempBasalCapable ?: false,
                bolusStepU = beschreibung?.bolusStep ?: Double.NaN,
                basalStepUPerH = beschreibung?.basalStep ?: Double.NaN,
                // DIESELBE Lesung wie alles andere hier - zweimal fragen
                // hiesse, zwei moeglicherweise verschiedene Antworten in
                // denselben Snapshot zu schreiben.
                baseBasalRateUPerH = runCatching { pump.baseBasalRate }.getOrDefault(Double.NaN),
                // Die kanonische Serialform haengt vom Pumpentyp ab (F7) -
                // deshalb beides aus DERSELBEN Lesung.
                serialHash = runCatching { LedgerFacts.serialHashOf(pump.serialNumber(), typName) }.getOrNull(),
            )
        }
    }
}
