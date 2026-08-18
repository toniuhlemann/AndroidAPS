package app.aaps.fuse.plugin.expectation

import org.junit.jupiter.api.Assertions.assertFalse
import app.aaps.fuse.plugin.ledger.FuseLedgerStore
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.plugin.ledger.Durability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileDescriptor

/**
 * DER STORE - und was ein Absturz zwischen den Schritten hinterlaesst.
 *
 * Die Schreibsequenz ist NICHT als Ganzes atomar. Jeder Zwischenzustand,
 * den ein Stromausfall hinterlassen kann, wird hier als Dateizustand
 * nachgebaut und geladen. Der Anspruch ist nicht "es geht nichts kaputt",
 * sondern: KEIN Zwischenzustand darf als gueltige, aber falsche Generation
 * durchgehen.
 */
class FuseExpectationStoreTest {

    private val t0 = 1_787_000_000_000L

    /** Fake-Durability: im JVM-Test gibt es kein Android-Os. */
    private class FakeDurability(val fehlerBeimVerzeichnis: Boolean = false) : Durability {

        var dateiSyncs = 0
        var verzeichnisSyncs = 0

        override fun syncFile(fd: FileDescriptor) {
            dateiSyncs++
        }

        override fun syncDirectory(dir: File) {
            verzeichnisSyncs++
            if (fehlerBeimVerzeichnis) error("Verzeichnis-Sync fehlgeschlagen")
        }
    }

    private fun eintrag(source: Long = t0, seg: Long = 1L) = ExpectationLedger.Entry(
        sourceTs = source, dueTs = source + 30 * 60_000L, segmentId = seg,
        anchorMgdl = 200.0, meanPredictedMgdl = 150.0,
        configGeneration = "cfg#1", interventionStamp = InterventionStamp("test-epoche", 42L),
        context = ExpectationLedger.ExpectationContext.CORRECTION,
        contextReason = ExpectationLedger.ContextReason.PURE_CORRECTION,
        safetyLowerPredictedMgdl = 40.0,
    )

    private fun zustand(n: Int = 2) = (
        ExpectationLedger.restore(
            entries = (1..n).map { eintrag(source = t0 + it * 60_000L) },
            consumed = setOf(ExpectationLedger.SampleId(1L, t0)),
            outcomes = listOf(
                ExpectationLedger.Outcome(
                    eintrag(), ExpectationLedger.Verdict.MISSED, t0 + 30 * 60_000L, 205.0,
                ),
            ),
            kopfstand = InterventionStamp("test-epoche", 42L),
        ) as ExpectationLedger.Restored.Valid
        ).state

    // ---- Der Normalfall ---------------------------------------------------

    @Test
    fun `eine geschriebene Generation wird unveraendert geladen`(@TempDir dir: File) {
        val store = FuseExpectationStore(FakeDurability())
        assertTrue(store.save(dir, zustand(), revision = 7L, kopfstand = InterventionStamp("test-epoche", 42L)))
        val geladen = store.load(dir, InterventionStamp("test-epoche", 42L))
        assertTrue(geladen is FuseExpectationStore.Loaded.Ok, "war $geladen")
        geladen as FuseExpectationStore.Loaded.Ok
        assertEquals(7L, geladen.revision)
        assertEquals(zustand().entries, geladen.state.entries)
        assertEquals(zustand().consumed, geladen.state.consumed)
        assertEquals(zustand().outcomes, geladen.state.outcomes)
    }

    /** Ein leeres Verzeichnis ist ein Erststart, kein Datenverlust. */
    @Test
    fun `ohne Datei ist es ein Erststart`(@TempDir dir: File) {
        assertTrue(FuseExpectationStore(FakeDurability()).load(dir, InterventionStamp("test-epoche", 42L)) is FuseExpectationStore.Loaded.Fresh)
    }

    /** BEIDE Syncs muessen laufen - Datei UND Verzeichnis. Ohne den zweiten
     *  kann die Umbenennung nach einem Stromausfall verschwinden, obwohl die
     *  Bytes auf dem Medium stehen. */
    @Test
    fun `Datei und Verzeichnis werden gesynct`(@TempDir dir: File) {
        val d = FakeDurability()
        val store = FuseExpectationStore(d)
        val stamp = InterventionStamp("test-epoche", 42L)
        store.save(dir, zustand(), 1L, kopfstand = stamp)
        assertEquals(1, d.dateiSyncs, "fsync auf die Datei")
        // ZWEI Verzeichnis-Syncs beim ERSTEN Mal: einer fuer die Rotation der
        // Generation, einer fuer den neu angelegten Zeugen. Der Zeuge braucht
        // seinen eigenen - verschwaende er nach einem Stromausfall, liefe ein
        // Datenverlust spaeter als Erststart durch, und genau das soll er
        // verhindern.
        assertEquals(2, d.verzeichnisSyncs, "Generation und Zeuge")

        // Ab dem zweiten Mal steht der Zeuge schon - dann bleibt es bei einem.
        store.save(dir, zustand(), 2L, kopfstand = stamp)
        assertEquals(3, d.verzeichnisSyncs, "kein weiterer Zeugen-Sync")
    }

