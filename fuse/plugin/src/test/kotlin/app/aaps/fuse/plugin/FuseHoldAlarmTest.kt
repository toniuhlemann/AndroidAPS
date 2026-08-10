package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S1 UND S2 AUS DER AUDITVORBEREITUNG (10.08.2026).
 *
 * Beide Fehler lagen in derselben ungeprueften Ecke: die Meldung des
 * Ledger-Holds war im Plugin verdrahtet und nur ueber den ganzen
 * Android-Aufbau erreichbar. Deshalb steht die Regel jetzt in
 * [FuseHoldAlarm] - und deshalb steht hier ein Test.
 */
class FuseHoldAlarmTest {

    private val recovery = FuseHoldAlarm.Kennung(0L, "LEDGER_RECOVERY_HOLD")
    private val global = FuseHoldAlarm.Kennung(47L, "LEDGER_GLOBAL_HOLD:IDENTITY_CONFLICT")
    private val leer = emptyMap<String, Int>()

    private fun melden(a: FuseHoldAlarm.Aktion) = a as FuseHoldAlarm.Aktion.Melden

    // ---- S2: der Alarm darf nicht verstummen -----------------------------

    /**
     * DER PFLICHTNACHWEIS: Hold 1 -> Aufloesung -> Hold 2 meldet WIEDER.
     *
     * `NotificationStore.add` frischt bei belegter Kennung nur das Datum auf -
     * kein Text, keine Stufe, kein Ton. Ohne Ruecknahme bei der Aufloesung
     * bliebe die alte Meldung stehen, und der zweite Hold - Tage spaeter, mit
     * ganz anderer Ursache - waere lautlos. FUSE gaebe nichts ab, und der
     * Kanal, der genau das melden soll, schwiege.
     */
    @Test
    fun `nach einer Aufloesung meldet der naechste Hold wieder`() {
        var zuletzt: FuseHoldAlarm.Kennung? = null

        val ersteMeldung = FuseHoldAlarm.naechste(true, global, mapOf("IDENTITY_CONFLICT" to 47), zuletzt)
        assertTrue(ersteMeldung is FuseHoldAlarm.Aktion.Melden)
        zuletzt = melden(ersteMeldung).kennung

        // Solange derselbe Hold steht: keine Tapete.
        assertEquals(
            FuseHoldAlarm.Aktion.Nichts,
            FuseHoldAlarm.naechste(true, global, mapOf("IDENTITY_CONFLICT" to 47), zuletzt),
        )

        // Aufloesung -> die Meldung MUSS zurueckgenommen werden.
        assertEquals(
            FuseHoldAlarm.Aktion.Zuruecknehmen,
            FuseHoldAlarm.naechste(false, FuseHoldAlarm.Kennung(0L, null), leer, zuletzt),
        )
        zuletzt = null

        // Und der naechste Hold meldet sich WIEDER.
        val zweite = FuseHoldAlarm.naechste(true, recovery, leer, zuletzt)
        assertTrue(zweite is FuseHoldAlarm.Aktion.Melden) {
            "ein zweiter Hold darf nicht am belegten Meldungsplatz scheitern"
        }
    }

    /** Ohne stehende Meldung gibt es auch nichts zurueckzunehmen - sonst
     *  schickte jeder gesunde Zyklus ein Dismiss-Ereignis los. */
    @Test
    fun `ohne Hold und ohne Meldung passiert nichts`() {
        assertEquals(
            FuseHoldAlarm.Aktion.Nichts,
            FuseHoldAlarm.naechste(false, FuseHoldAlarm.Kennung(0L, null), leer, null),
        )
    }

    /**
     * DIE GENERATION ALLEIN REICHT NICHT ALS SCHLUESSEL.
     *
     * `state.holdGeneration` zaehlt nur fuer Fehler AUS dem Zustand. Ein
     * `recoveryHold` laesst sie auf 0 - zwei aufeinanderfolgende Holds
     * verschiedener Herkunft haetten dieselbe Generation, und der zweite waere
     * als "schon gemeldet" durchgefallen.
     */
    @Test
    fun `ein anderer Grund bei gleicher Generation meldet sich neu`() {
        val zuletzt = FuseHoldAlarm.Kennung(0L, "LEDGER_MIGRATION_PENDING")
        val jetzt = FuseHoldAlarm.Kennung(0L, "LEDGER_RECOVERY_HOLD")
        assertTrue(FuseHoldAlarm.naechste(true, jetzt, leer, zuletzt) is FuseHoldAlarm.Aktion.Melden) {
            "gleiche Generation, anderer Grund - das ist ein NEUER Befund"
        }
    }

    // ---- S1: die Quelle gehoert in den Text ------------------------------

