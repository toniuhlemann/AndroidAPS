package app.aaps.fuse.plugin

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump

/**
 * Harter Riegel: gegen WELCHE Pumpe darf FUSE ueberhaupt aktuieren.
 *
 * Das ist KEIN Modus und kein Schalter, sondern eine Startverweigerung. Ein
 * Regelkern darf nicht versehentlich auf einem fremden Geraet dosieren - und
 * eine UI-Beschriftung reicht dafuer nicht, weil sie niemand liest, wenn ein
 * APK auf dem falschen Telefon landet.
 *
 * Geprueft wird das TATSAECHLICH aktive Pumpenplugin zur Laufzeit, nicht eine
 * Preference: ein alter Einstellungswert darf den Riegel nicht umgehen koennen.
 *
 * ## Warum die Erkennung nur ueber [PumpType] laeuft
 *
 * FUSE darf keine Sicherheitsinvariante an eine private Stelle eines
 * Pumpentreibers haengen. [Pump.model] und [PumpType] sind oeffentliche,
 * stabile AAPS-Schnittstellen; ein Klassenname, ein Paketname oder ein Feld im
 * Medtrum-Modul waeren es nicht. Das ist zugleich die Bedingung dafuer, dass
 * FUSE nach einem AAPS-Master-Merge ohne erneute Treiberanalyse baubar bleibt
 * (s. FUSE_UPSTREAM_PORTING_MATRIX.md).
 *
 * Ausdruecklich NICHT geprueft wird die Seriennummer. `serialNumber()` ist
 * nach jedem Prozessstart zunaechst leer, weil sie asynchron nachgeladen wird -
 * ein Riegel darauf waere in genau der Minute offen oder zu, in der niemand
 * damit rechnet.
 */
object FusePumpGate {

    /**
     * Die BELEGTE Medtrum-Familie - und zwar wirklich nur das Geraet, auf dem
     * gemessen wurde: [PumpType.MEDTRUM_NANO].
     *
     * [PumpType.MEDTRUM_300U] ist ausdruecklich GESPERRT, obwohl es denselben
     * Treiberpfad und dieselbe `PumpCapability` hat. Fuer einen Fork mit genau
     * einem Nutzer ersetzt "gleicher Code" keine praktische Freigabe - belegt
     * ist, was gelaufen ist. Eine spaetere Erweiterung bekommt einen eigenen
     * Commit mit eigenem Test.
     *
     * [PumpType.MEDTRUM_UNTESTED] steht aus einem ANDEREN Grund nicht hier: es
     * ist im Treiber der else-Zweig der Modellzuordnung (`MedtrumPump.kt:326`)
     * und bedeutet "diese Modellkennung kennen wir nicht". Ueber
     * `parent = MEDTRUM_NANO` erbt es aber Nanos vollstaendige Dosier-Huelle
     * und SIEHT damit aus wie ein fertig beschriebener Pumpentyp.
     *
     * Das ist zum fuenften Mal in diesem Projekt derselbe Fehler: ein
     * Vorgabewert, der wie ein gueltiger Wert aussieht, ist durch keine
     * Wertpruefung zu fangen. Deshalb wird hier gegen eine ERLAUBNISLISTE
     * geprueft und nicht gegen eine Sperrliste - was nicht ausdruecklich
     * bewiesen ist, ist gesperrt, auch wenn es noch nicht existiert.
     */
    private val PROVEN_MEDTRUM: Set<PumpType> = setOf(PumpType.MEDTRUM_NANO)

    enum class Verdict {
        /**
         * Entwicklungs- und Testpfad (VirtualPump).
         *
         * Der Name bleibt `ALLOWED` und wird NICHT in `ALLOWED_VIRTUAL`
         * umbenannt, obwohl das schoener waere. Der Wert steht so in jedem
         * bisher geschriebenen Export; ihn umzubenennen haette jeden Auswerter
         * gebrochen, der auf die Zeichenkette prueft - fuer einen reinen
         * Schoenheitsgewinn. Die neue Erlaubnis bekommt stattdessen einen
         * neuen Namen, und damit bleibt die Aenderung rein additiv.
         */
        ALLOWED,

        /** Belegte Medtrum-Familie, AAPS-nativer Aktuationspfad. Neuer Wert -
         *  er kann in keinem alten Datensatz vorkommen. */
        ALLOWED_REAL_MEDTRUM,

        /** Eine echte Pumpe, aber nicht die belegte Familie. */
        BLOCKED_REAL_PUMP,