    /** Schlaegt der Verzeichnis-Sync fehl, gilt die Generation als NICHT
     *  geschrieben - auch wenn die Bytes schon am Ziel liegen. */
    @Test
    fun `ein fehlgeschlagener Verzeichnis-Sync meldet Misserfolg`(@TempDir dir: File) {
        assertTrue(!FuseExpectationStore(FakeDurability(fehlerBeimVerzeichnis = true)).save(dir, zustand(), 1L, kopfstand = InterventionStamp("test-epoche", 42L)))
    }

    // ---- Absturz zwischen den Schritten -----------------------------------

    /**
     * ABSTURZ NACH DEM SCHREIBEN DER ZWISCHENDATEI, VOR DER UMBENENNUNG.
     *
     * Auf dem Medium liegen dann eine vollstaendige `.tmp` (neu) und ein
     * intaktes Ziel (alt). Erwartet wird die HOEHERE Revision - die
     * Zwischendatei -, denn sie ist vollstaendig und neuer.
     */
    @Test
    fun `Absturz vor der Umbenennung - die neuere Zwischendatei gewinnt`(@TempDir dir: File) {
        val store = FuseExpectationStore(FakeDurability())
        store.save(dir, zustand(1), revision = 3L, kopfstand = InterventionStamp("test-epoche", 42L))
        // Die naechste Generation bleibt als .tmp liegen.
        File(dir, FuseExpectationStore.FILE_NAME + ".tmp")
            .writeText(FuseExpectationCodec.encode(zustand(2), 4L, lastObservationGapTs = 0L, droppedOutcomesTotal = 0L), Charsets.UTF_8)

        val geladen = store.load(dir, InterventionStamp("test-epoche", 42L)) as FuseExpectationStore.Loaded.Ok
        assertEquals(4L, geladen.revision, "die vollstaendige neuere Generation gewinnt")
        assertEquals(2, geladen.state.entries.size)
    }

    /**
     * ABSTURZ ZWISCHEN DEN BEIDEN UMBENENNUNGEN - der gefaehrlichste Fall.
     *
     * Das Ziel ist bereits zur Sicherung gedreht, die Zwischendatei aber
     * noch nicht an seinen Platz gerueckt: es gibt KEIN Ziel. Ohne die drei
     * Kandidaten waere das Datenverlust.
     */
    @Test
    fun `Absturz zwischen den Umbenennungen - kein Ziel, aber Sicherung und Zwischendatei`(@TempDir dir: File) {
        val store = FuseExpectationStore(FakeDurability())
        store.save(dir, zustand(1), revision = 5L, kopfstand = InterventionStamp("test-epoche", 42L))
        // Zustand nach Schritt 2, vor Schritt 3.
        File(dir, FuseExpectationStore.FILE_NAME)
            .renameTo(File(dir, FuseExpectationStore.FILE_NAME + ".bak"))
        File(dir, FuseExpectationStore.FILE_NAME + ".tmp")
            .writeText(FuseExpectationCodec.encode(zustand(3), 6L, lastObservationGapTs = 0L, droppedOutcomesTotal = 0L), Charsets.UTF_8)
        assertTrue(!File(dir, FuseExpectationStore.FILE_NAME).exists(), "der Aufbau muss stimmen")

        val geladen = store.load(dir, InterventionStamp("test-epoche", 42L)) as FuseExpectationStore.Loaded.Ok
        assertEquals(6L, geladen.revision)
        assertEquals(3, geladen.state.entries.size)
    }

