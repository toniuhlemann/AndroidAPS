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

        /**
         * EIGENES, APP-INTERNES VERZEICHNIS (Toni 18.08.).
         *
         * Nicht im externen Exportverzeichnis: dort lesen und schreiben
         * fremde Prozesse mit, und die Semantikpruefungen dieses Bausteins
         * sind von Hand erfuellbar - 30 erfundene MISSED-Ergebnisse ergaeben
         * sofort eine lange Nachweisstrecke.
         *
         * Und nicht im Verzeichnis des Insulinledgers: [FuseLedgerRepair]
         * arbeitet dort auf einer festen Namensliste und wuerde diese Datei
         * heute zwar in Ruhe lassen - aber eine Reparaturdomaene, die nur
         * deshalb nicht zugreift, weil ein Name gerade nicht in einer Liste
         * steht, ist keine Trennung. Getrennte Verzeichnisse sind eine.
         *
         * Geteilt wird ausschliesslich [Durability]; Generationen, Marker und
         * Reparatur bleiben je Baustein fuer sich.
         */
        const val DIR_NAME = "fuse_expectation"

        /** Das Ablageverzeichnis unter dem app-internen `filesDir`. */
        fun dirIn(filesDir: File): File = File(filesDir, DIR_NAME)

        const val FILE_NAME = "fuse_expectation.json"

        /**
         * DER ZEUGE, DASS ES HIER SCHON EINMAL ETWAS GAB.
         *
         * Ohne ihn ist eine VERSCHWUNDENE Generation von einem Erststart
         * nicht zu unterscheiden - beide zeigen ein leeres Verzeichnis. Das
         * ist genau der Datenverlust, den [Loaded.Corrupt] melden soll:
         * beginnt der Streak still neu, merkt es niemand.
         *
         * Eigener Name neben dem des Insulinledgers (`fuse_ledger.exists`),
         * damit auch bei einer versehentlich gemeinsamen Ablage keiner den
         * Zeugen des anderen liest.
         */
        const val SENTINEL_NAME = "fuse_expectation.exists"

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
        /**
         * 240 statt 500 (Toni 18.08.).
         *
         * TECHNISCHE SPEICHERGRENZE, KEINE THERAPEUTISCHE ZEITGRENZE. Bei
         * hoechstens einem Ergebnis je Minute deckt sie eine durchgehende
         * vierstuendige Korrektur-Sackgasse ab und liegt damit ueber den
         * bisher beobachteten dreistuendigen Plateaus. Der Store ist ein
         * ARBEITSZUSTAND, kein Archiv - das Archiv ist der Zyklustrail, in
         * dem die Rohgroessen ohnehin landen.
         *
         * Senkt die groesste Datei von rund 208 KB auf 85-100 KB. Eine
         * gekappte Strecke muss im Export als "mindestens N Minuten"
         * erscheinen, nie als exakte Laenge - dafuer traegt der Export
         * `historyTruncated` und `oldestRetainedDueTs`.
         */
        const val MAX_OUTCOMES = 240
        const val MAX_CONSUMED = 500
    }

    /** Was beim Laden vorgefunden wurde. */
    sealed interface Loaded {

        data class Ok(
            val state: ExpectationLedger.State,
            val revision: Long,
            val lastObservationGapTs: Long,
            val droppedOutcomesTotal: Long,
        ) : Loaded

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
        var besteGapTs = 0L
        var besteDropped = 0L
        val gruende = mutableListOf<String>()
        for (f in kandidaten) {
            val text = runCatching { if (f.isFile) f.readText(Charsets.UTF_8) else null }.getOrNull() ?: continue
            gabEs = true
            when (val d = FuseExpectationCodec.decode(text, kopfstand)) {
                is FuseExpectationCodec.Decoded.Valid   ->
                    if (d.revision > besteRevision) {
                        besteState = d.state
                        besteRevision = d.revision
                        besteGapTs = d.lastObservationGapTs
                        besteDropped = d.droppedOutcomesTotal
                    }

                is FuseExpectationCodec.Decoded.Invalid -> gruende += f.name + ": " + d.reason
                FuseExpectationCodec.Decoded.Missing    -> Unit
            }
        }
        besteState?.let { return Loaded.Ok(it, besteRevision, besteGapTs, besteDropped) }
        if (gabEs) return Loaded.Corrupt(gruende.joinToString("; ").ifBlank { "keine lesbare Generation" })
        // NICHTS GEFUNDEN - aber gab es hier schon einmal etwas? Der Zeuge
        // entscheidet. Ohne ihn liefe ein Datenverlust als Erststart durch.
        return if (sentinelExists(dir)) Loaded.Corrupt("alle Generationen fehlen, obwohl hier schon geschrieben wurde")
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
    /**
     * WAS EIN SCHREIBVORGANG GEKOSTET HAT - fuer die Lastmessung vor dem
     * ersten Feldlauf (Toni 18.08.).
     *
     * Der Recorder schreibt in JEDEM Zyklus, bei Ein-Minuten-Takt also 1440
     * mal am Tag. Ob das die Zykluszeit belastet, laesst sich nur messen -
     * und zwar bevor es auf dem produktiven Geraet laeuft, nicht danach.
     */
    data class WriteStats(
        val ok: Boolean,
        val bytes: Int,
        val durationMs: Long,
        /**
         * WAS TATSAECHLICH AUF PLATTE STEHT - gekappt, nicht der uebergebene
         * Zustand (Toni 18.08.).
         *
         * Der erste Wurf gab nur `ok` zurueck, und der Aufrufer uebernahm
         * seinen eigenen, UNGEKAPPTEN Kandidaten als "persistiert". Ab der
         * Kappungsgrenze wertete die Auswertung damit Ergebnisse aus, die nie
         * versiegelt wurden - zwei Wahrheiten ueber denselben Zustand.
         *
         * `null` bei Fehlschlag: dann steht nichts Neues auf Platte.
         */
        val written: ExpectationLedger.State?,
        /** Wie viele Ergebnisse die Kappung entfernt hat. */
        val droppedOutcomes: Int,
        /** Gesamtstand NACH diesem Schreibvorgang - persistiert. */
        val droppedOutcomesTotal: Long,
    )

    fun save(
        dir: File,
        state: ExpectationLedger.State,
        revision: Long,
        kopfstand: InterventionStamp,
        lastObservationGapTs: Long = 0L,
        droppedOutcomesTotalBefore: Long = 0L,
    ): Boolean =
        saveWithStats(dir, state, revision, kopfstand, lastObservationGapTs, droppedOutcomesTotalBefore).ok

    fun saveWithStats(
        dir: File,
        state: ExpectationLedger.State,
        revision: Long,
        kopfstand: InterventionStamp,
        lastObservationGapTs: Long = 0L,
        /** Stand VOR diesem Schreibvorgang - die neuen Verluste kommen dazu. */
        droppedOutcomesTotalBefore: Long = 0L,
    ): WriteStats {
        val start = System.nanoTime()
        var groesse = 0
        // EINMAL kappen und genau diesen Zustand schreiben - nicht zweimal
        // rechnen. Zwei Aufrufe koennten sich unterscheiden, sobald `kappen`
        // je eine Ordnung mit Gleichstaenden bekommt.
        val gekappt = kappen(state, kopfstand)
        val entfernt = (state.outcomes.size - gekappt.outcomes.size).coerceAtLeast(0)
        val gesamt = droppedOutcomesTotalBefore + entfernt
        val ok = saveInner(dir, gekappt, revision, kopfstand, lastObservationGapTs, gesamt) { groesse = it }
        return WriteStats(
            ok = ok, bytes = groesse, durationMs = (System.nanoTime() - start) / 1_000_000L,
            written = if (ok) gekappt else null, droppedOutcomes = entfernt,
            droppedOutcomesTotal = gesamt,
        )
    }

    private inline fun saveInner(
        dir: File,
        state: ExpectationLedger.State,
        revision: Long,
        kopfstand: InterventionStamp,
        lastObservationGapTs: Long,
        droppedOutcomesTotal: Long,
        bytes: (Int) -> Unit,
    ): Boolean = runCatching {
        val inhalt = FuseExpectationCodec.encode(state, revision, lastObservationGapTs, droppedOutcomesTotal)
        bytes(inhalt.toByteArray(Charsets.UTF_8).size)
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
        val geschrieben = ziel.isFile && ziel.readText(Charsets.UTF_8) == inhalt
        // 6. Den Zeugen setzen - NACH dem Nachweis, nie davor. Ein Zeuge fuer
        //    eine Generation, die es nicht gibt, machte jeden kuenftigen
        //    Erststart zu einem gemeldeten Datenverlust.
        //
        //    DIESE BEDINGUNG IST HEUTE DEFENSIV, NICHT WIRKSAM - und das steht
        //    hier, damit niemand sie fuer geprueft haelt. Jeder erreichbare
        //    Fehlerpfad kehrt vorher zurueck (Wurf oder `return@runCatching
        //    false`); ein abweichender Rueckleseinhalt OHNE Wurf laesst sich
        //    ohne Fake-Dateisystem nicht erzeugen. Eine Mutationsprobe auf
        //    `if (true)` bleibt deshalb gruen. Die Zeile bleibt trotzdem: sie
        //    kostet nichts und haelt die Reihenfolge fest, falls spaeter ein
        //    Pfad dazukommt, der hier mit `false` ankommt.
        if (geschrieben) runCatching {
            val zeuge = File(dir, SENTINEL_NAME)
            if (!zeuge.isFile) {
                zeuge.writeText("1")
                durability.syncDirectory(dir)
            }
        }
        geschrieben
    }.getOrDefault(false)

    /** Ob hier schon einmal eine Generation stand. */
    fun sentinelExists(dir: File): Boolean =
        runCatching { File(dir, SENTINEL_NAME).isFile }.getOrDefault(false)

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
