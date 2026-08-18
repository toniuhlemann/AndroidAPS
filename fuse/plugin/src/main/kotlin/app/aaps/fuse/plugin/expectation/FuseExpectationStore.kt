package app.aaps.fuse.plugin.expectation

import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.plugin.ledger.Durability
import java.io.File
import java.io.FileOutputStream

/**
 * PERSISTENZ DES ERWARTUNGS-LEDGERS - eine Generation, atomar ersetzt.
 *
 * Bewusst eine EIGENE Klasse neben FuseLedgerStore und kein Umbau von dessen
 * Mechanik: jener sichert die offene Insulinhaftung und laeuft seit Wochen
 * produktiv. Ihn zu verallgemeinern, damit ein rein beobachtender Baustein
 * mitbenutzen kann, waere ein Eingriff in Sicherheitscode fuer eine
 * Bequemlichkeit. Die [Durability]-Abstraktion wird geteilt - sie ist
 * bereits eigenstaendig und testbar.
 *
 * SCHREIBEN heisst hier: vollstaendig in die Zwischendatei schreiben, fsync,
 * die alte Zieldatei zur Sicherung drehen, umbenennen, Verzeichnis-fsync,
 * zurueckLESEN und vergleichen. Erst danach gilt eine Generation als
 * geschrieben.
 *
 * DIE SEQUENZ IST NICHT ALS GANZES ATOMAR, und das ist der Grund fuer die
 * drei Kandidaten: ein Absturz zwischen den beiden Umbenennungen
 * hinterlaesst KEIN Ziel, aber eine vollstaendige Zwischendatei. Deshalb
 * betrachtet [load] alle drei Generationen und nimmt die mit der HOECHSTEN
 * Revision - nicht die zuerst gefundene.
 *
 * FAIL-CLOSED HEISST HIER ETWAS ANDERES ALS BEIM INSULIN-LEDGER. Dieser
 * Baustein misst nur; ein verlorener Zustand kostet keinen Nachweis, der
 * eine Dosis traegt, sondern verzoegert ihn um Minuten. Trotzdem darf eine
 * BESCHAEDIGTE Generation nie als leerer Start durchgehen - sonst begaenne
 * der Streak neu, ohne dass jemand es merkt. Deshalb meldet [load] den
 * Unterschied ([Loaded.Corrupt]) und ueberlaesst die Entscheidung dem
 * Aufrufer.
 */
class FuseExpectationStore(private val durability: Durability = Durability.ANDROID) {

    companion object {

        const val FILE_NAME = "fuse_expectation.json"

        /**
         * HARTE OBERGRENZEN, damit die Datei unter keinen Umstaenden
         * unbegrenzt waechst (Tonis Store-Auflage).
         *
         * Strukturell koennte das nicht passieren: offene Eintraege sind
         * hoechstens einen Horizont alt, Ergebnisse werden auf vier Stunden
         * beschnitten, verbrauchte Kennungen auf das Zuordnungsfenster. Diese
         * Grenzen sind der Riegel fuer den Fall, dass eine dieser Annahmen
         * einmal nicht mehr stimmt - eine Datei, die das Geraet vollschreibt,
         * waere ein Schaden weit ueber diesen Baustein hinaus.
         *
         * Beim Ueberschreiten werden die AELTESTEN verworfen: der Nachweis
         * lebt von der juengsten zusammenhaengenden Strecke.
         */
        const val MAX_ENTRIES = 200
        const val MAX_OUTCOMES = 500
        const val MAX_CONSUMED = 500
    }

    /** Was beim Laden vorgefunden wurde. */
    sealed interface Loaded {

        data class Ok(val state: ExpectationLedger.State, val revision: Long) : Loaded

        /** Nichts da - beim Erststart der Normalfall, leer weiterlaufen. */
        data object Fresh : Loaded

        /**
         * Es GAB etwas, aber keine Generation war lesbar. Der Aufrufer
         * startet leer, muss das aber als Datenverlust behandeln und melden -
         * nicht als Erststart.
         */
        data class Corrupt(val reason: String) : Loaded
    }