    /**
     * ABGEBROCHENER SCHREIBVORGANG: die Zwischendatei ist halb geschrieben.
     *
     * Sie darf die intakte Vorgaengergeneration nicht verdraengen - eine
     * unlesbare Datei ist kein Kandidat, aber auch kein Grund, die
     * lesbaren zu verwerfen.
     */
    @Test
    fun `eine halbe Zwischendatei verdraengt die intakte Generation nicht`(@TempDir dir: File) {
        val store = FuseExpectationStore(FakeDurability())
        store.save(dir, zustand(2), revision = 8L, kopfstand = InterventionStamp("test-epoche", 42L))
        File(dir, FuseExpectationStore.FILE_NAME + ".tmp")
            .writeText("""{"schema":1,"revision":9,"entr""", Charsets.UTF_8)

        val geladen = store.load(dir, InterventionStamp("test-epoche", 42L)) as FuseExpectationStore.Loaded.Ok
        assertEquals(8L, geladen.revision, "die intakte alte Generation bleibt gueltig")
    }

    /** Dasselbe fuer eine Datei aus Null-Bytes - genau das hinterlaesst ein
     *  Stromausfall auf manchen Dateisystemen. */
    @Test
    fun `eine Null-Byte-Datei verdraengt die intakte Generation nicht`(@TempDir dir: File) {
        val store = FuseExpectationStore(FakeDurability())
        store.save(dir, zustand(2), revision = 8L, kopfstand = InterventionStamp("test-epoche", 42L))
        File(dir, FuseExpectationStore.FILE_NAME + ".tmp").writeBytes(ByteArray(64))

        assertEquals(8L, (store.load(dir, InterventionStamp("test-epoche", 42L)) as FuseExpectationStore.Loaded.Ok).revision)
    }

    /**
     * ALLE DREI UNLESBAR: das ist Datenverlust und muss als solcher gemeldet
     * werden - NICHT als Erststart.
     *
     * Der Unterschied ist die halbe Sicherung: ein Erststart laeuft
     * stillschweigend leer weiter, ein Datenverlust gehoert benannt.
     */
    @Test
    fun `alle Generationen beschaedigt ist Datenverlust, kein Erststart`(@TempDir dir: File) {
        for (name in listOf(
            FuseExpectationStore.FILE_NAME,
            FuseExpectationStore.FILE_NAME + ".tmp",
            FuseExpectationStore.FILE_NAME + ".bak",
        )) File(dir, name).writeText("kaputt", Charsets.UTF_8)

        val geladen = FuseExpectationStore(FakeDurability()).load(dir, InterventionStamp("test-epoche", 42L))
        assertTrue(geladen is FuseExpectationStore.Loaded.Corrupt, "war $geladen")
        assertTrue((geladen as FuseExpectationStore.Loaded.Corrupt).reason.isNotBlank(), "mit Grund")
    }

    /** Eine SEMANTISCH unmoegliche Datei zaehlt genauso als beschaedigt -
     *  syntaktisch lesbar heisst nicht verwendbar. */
    @Test
    fun `eine semantisch unmoegliche Datei ist beschaedigt`(@TempDir dir: File) {
        File(dir, FuseExpectationStore.FILE_NAME).writeText(
            """{"schema":1,"revision":1,"entries":[],"consumed":[],"outcomes":[
                {"entry":{"sourceTs":$t0,"dueTs":${t0 + 1800000},"seg":1,"anchor":200,"mean":150,
                 "cfg":"cfg#1","rev":42},"verdict":"MISSED"}]}""",
            Charsets.UTF_8,
        )
        assertTrue(FuseExpectationStore(FakeDurability()).load(dir, InterventionStamp("test-epoche", 42L)) is FuseExpectationStore.Loaded.Corrupt)
    }

    // ---- Monotone Revision -------------------------------------------------

    /** Gewaehlt wird nach REVISION, nicht nach Dateizeit. Bei einem
     *  Uhrensprung waere die Zeit die falsche Auskunft. */
    @Test
    fun `die hoechste Revision gewinnt, unabhaengig von der Dateizeit`(@TempDir dir: File) {
        File(dir, FuseExpectationStore.FILE_NAME)
            .writeText(FuseExpectationCodec.encode(zustand(1), 100L, lastObservationGapTs = 0L, droppedOutcomesTotal = 0L), Charsets.UTF_8)
        // Die Sicherung ist NEUER auf der Platte, traegt aber die kleinere
        // Generation - sie darf nicht gewinnen.
        val bak = File(dir, FuseExpectationStore.FILE_NAME + ".bak")
        bak.writeText(FuseExpectationCodec.encode(zustand(3), 99L, lastObservationGapTs = 0L, droppedOutcomesTotal = 0L), Charsets.UTF_8)
        bak.setLastModified(System.currentTimeMillis() + 60_000L)

        val geladen = FuseExpectationStore(FakeDurability()).load(dir, InterventionStamp("test-epoche", 42L)) as FuseExpectationStore.Loaded.Ok
        assertEquals(100L, geladen.revision)
        assertEquals(1, geladen.state.entries.size)
    }

