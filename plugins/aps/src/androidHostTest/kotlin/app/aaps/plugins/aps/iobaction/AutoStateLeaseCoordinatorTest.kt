package app.aaps.plugins.aps.iobaction

import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.automation.AutomationStateSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * AUTOSTATE-Koordinator. Der Kern ist nicht das Setzen, sondern das AUFRAEUMEN: wann die Basis
 * zurueckgeschrieben wird und wann eben NICHT, weil ein AAPS-Automat inzwischen geschrieben hat.
 * Ein auslaufendes Lease, das einen Schutz-Reset rueckgaengig macht, waere ein Dosier-Fehler.
 */
class AutoStateLeaseCoordinatorTest {

    /** Minimaler In-Memory-State-Dienst; zaehlt Schreibvorgaenge fuer die Nachweise. */
    private open class FakeStates(initial: Map<String, String> = emptyMap()) : AutomationStateInterface {
        val values = HashMap<String, String>(initial)
        val allowed = HashMap<String, List<String>>()
        val writes = ArrayList<Pair<String, String>>()
        override fun inState(stateName: String, state: String) = values[stateName] == state
        override fun setState(stateName: String, state: String) {
            if (!hasStateValues(stateName)) throw IllegalStateException("unknown state")
            if (state !in getStateValues(stateName)) throw IllegalStateException("bad value")
            values[stateName] = state; writes.add(stateName to state)
        }
        override fun getState(stateName: String) = values[stateName] ?: error("unknown")
        override fun getAllStates() = values.entries.map { it.key to it.value }
        override fun getStateValues(stateName: String) = allowed[stateName] ?: emptyList()
        override fun setStateValues(stateName: String, values: List<String>) { allowed[stateName] = values }
        override fun hasStateValues(stateName: String) = allowed.containsKey(stateName)
        override fun deleteState(stateName: String) { values.remove(stateName); allowed.remove(stateName) }
        open override fun getStateSnapshot(stateName: String) =
            AutomationStateSnapshot(known = allowed.containsKey(stateName), value = values[stateName])
    }

    /** Gate-Quelle: derselbe Koordinator wie in Produktion, mit injizierten Gates. */
    private fun gateSource(
        channel: Boolean = true, autostate: Boolean = true, vo: Boolean = false,
    ) = AutoIsfValueLeaseCoordinator(mock(), mock()).apply {
        gatesReader = {
            AutoIsfValueLeaseCoordinator.Gates(
                channelEnabled = channel,
                capabilityEnabled = mapOf(AutoIsfCapability.AUTOSTATE to autostate),
                forcedValidateOnly = vo,
            )
        }
    }

    private val t0 = 1_784_500_000_000L
    private val e0 = 10_000L

    private fun applied(id: String = "lease1", ver: Long = 1L) =
        AutoStateLeaseCoordinator.RoomSetResult("APPLIED", null, false, null, id, ver)

    private fun states(): FakeStates = FakeStates(mapOf("MEAL_ACTIVE" to "false")).apply {
        setStateValues("MEAL_ACTIVE", listOf("true", "false"))
    }