    /**
     * Die juengste GUELTIGE Generation aus allen drei Kandidaten.
     *
     * Geprueft wird jede fuer sich; eine beschaedigte Zwischendatei
     * schliesst ein intaktes Ziel nicht aus. Gewaehlt wird nach REVISION,
     * nicht nach Dateizeit - Zeitstempel koennen bei Zeitzonen- oder
     * Uhrensprung luegen, eine monoton gezaehlte Generation nicht.
     */
    /**
     * @param kopfstand der aktuelle Eingriffsstempel aus dem
     *   Publikationsledger - die Autoritaet liegt dort, nicht hier.
     */
    fun load(dir: File, kopfstand: InterventionStamp): Loaded {
        val kandidaten = listOf(
            File(dir, FILE_NAME + ".tmp"),
            File(dir, FILE_NAME),
            File(dir, FILE_NAME + ".bak"),
        )
        var gabEs = false
        var besteState: ExpectationLedger.State? = null
        var besteRevision = Long.MIN_VALUE
        val gruende = mutableListOf<String>()
        for (f in kandidaten) {
            val text = runCatching { if (f.isFile) f.readText(Charsets.UTF_8) else null }.getOrNull() ?: continue
            gabEs = true
            when (val d = FuseExpectationCodec.decode(text, kopfstand)) {
                is FuseExpectationCodec.Decoded.Valid   ->
                    if (d.revision > besteRevision) {
                        besteState = d.state
                        besteRevision = d.revision
                    }

                is FuseExpectationCodec.Decoded.Invalid -> gruende += f.name + ": " + d.reason
                FuseExpectationCodec.Decoded.Missing    -> Unit
            }
        }
        besteState?.let { return Loaded.Ok(it, besteRevision) }
        return if (gabEs) Loaded.Corrupt(gruende.joinToString("; ").ifBlank { "keine lesbare Generation" })
        else Loaded.Fresh
    }

    /**
     * Eine Generation schreiben und die Schreibung NACHWEISEN.
     *
     * @return true nur, wenn die Zieldatei danach exakt den geschriebenen
     *   Inhalt trug. Ein verworfener Rueckgabewert waere hier folgenlos (der
     *   Baustein misst nur), aber der Aufrufer soll den Fehlschlag melden
     *   koennen - eine still nicht geschriebene Generation faellt sonst erst
     *   beim naechsten Neustart auf.
     */
    fun save(
        dir: File,
        state: ExpectationLedger.State,
        revision: Long,
        kopfstand: InterventionStamp,
    ): Boolean = runCatching {
        val inhalt = FuseExpectationCodec.encode(kappen(state, kopfstand), revision)
        val tmp = File(dir, FILE_NAME + ".tmp")
        val ziel = File(dir, FILE_NAME)
        val bak = File(dir, FILE_NAME + ".bak")

        // 1. Vollstaendig schreiben und auf das Medium zwingen. Ohne fsync
        //    kann die Umbenennung sichtbar sein, waehrend der Inhalt noch im
        //    Seitencache steht - nach einem Stromausfall ist das eine leere
        //    oder halbe Datei unter dem richtigen Namen.
        FileOutputStream(tmp).use { out ->
            out.write(inhalt.toByteArray(Charsets.UTF_8))
            out.flush()
            durability.syncFile(out.fd)
        }
        // 2. Die alte Generation zur Seite drehen, BEVOR sie ueberschrieben
        //    wird - sie ist die Sicherung, falls Schritt 3 unterbrochen wird.
        if (ziel.isFile) {
            bak.delete()
            ziel.renameTo(bak)
        }
        // 3. Umbenennen - auf demselben Dateisystem die atomare Operation,
        //    an der die ganze Sequenz haengt.
        if (!tmp.renameTo(ziel)) return@runCatching false
        // 4. Den VERZEICHNISEINTRAG syncen. Ohne ihn kann die Umbenennung
        //    nach einem Stromausfall verschwinden, obwohl die Bytes da sind.
        durability.syncDirectory(dir)
        // 5. Zuruecklesen. Alles davor ist Absicht - das hier ist der
        //    einzige Nachweis.
        ziel.isFile && ziel.readText(Charsets.UTF_8) == inhalt
    }.getOrDefault(false)

    /**
     * Die harten Obergrenzen anwenden - die AELTESTEN fallen weg.
     *
     * Sichtbar getrennt vom Schreiben, damit im Test pruefbar ist, WAS
     * geschrieben worden waere. Ein stilles Kuerzen im Schreibpfad waere
     * genau die Art Datenverlust, die man erst Wochen spaeter bemerkt.
     */
    internal fun kappen(state: ExpectationLedger.State, kopfstand: InterventionStamp): ExpectationLedger.State {
        if (state.entries.size <= MAX_ENTRIES &&
            state.outcomes.size <= MAX_OUTCOMES &&
            state.consumed.size <= MAX_CONSUMED
        ) return state
        val entries = state.entries.sortedBy { it.dueTs }.takeLast(MAX_ENTRIES)
        val outcomes = state.outcomes.sortedBy { it.entry.dueTs }.takeLast(MAX_OUTCOMES)
        val consumed = state.consumed.sortedBy { it.ts }.takeLast(MAX_CONSUMED).toSet()
        // Ueber restore, damit auch eine gekappte Generation die
        // Semantikpruefung besteht - sonst schriebe das Kappen einen
        // Zustand, den das Laden anschliessend verwirft.
        return when (
            val r = ExpectationLedger.restore(
                entries, consumed, outcomes, kopfstand = kopfstand,
            )
        ) {
            is ExpectationLedger.Restored.Valid   -> r.state
            is ExpectationLedger.Restored.Invalid -> ExpectationLedger.State.empty()
        }
    }
}