    /**
     * DIE REIHENFOLGE DER KANDIDATEN DARF NICHT ENTSCHEIDEN.
     *
     * Der Test darueber traf den Fall zufaellig nicht: dort trug der ZUERST
     * gepruefte Kandidat ohnehin die hoechste Revision, also waere auch ein
     * "nimm den ersten gueltigen" gruen geblieben. Die Mutationsprobe hat
     * das gezeigt. Hier traegt der ERSTE Kandidat (.tmp) bewusst die
     * KLEINERE Generation.
     */
    @Test
    fun `ein frueher geprueftes, aber aelteres Ergebnis gewinnt nicht`(@TempDir dir: File) {
        File(dir, FuseExpectationStore.FILE_NAME + ".tmp")
            .writeText(FuseExpectationCodec.encode(zustand(1), 5L, lastObservationGapTs = 0L, droppedOutcomesTotal = 0L), Charsets.UTF_8)
        File(dir, FuseExpectationStore.FILE_NAME)
            .writeText(FuseExpectationCodec.encode(zustand(4), 10L, lastObservationGapTs = 0L, droppedOutcomesTotal = 0L), Charsets.UTF_8)

        val geladen = FuseExpectationStore(FakeDurability()).load(dir, InterventionStamp("test-epoche", 42L)) as FuseExpectationStore.Loaded.Ok
        assertEquals(10L, geladen.revision, "die hoehere Generation gewinnt, nicht die zuerst geprueft")
        assertEquals(4, geladen.state.entries.size)
    }

    // ---- Die Rueckleseprobe ------------------------------------------------

    /**
     * WAS NICHT ZURUECKGELESEN WERDEN KANN, GILT NICHT ALS GESCHRIEBEN.
     *
     * Alles vor der Rueckleseprobe ist Absicht - sie ist der einzige
     * Nachweis. Simuliert ueber eine Durability, die beim Verzeichnis-Sync
     * die Zieldatei veraendert; auf dem Geraet waere das ein Medienfehler
     * oder ein abgebrochener Schreibvorgang, der erst spaeter auffaellt.
     */
    @Test
    fun `eine fehlgeschlagene Rueckleseprobe meldet Misserfolg`(@TempDir dir: File) {
        val saboteur = object : Durability {
            override fun syncFile(fd: FileDescriptor) = Unit
            override fun syncDirectory(d: File) {
                File(d, FuseExpectationStore.FILE_NAME).writeText("etwas anderes", Charsets.UTF_8)
            }
        }
        assertTrue(
            !FuseExpectationStore(saboteur).save(dir, zustand(), 1L, kopfstand = InterventionStamp("test-epoche", 42L)),
            "der Inhalt stimmt nicht mehr - das darf nicht als Erfolg gelten",
        )
    }

    /** Eine negative Revision kann aus keinem Schreibvorgang stammen. */
    @Test
    fun `eine negative Revision ist beschaedigt`(@TempDir dir: File) {
        File(dir, FuseExpectationStore.FILE_NAME).writeText(
            """{"schema":1,"revision":-1,"entries":[],"consumed":[],"outcomes":[]}""",
            Charsets.UTF_8,
        )
        assertTrue(FuseExpectationStore(FakeDurability()).load(dir, InterventionStamp("test-epoche", 42L)) is FuseExpectationStore.Loaded.Corrupt)
    }

    // ---- Begrenztes Wachstum ----------------------------------------------

    /**
     * DIE DATEI DARF UNTER KEINEN UMSTAENDEN UNBEGRENZT WACHSEN.
     *
     * Strukturell kann das nicht passieren; diese Grenze ist der Riegel fuer
     * den Fall, dass eine der Annahmen einmal nicht mehr stimmt.
     */
    @Test
    fun `die harten Obergrenzen greifen`(@TempDir dir: File) {
        val store = FuseExpectationStore(FakeDurability())
        val vieleEintraege = (1..FuseExpectationStore.MAX_ENTRIES + 50)
            .map { eintrag(source = t0 + it * 60_000L) }
        val gross = (
            ExpectationLedger.restore(vieleEintraege, emptySet(), emptyList(), kopfstand = InterventionStamp("test-epoche", 42L))
                as ExpectationLedger.Restored.Valid
            ).state
        val gekappt = store.kappen(gross, kopfstand = InterventionStamp("test-epoche", 42L))
        assertEquals(FuseExpectationStore.MAX_ENTRIES, gekappt.entries.size)
        // Die JUENGSTEN bleiben - der Nachweis lebt von der juengsten Strecke.
        assertEquals(
            vieleEintraege.takeLast(FuseExpectationStore.MAX_ENTRIES).map { it.id },
            gekappt.entries.map { it.id },
        )
        // Und was geschrieben wird, ist auch wieder ladbar.
        assertTrue(store.save(dir, gross, 1L, kopfstand = InterventionStamp("test-epoche", 42L)))
        val geladen = store.load(dir, InterventionStamp("test-epoche", 42L)) as FuseExpectationStore.Loaded.Ok
        assertEquals(FuseExpectationStore.MAX_ENTRIES, geladen.state.entries.size)
    }