        /**
         * Eine Medtrum, deren Modell der Treiber NICHT identifiziert hat.
         *
         * Eigener Verdikt-Wert, damit im Export sichtbar ist, dass FUSE nicht
         * an der Marke gescheitert ist, sondern an der fehlenden Identitaet.
         * Wer nur "blockiert" sieht, sucht den Fehler an der falschen Stelle.
         */
        BLOCKED_UNPROVEN_MODEL,

        /** Kein Pumpenplugin, oder der Typ war nicht ermittelbar. */
        BLOCKED_UNKNOWN_PUMP,
    }

    data class Result(val verdict: Verdict, val pumpDescription: String) {

        val allowed: Boolean get() = verdict == Verdict.ALLOWED || verdict == Verdict.ALLOWED_REAL_MEDTRUM

        /** Aktuiert FUSE gegen echtes Insulin? Fuer Export und UI - eine
         *  erlaubte VirtualPump und eine erlaubte Medtrum duerfen im Log nicht
         *  gleich aussehen. */
        val realPump: Boolean get() = verdict == Verdict.ALLOWED_REAL_MEDTRUM

        /** Kurzform fuer Export und UI - der Grund muss sichtbar sein, sonst
         *  wirkt ein blockiertes FUSE wie ein defektes FUSE. */
        val reason: String
            get() = when (verdict) {
                Verdict.ALLOWED                -> "VPUMP_ALPHA"
                Verdict.ALLOWED_REAL_MEDTRUM   -> "MEDTRUM_NATIVE ($pumpDescription)"
                Verdict.BLOCKED_REAL_PUMP      -> "BLOCKED: real pump ($pumpDescription)"
                Verdict.BLOCKED_UNPROVEN_MODEL -> "BLOCKED: medtrum model not identified ($pumpDescription)"
                Verdict.BLOCKED_UNKNOWN_PUMP   -> "BLOCKED: pump not identifiable"
            }
    }

    /**
     * Die LESENDE Fassung. Sie fragt `model()` genau einmal und reicht das
     * Ergebnis an [decide] weiter.
     *
     * Wer den Typ ohnehin schon hat - etwa [FuseActivePump.of] beim Bauen
     * des Zyklus-Snapshots - ruft [decide] direkt auf. Sonst laese derselbe
     * Zyklus die Pumpe zweimal, und genau daran krankte der Zyklus vor dem
     * Snapshot-Umbau.
     */
    fun evaluate(pump: Pump?): Result {
        if (pump == null) return Result(Verdict.BLOCKED_UNKNOWN_PUMP, "none")
        return decide(
            virtualPump = pump is VirtualPump,
            // model() ist eine Treiberabfrage und kann werfen oder null
            // liefern, wenn noch keine Verbindung stand.
            type = runCatching { pump.model() }.getOrNull(),
            pumpClass = pump.javaClass.simpleName,
        )
    }

    /**
     * Die REINE Entscheidung - ohne Treiberabfrage, damit sie ohne Geraet
     * pruefbar ist und ohne zweite Lesung auskommt.
     *
     * [virtualPump] steht VOR dem Typ, weil die Klassenzugehoerigkeit die
     * Eigenschaft ist und der Typname bei der Emulation nur eine
     * Einstellung. `type == null` heisst unbekannt - und unbekannt ist
     * gesperrt, nicht durchgelassen.
     */
    fun decide(virtualPump: Boolean, type: PumpType?, pumpClass: String): Result {
        if (virtualPump) return Result(Verdict.ALLOWED, pumpClass)
        if (type == null) return Result(Verdict.BLOCKED_UNKNOWN_PUMP, pumpClass)
        return when {
            type in PROVEN_MEDTRUM   -> Result(Verdict.ALLOWED_REAL_MEDTRUM, type.name)
            // Eine Medtrum ohne erkanntes Modell wird ausdruecklich anders
            // beschieden als eine Fremdpumpe - siehe BLOCKED_UNPROVEN_MODEL.
            type.isMedtrumFamily()   -> Result(Verdict.BLOCKED_UNPROVEN_MODEL, type.name)
            else                     -> Result(Verdict.BLOCKED_REAL_PUMP, type.name)
        }
    }

    /**
     * Zugehoerigkeit zur Marke, nicht zur Erlaubnis.
     *
     * Ueber den Namen und nicht ueber `manufacturer`, weil
     * [PumpType.MEDTRUM_UNTESTED] als reiner Elternverweis definiert ist und
     * seinen Hersteller gar nicht selbst setzt - eine Herstellerabfrage wuerde
     * genau den Fall verfehlen, fuer den dieser Zweig existiert.
     */
    private fun PumpType.isMedtrumFamily(): Boolean = name.startsWith("MEDTRUM")
}