    /**
     * Drei der vier Hold-Quellen haben ueberhaupt keine Fehlerliste
     * (recoveryHold, persistFailed, migrationPending). Stuende nur "kein
     * Fehler benannt" da, laese sich der Befund nicht einordnen - dabei ist
     * der Grund genau bekannt.
     */
    @Test
    fun `der Grund steht im Meldungstext, auch ohne Fehlerliste`() {
        val t = FuseHoldAlarm.text(recovery, leer)
        assertTrue(t.contains("LEDGER_RECOVERY_HOLD")) { "die Quelle muss dastehen" }
        assertTrue(t.contains("Reparatur")) { "ein Ausweg gehoert dazu" }

        val mitFehlern = FuseHoldAlarm.text(global, mapOf("IDENTITY_CONFLICT" to 47, "PHASE_VIOLATION" to 1))
        assertTrue(mitFehlern.contains("IDENTITY_CONFLICT x47"))
        assertTrue(
            mitFehlern.indexOf("IDENTITY_CONFLICT") < mitFehlern.indexOf("PHASE_VIOLATION")
        ) { "haeufigste Ursache zuerst" }
    }

    // ---- Der Vertrag ans Absetzen ----------------------------------------

    /**
     * SCHEITERT DAS ABSETZEN, GILT DER BEFUND NICHT ALS GEMELDET.
     *
     * Diesen Test gibt es, weil die Mutationsprobe ihn eingefordert hat: die
     * Eigenschaft lag im Plugin, und dort kommt kein Test hin - die Mutation
     * "Kennung schon vor dem Absetzen merken" blieb gruen. Eine Eigenschaft,
     * die eine Mutation ueberlebt, ist keine.
     *
     * Sie ist nicht kosmetisch: gilt ein fehlgeschlagener Versuch als erfolgt,
     * meldet sich GENAU DIESER Befund nie wieder - der Regler steht still und
     * der Kanal schweigt.
     */
    @Test
    fun `ein fehlgeschlagenes Absetzen wird im naechsten Zyklus wiederholt`() {
        val z = FuseHoldAlarm.Zustand()
        var versuche = 0

        z.verarbeite(true, global, leer, melden = { versuche++; false }, zuruecknehmen = {})
        assertEquals(1, versuche)
        assertEquals(null, z.gemeldet) { "misslungen heisst nicht gemeldet" }

        // Naechster Zyklus, gleiche Lage: es wird ERNEUT versucht.
        z.verarbeite(true, global, leer, melden = { versuche++; true }, zuruecknehmen = {})
        assertEquals(2, versuche) { "ohne Wiederholung bliebe der Regler stumm stehen" }
        assertEquals(global, z.gemeldet)

        // Und danach ist Ruhe - keine Tapete.
        z.verarbeite(true, global, leer, melden = { versuche++; true }, zuruecknehmen = {})
        assertEquals(2, versuche)
    }

    /** Nach einer Reparatur ist der Befund weg - und mit ihm das Gedaechtnis,
     *  sonst schwiege ein spaeterer gleichartiger Hold. */
    @Test
    fun `vergessen laesst denselben Befund wieder melden`() {
        val z = FuseHoldAlarm.Zustand()
        var gemeldet = 0
        z.verarbeite(true, global, leer, melden = { gemeldet++; true }, zuruecknehmen = {})
        assertEquals(1, gemeldet)

        z.vergessen()

        z.verarbeite(true, global, leer, melden = { gemeldet++; true }, zuruecknehmen = {})
        assertEquals(2, gemeldet)
    }

    /** Die Aufloesung nimmt zurueck und leert das Gedaechtnis in EINEM Schritt -
     *  sonst haette der naechste Hold einen belegten Platz vorgefunden. */
    @Test
    fun `die Aufloesung nimmt zurueck und vergisst`() {
        val z = FuseHoldAlarm.Zustand()
        var zurueck = 0
        z.verarbeite(true, global, leer, melden = { true }, zuruecknehmen = {})
        z.verarbeite(false, FuseHoldAlarm.Kennung(0L, null), leer, melden = { true }, zuruecknehmen = { zurueck++ })
        assertEquals(1, zurueck)
        assertEquals(null, z.gemeldet)
    }

    /** Ein Hold ohne benannten Grund bleibt eine Meldung - unbekannt ist der
     *  Fall, in dem am wenigsten klar ist, was los ist. */
    @Test
    fun `ein Hold ohne Grund meldet sich trotzdem`() {
        val ohne = FuseHoldAlarm.Kennung(5L, null)
        val a = FuseHoldAlarm.naechste(true, ohne, leer, null)
        assertTrue(a is FuseHoldAlarm.Aktion.Melden)
        assertTrue(melden(a).text.contains("Grund unbekannt"))
    }
}