    /** Unterhalb der Grenzen wird NICHTS gekappt - sonst ginge stillschweigend
     *  Nachweis verloren. */
    @Test
    fun `unterhalb der Grenzen bleibt alles erhalten`() {
        val s = zustand(5)
        assertEquals(s.entries.size, FuseExpectationStore(FakeDurability()).kappen(s, kopfstand = InterventionStamp("test-epoche", 42L)).entries.size)
    }

    // ---- Ablage und Zeuge -----------------------------------------------

    /**
     * DIE ABLAGE IST APP-INTERN UND GETRENNT (Toni 18.08.).
     *
     * Nicht im externen Exportverzeichnis - dort ist die Datei faelschbar, und
     * alle Semantikpruefungen dieses Bausteins sind von Hand erfuellbar. Und
     * nicht in der Reparaturdomaene des Insulinledgers.
     */
    @Test
    fun `die Ablage liegt in einem eigenen Unterverzeichnis`(@TempDir filesDir: File) {
        val dir = FuseExpectationStore.dirIn(filesDir)
        assertEquals(FuseExpectationStore.DIR_NAME, dir.name)
        assertEquals(filesDir, dir.parentFile)
        assertFalse(dir.path.contains("fuse_ledger"), "nicht in der Ledger-Domaene")
        assertFalse(
            FuseExpectationStore.SENTINEL_NAME == FuseLedgerStore.SENTINEL_NAME,
            "und ein eigener Zeuge - sonst liest einer den des anderen",
        )
    }

    /**
     * EINE VERSCHWUNDENE GENERATION IST KEIN ERSTSTART.
     *
     * Beide zeigen ein leeres Verzeichnis. Ohne den Zeugen begaenne der
     * Streak still neu und niemand wuesste, dass Nachweis verlorenging.
     */
    @Test
    fun `eine geloeschte Generation wird als Verlust gemeldet, nicht als Erststart`(@TempDir dir: File) {
        assertTrue(FuseExpectationStore(FakeDurability()).save(dir, zustand(), revision = 1L, kopfstand = InterventionStamp("test-epoche", 42L)))
        // Alles weg - nur der Zeuge bleibt.
        dir.listFiles()!!.filter { FuseExpectationStore.SENTINEL_NAME !in it.name }.forEach { it.delete() }

        val geladen = FuseExpectationStore(FakeDurability()).load(dir, InterventionStamp("test-epoche", 42L))
        assertTrue(geladen is FuseExpectationStore.Loaded.Corrupt, "$geladen")
    }

    /** Und der echte Erststart bleibt ein Erststart. */
    @Test
    fun `ein leeres Verzeichnis ohne Zeugen ist ein Erststart`(@TempDir dir: File) {
        assertTrue(FuseExpectationStore(FakeDurability()).load(dir, InterventionStamp("test-epoche", 42L)) is FuseExpectationStore.Loaded.Fresh)
    }

    /**
     * DER ZEUGE ENTSTEHT ERST NACH DEM NACHWEIS.
     *
     * Stuende er davor, machte ein gescheiterter erster Schreibversuch jeden
     * kuenftigen Erststart zu einem gemeldeten Datenverlust.
     */
    @Test
    fun `ein gescheiterter Schreibversuch hinterlaesst keinen Zeugen`(@TempDir parent: File) {
        val blockiert = File(parent, "datei-statt-verzeichnis").also { it.writeText("x") }
        val dir = File(blockiert, "unter")
        assertFalse(FuseExpectationStore(FakeDurability()).save(dir, zustand(), revision = 1L, kopfstand = InterventionStamp("test-epoche", 42L)))
        assertFalse(FuseExpectationStore(FakeDurability()).sentinelExists(dir))
    }
}