    @Test fun `SET schreibt den State erst nach APPLIED`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        // Room lehnt ab -> der State bleibt unberuehrt
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) {
            AutoStateLeaseCoordinator.RoomSetResult("REJECTED", "REJECTED_POLICY", false, null, null, null)
        }
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        // Room committet -> jetzt wird geschrieben
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied() }
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("true")
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_ACTIVE)
    }

    @Test fun `CLEAR stellt die Basis wieder her`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied() }
        c.executeArmedClear(t0 + 60_000, e0 + 60_000) { applied("lease1", 1L) }
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
    }

    @Test fun `Fremdschreiber gewinnt - CLEAR stellt NICHT wieder her`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied() }
        // Eine AAPS-Automation setzt zurueck (z.B. Schutz-Reset bei Dip)
        st.setState("MEAL_ACTIVE", "false")
        val before = st.writes.size
        c.executeArmedClear(t0 + 60_000, e0 + 60_000) { applied("lease1", 1L) }
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
        assertThat(st.writes.size).isEqualTo(before)   // kein einziger Schreibvorgang mehr
    }

    @Test fun `Ablauf stellt die Basis wieder her und terminalisiert`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0, e0) { applied() }
        // kurz vor der Frist: nichts passiert
        c.enforce(t0 + 29 * 60_000, e0 + 29 * 60_000)
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("true")
        // auf der Frist gilt als abgelaufen
        c.enforce(t0 + 30 * 60_000, e0 + 30 * 60_000)
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        val pt = c.peekPendingTerminal()!!
        assertThat(pt.reason).isEqualTo(AutoStateLeaseCoordinator.REASON_EXPIRED)
        assertThat(pt.capability).isEqualTo("AUTOSTATE")
    }

    @Test fun `Fremdschreiber toetet die Lease beim naechsten enforce`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied() }
        st.setState("MEAL_ACTIVE", "false")
        c.enforce(t0 + 60_000, e0 + 60_000)
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        assertThat(c.peekPendingTerminal()!!.reason).isEqualTo(AutoStateLeaseCoordinator.REASON_FOREIGN)
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")   // Basis NICHT zurueckgeschrieben
    }

    @Test fun `abgelaufen zaehlt auch wenn nur die Wall-Uhr sprang`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied() }
        // Wall-Uhr weit vor, elapsed kaum bewegt -> trotzdem abgelaufen
        c.enforce(t0 + 200 * 60_000, e0 + 1_000)
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
    }

    @Test fun `abgelaufen zaehlt auch wenn nur elapsed lief`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied() }
        // Wall-Uhr zurueckgestellt, elapsed ueber der Frist
        c.enforce(t0 - 3_600_000, e0 + 200 * 60_000)
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
    }

    @Test fun `unbekannter State oder fremder Wert ist nicht erlaubt`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        assertThat(c.isValueAllowed("MEAL_ACTIVE", "true")).isTrue()
        assertThat(c.isValueAllowed("MEAL_ACTIVE", "vielleicht")).isFalse()
        assertThat(c.isValueAllowed("GIBT_ES_NICHT", "true")).isFalse()
    }

    // Runde 3 / F5: frueher wurde ein State OHNE Vorwert gesetzt und beim Ablauf nicht
    // zurueckgenommen — AutomationStateInterface kennt kein "Wert entfernen", der Wert waere
    // also PERMANENT festgeschrieben gewesen. Solche SETs werden jetzt abgelehnt.
    @Test fun `State ohne bekannte Basis wird gar nicht erst gesetzt`() {
        val st = FakeStates().apply { setStateValues("NEUER_STATE", listOf("A", "B")) }
        val c = AutoStateLeaseCoordinator(st, gateSource())
        var called = false
        val r = c.executeArmedSet("NEUER_STATE", "A", 30, t0, e0) { called = true; applied() }
        assertThat(called).isFalse()
        assertThat(r.room.errorCode).isEqualTo("REJECTED_POLICY")
        assertThat(st.values["NEUER_STATE"]).isNull()
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
    }

    @Test fun `scheiterndes setState fuehrt zu keiner lebenden Lease`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        // Wert ist nicht in der Liste des States -> setState wirft, Room hat aber committet
        val r = c.executeArmedSet("MEAL_ACTIVE", "vielleicht", 120, t0, e0) { applied() }
        assertThat(r.currentLeaseState).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        assertThat(c.peekPendingTerminal()!!.reason).isEqualTo(AutoStateLeaseCoordinator.REASON_FOREIGN)
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
    }

    @Test fun `ack entfernt nur den zuvor gesehenen Auftrag`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0, e0) { applied() }
        c.enforce(t0 + 30 * 60_000, e0 + 30 * 60_000)
        val pt = c.peekPendingTerminal()!!
        c.ackPendingTerminal(pt)
        assertThat(c.peekPendingTerminal()).isNull()
    }

    @Test fun `Statusabfrage nennt Lease und Policy-Hash ohne Seiteneffekt`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        assertThat(c.statusMap()["autoStateLeaseState"]).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied("abc", 4L) }
        val m = c.statusMap()
        assertThat(m["autoStateLeaseState"]).isEqualTo(AutoStateLeaseCoordinator.STATE_ACTIVE)
        assertThat(m["autoStateLeaseId"]).isEqualTo("abc")
        assertThat(m["autoStateLeaseVersion"]).isEqualTo(4L)
        assertThat(m["autoStateName"]).isEqualTo("MEAL_ACTIVE")
        assertThat(m["autoStateValue"]).isEqualTo("true")
        assertThat(m["serverAutoStatePolicyHash"]).isEqualTo(LocalCommandAutoStatePolicy.hash())
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_ACTIVE)
    }

    @Test fun `kein automatisches Nachsetzen waehrend der Lease`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied() }
        val after = st.writes.size
        // mehrere Zyklen ohne Fremdschreiber: der Wert wird NICHT erneut geschrieben
        c.enforce(t0 + 60_000, e0 + 60_000)
        c.enforce(t0 + 120_000, e0 + 120_000)
        assertThat(st.writes.size).isEqualTo(after)
    }

    // ---- Review-Befunde vom 25.07., je ein Nachweis ----

    // Befund 2: Verlaengern darf die Basis NICHT neu erfassen, sonst wird der ueberlagerte Wert
    // zur "Basis" und der State kehrt nie zum Original zurueck. Bei 30-min-TTL der Normalfall.
    @Test fun `Verlaengerung behaelt die urspruengliche Basis`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0, e0) { applied("l1", 1L) }
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("true")
        // Verlaengerung, waehrend unser eigener Wert steht
        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0 + 60_000, e0 + 60_000) { applied("l1", 2L) }
        // Ablauf muss auf die ECHTE Basis zurueckstellen, nicht auf "true"
        c.enforce(t0 + 91 * 60_000, e0 + 91 * 60_000)
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
    }

    // Befund 2b: ein Wechsel auf einen anderen State haette den alten ueberlagert
    // zurueckgelassen, ohne dass ihn noch etwas haelt.
    @Test fun `laufende Lease wird nicht auf einen anderen State umgebogen`() {
        val st = states().apply { setStateValues("MANUAL_TH", listOf("true", "false")) }
        st.values["MANUAL_TH"] = "false"
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied() }
        var called = false
        val r = c.executeArmedSet("MANUAL_TH", "true", 120, t0, e0) { called = true; applied("l2", 2L) }
        assertThat(called).isFalse()
        assertThat(r.room.errorCode).isEqualTo("REJECTED_STATE_CONFLICT")
        assertThat(st.values["MANUAL_TH"]).isEqualTo("false")
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("true")   // die erste Lease laeuft weiter
    }

    // Befund 3: Master-Schalter, Capability-Schalter und Validate-only muessen eine LAUFENDE
    // Lease beenden — und dabei die Basis zurueckstellen, sonst friert der State ein.
    @Test fun `Gate aus beendet die Lease und stellt die Basis zurueck`() {
        listOf(
            gateSource(channel = false),
            gateSource(autostate = false),
            gateSource(vo = true),
        ).forEach { src ->
            val st = states()
            val open = gateSource()
            val c = AutoStateLeaseCoordinator(st, open)
            c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied() }
            assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("true")
            // Gate zu: derselbe Koordinator, aber die Beobachtung meldet jetzt unsicher
            open.gatesReader = src.gatesReader
            c.enforce(t0 + 60_000, e0 + 60_000)
            assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
            assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
        }
    }

    @Test fun `unsicheres Gate verhindert ein SET ueberhaupt`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource(autostate = false))
        var called = false
        val r = c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { called = true; applied() }
        assertThat(called).isFalse()
        assertThat(r.room.outcome).isEqualTo("REJECTED")
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
    }

    // Befund 9: ein nicht lesbarer Snapshot ist kein Fremdschreiber — sonst stirbt die Lease
    // und laesst den ueberlagerten Wert stehen.
    @Test fun `unlesbarer State-Snapshot widerruft die Lease nicht`() {
        val st = object : FakeStates(mapOf("MEAL_ACTIVE" to "false")) {
            var fail = false
            override fun getStateSnapshot(stateName: String) =
                if (fail) throw IllegalStateException("transient") else super.getStateSnapshot(stateName)
        }.apply { setStateValues("MEAL_ACTIVE", listOf("true", "false")) }
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied() }
        st.fail = true
        c.enforce(t0 + 60_000, e0 + 60_000)
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_ACTIVE)
        assertThat(c.peekPendingTerminal()).isNull()
    }

    // Befund 1: der Capture muss eine echte Gate-Generation tragen — Room verlangt sie
    // nicht-nullable beim ersten SET.
    @Test fun `BaseCapture traegt eine Gate-Generation`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        var seen: AutoStateLeaseCoordinator.BaseCapture? = null
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { seen = it; applied() }
        assertThat(seen).isNotNull()
        assertThat(seen!!.gateGeneration).isAtLeast(0L)
    }

    // ---- Re-Review-Befunde (B) ----

    // B4: Gate und Frist duerfen NICHT davon abhaengen, ob der State gerade lesbar ist.
    @Test fun `unlesbarer Snapshot blockiert weder Gate- noch TTL-Widerruf`() {
        val st = object : FakeStates(mapOf("MEAL_ACTIVE" to "false")) {
            var fail = false
            override fun getStateSnapshot(stateName: String) =
                if (fail) throw IllegalStateException("transient") else super.getStateSnapshot(stateName)
        }.apply { setStateValues("MEAL_ACTIVE", listOf("true", "false")) }
        val open = gateSource()
        val c = AutoStateLeaseCoordinator(st, open)
        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0, e0) { applied() }
        st.fail = true
        // Frist abgelaufen, State unlesbar -> die Lease muss trotzdem sterben
        c.enforce(t0 + 30 * 60_000, e0 + 30 * 60_000)
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        assertThat(c.peekPendingTerminal()!!.reason).isEqualTo(AutoStateLeaseCoordinator.REASON_EXPIRED)
    }

    // B3: Gate-Wechsel WAEHREND der Room-Transaktion darf den State nicht mehr schreiben.
    @Test fun `Gate-Wechsel waehrend der Transaktion verhindert den State-Schreibvorgang`() {
        val st = states()
        val open = gateSource()
        val c = AutoStateLeaseCoordinator(st, open)
        val r = c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) {
            // waehrend der "Transaktion" legt der Nutzer den Schalter um
            open.gatesReader = gateSource(channel = false).gatesReader
            applied()
        }
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")   // NICHT geschrieben
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        assertThat(r.room.outcome).isEqualTo("APPLIED")           // historisch bleibt es APPLIED
        assertThat(c.peekPendingTerminal()).isNotNull()
    }

    // B5: fehlgeschlagenes setState bei einer VERLAENGERUNG darf keine Geister-Lease lassen.
    @Test fun `fehlgeschlagenes setState bei Verlaengerung hinterlaesst keine Geister-Lease`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied("l1", 1L) }
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_ACTIVE)
        // Wertliste des States schrumpft -> setState wirft bei der Verlaengerung
        st.allowed["MEAL_ACTIVE"] = listOf("false")
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0 + 60_000, e0 + 60_000) { applied("l1", 2L) }
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
    }

    // B7: nach einem Prozesstod muss die Basis zurueckgeschrieben werden — aber nur, wenn noch
    // unser Wert steht.
    @Test fun `Wiederherstellung nach Prozesstod nur bei unveraendertem Wert`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        st.values["MEAL_ACTIVE"] = "true"
        assertThat(c.restoreAfterProcessRestart("MEAL_ACTIVE", "true", "false"))
            .isEqualTo(AutoStateLeaseCoordinator.RestartRestore.RESTORED)
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
        // Fremdschreiber war schneller -> nicht anfassen. KEIN Fehler: erledigter Fall.
        st.values["MEAL_ACTIVE"] = "false"
        assertThat(c.restoreAfterProcessRestart("MEAL_ACTIVE", "true", "false"))
            .isEqualTo(AutoStateLeaseCoordinator.RestartRestore.NOT_OURS)
        // ohne bekannte Basis wird nichts erfunden — ebenfalls kein Fehler
        assertThat(c.restoreAfterProcessRestart("MEAL_ACTIVE", "true", null))
            .isEqualTo(AutoStateLeaseCoordinator.RestartRestore.NO_BASE)
    }

    // Runde 3 / F1: der Abbruchzweig nach der zweiten Gate-Pruefung muss aufraeumen wie der
    // Gate-Widerruf — sonst meldet currentState() ACTIVE fuer eine Lease, die Room ersetzt hat.
    @Test fun `Abbruch nach der zweiten Gate-Pruefung laesst keine Geister-Lease`() {
        val st = states()
        val open = gateSource()
        val c = AutoStateLeaseCoordinator(st, open)
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0, e0) { applied("l1", 1L) }
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_ACTIVE)
        // Verlaengerung, waehrend der "Transaktion" faellt das Gate
        c.executeArmedSet("MEAL_ACTIVE", "true", 120, t0 + 60_000, e0 + 60_000) {
            open.gatesReader = gateSource(channel = false).gatesReader
            applied("l1", 2L)
        }
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")   // Basis zurueckgestellt
    }

    // Runde 4: das Generations-Netz ist zurueckgenommen. Ein WERTGLEICHER Fremdschreiber
    // bleibt damit unerkannt — bekannte, bewusst akzeptierte Luecke (siehe Klassenkopf).
    // Der Test haelt fest, was stattdessen GILT: die Rueckstellung funktioniert.
    @Test fun `ohne Fremdschreiber wird beim Ablauf normal zurueckgestellt`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0, e0) { applied() }
        c.enforce(t0 + 30 * 60_000, e0 + 30 * 60_000)
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")
    }

    // ---- Review 26.07. ----

    // Befund 3: das gefaehrlichste der Runde. Zwischen Gate-Pruefung und Schreiben liegt die
    // Room-Transaktion; AutomationStateService synchronisiert auf sich selbst, nicht auf
    // unseren Lock. Ein Griff des Nutzers in diesem Fenster darf NICHT ueberschrieben und mit
    // frischer TTL wieder festgeschrieben werden — das waere kein verlorenes Rennen, sondern
    // eine spurlos geloeschte Ruecknahme.
    @Test fun `manuelle Ruecknahme waehrend des SET-Fensters wird nicht ueberschrieben`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0, e0) { applied("L1", 1) }
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("true")
        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_ACTIVE)

        // Verlaengerung — und WAEHREND der Room-Transaktion greift der Nutzer in AAPS ein.
        val res = c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0 + 60_000L, e0 + 60_000L) {
            st.values["MEAL_ACTIVE"] = "false"
            applied("L2", 2)
        }

        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")   // seine Hand gewinnt
        assertThat(res.currentLeaseState).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        assertThat(c.peekPendingTerminal()!!.reason).isEqualTo(AutoStateLeaseCoordinator.REASON_FOREIGN)
    }

    // Befund 8: schlaegt der State-Write bei einer VERLAENGERUNG fehl, steht unser Wert aus der
    // vorigen Runde noch — und die Lease, die ihn gehalten hat, ist gleich weg. Ohne
    // Rueckstellung bliebe er ohne jede Frist stehen.
    @Test fun `fehlgeschlagener Write bei Verlaengerung laesst keinen fristlosen Wert stehen`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0, e0) { applied("L1", 1) }
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("true")

        // "true" faellt aus der Werteliste — der naechste Write darauf scheitert.
        st.allowed["MEAL_ACTIVE"] = listOf("false")
        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0 + 60_000L, e0 + 60_000L) { applied("L2", 2) }

        assertThat(c.currentState()).isEqualTo(AutoStateLeaseCoordinator.STATE_NONE)
        assertThat(st.values["MEAL_ACTIVE"]).isEqualTo("false")   // Basis zurueckgestellt
    }

    // Befund 4 / H2: der Grund des letzten Endes muss den Status erreichen — "von Hand
    // entzogen" und "abgelaufen" verlangen entgegengesetztes Verhalten vom Aufrufer.
    @Test fun `der Status meldet den Grund des letzten Lease-Endes`() {
        val st = states()
        val c = AutoStateLeaseCoordinator(st, gateSource())
        assertThat(c.statusMap()["autoStateLastTerminalReason"]).isNull()

        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0, e0) { applied("L1", 1) }
        st.values["MEAL_ACTIVE"] = "false"                       // Fremdschreiber
        c.enforce(t0 + 60_000L, e0 + 60_000L)
        assertThat(c.statusMap()["autoStateLastTerminalReason"])
            .isEqualTo(AutoStateLeaseCoordinator.REASON_FOREIGN)

        // und nach einem echten Ablauf steht dort der ANDERE Grund
        c.executeArmedSet("MEAL_ACTIVE", "true", 30, t0 + 120_000L, e0 + 120_000L) { applied("L3", 3) }
        c.enforce(t0 + 120_000L + 31 * 60_000L, e0 + 120_000L + 31 * 60_000L)
        assertThat(c.statusMap()["autoStateLastTerminalReason"])
            .isEqualTo(AutoStateLeaseCoordinator.REASON_EXPIRED)
    }
}
