package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE FRIST DES REBOUND-SONDERRECHTS (Toni 19.08.).
 *
 * DER GEMESSENE ANLASS, aus dem frisch gezogenen Trail:
 *
 *     letzter Marker   ~08:54
 *     13:41            Markeralter 287 min, Rebound noch 32 min offen
 *                      Evidenz wieder ACTIVE, Kredit +0,42 mg/dl/min
 *                      BG 109,8 gegen Rebound-Schwelle 98 + 40 = 138
 *     13:41-13:45      fuenf veroeffentlichte SMBs, zusammen 0,35 U
 *
 * Ohne die damals UNBEFRISTETE Kredit-Ausnahme haette das Rebound-Totband
 * diese fuenf Zyklen geblockt. Ab 13:46 lag der Zucker ueber 138 - dort
 * bremst die neue Regel nicht mehr. Die Aenderung ist also eng begrenzt.
 *
 * WAS SIE NICHT ANFASST, und das ist der Kern der Bauform: die Evidenzepisode
 * lebt unveraendert bis zum 360-Minuten-Deckel und darf weiter Bedarf
 * erzeugen. Befristet ist NUR ihr Recht, ein AKTIVES Rebound-Totband zu
 * entwaffnen. Das NACHT-Totband bleibt unbefristet entwaffnet - deshalb hat
 * [NightWindow.effectiveDeadbandMgdl] zwei getrennte Berechtigungen und nicht
 * ein gemeinsames Signal.
 */
class ReboundOverrideTtlTest {

    private val marker = 1_787_000_000_000L
    private val ttlMin = 120
    private val deadline = marker + ttlMin * 60_000L

    private fun darf(alterMin: Int, kredit: Boolean = true, frist: Long = deadline) =
        NightWindow.evidenceMayOverrideRebound(
            evidenceCreditActive = kredit,
            deadlineTs = frist,
            computeTs = marker + alterMin * 60_000L,
        )

    /** Der Zucker in einem Rebound-Fenster, gegen Ziel 98 und Band 40. */
    private fun band(
        reboundOverride: Boolean,
        nightOverride: Boolean = false,
        rebound: Boolean = true,
        nacht: Boolean = false,
    ) = NightWindow.effectiveDeadbandMgdl(
        reboundWindow = rebound,
        reboundDeadbandMgdl = 40.0,
        isNight = nacht,
        nightDeadbandMgdl = 45.0,
        markerBoost = false,
        reboundOverrideByEvidence = reboundOverride,
        nightOverrideByEvidence = nightOverride,
    )

    // ---- 1 + 2: die Kante ------------------------------------------------

    @Test
    fun `kurz vor der Frist darf die Evidenz entwaffnen`() {
        assertTrue(darf(alterMin = 119), "119 min: das Privileg gilt")
        // 119:59 - dieselbe Minute, eine Sekunde vor Ablauf.
        assertTrue(
            NightWindow.evidenceMayOverrideRebound(true, deadline, deadline - 1_000L),
            "119:59 MUSS noch gelten",
        )
        assertEquals(0.0, band(reboundOverride = true), 1e-9, "und das Band schweigt")
    }

    /**
     * HALB OFFENES FENSTER: bei EXAKT T+120 ist das Privileg beendet.
     *
     * Die Kante gehoert festgeschrieben, weil sie sonst bei jeder Umformung
     * unbemerkt kippen kann - und ein `<=` haette hier eine ganze Minute
     * zusaetzliches Sonderrecht bedeutet.
     */
    @Test
    fun `bei exakt der Frist gewinnt das Rebound-Totband`() {
        assertFalse(
            NightWindow.evidenceMayOverrideRebound(true, deadline, deadline),
            "T+120:00 - das Privileg ist beendet",
        )
        assertEquals(40.0, band(reboundOverride = false), 1e-9, "das Band ist wieder scharf")
    }

    // ---- 3 + 4: der reproduzierte 13:41-Fall -----------------------------

    /**
     * DER GEMESSENE FALL. 287 Minuten nach dem Marker, Kredit fliesst,
     * Rebound offen, BG 109,8 unter der Schwelle 138 - es darf nichts mehr
     * durchkommen.
     */
    @Test
    fun `der 13-41-Fall wird jetzt gebremst`() {
        val darfNoch = darf(alterMin = 287)
        assertFalse(darfNoch, "nach 287 min gibt es kein Sonderrecht mehr")

        val bandMgdl = band(reboundOverride = darfNoch)
        assertEquals(40.0, bandMgdl, 1e-9)
        val schwelle = 98.0 + bandMgdl
        assertTrue(109.8 < schwelle, "BG 109,8 liegt unter der Schwelle $schwelle - keine positive Menge")
    }

    /**
     * UND DIE GEGENPROBE AB 13:46: derselbe spaete Zustand, aber der Zucker
     * ueber der Schwelle. Dort bremst das Totband ohnehin nicht, und die
     * Evidenz bleibt uneingeschraenkt nutzbar - die Aenderung ist eng
     * begrenzt und schaltet die spaete Evidenz NICHT ab.
     */
    @Test
    fun `derselbe spaete Zustand ueber der Schwelle bleibt frei`() {
        val bandMgdl = band(reboundOverride = darf(alterMin = 287))
        assertTrue(139.0 >= 98.0 + bandMgdl, "BG 139 liegt ueber der Schwelle - das Band greift gar nicht")
    }

    // ---- 5: ein neuer Marker erneuert die Frist --------------------------

    /**
     * DAS PRIVILEG HAENGT AM MARKER, NICHT AN DER EPISODE. Ein neuer Druck
     * setzt eine neue Frist, auch wenn dieselbe 360-Minuten-Evidenzepisode
     * weiterlaeuft - sonst waere eine zweite angekuendigte Mahlzeit ohne
     * Sonderrecht, nur weil die erste lange her ist.
     */
    @Test
    fun `ein neuer Marker erneuert die Frist innerhalb derselben Episode`() {
        val spaet = marker + 287 * 60_000L
        assertFalse(darf(alterMin = 287), "die alte Frist ist abgelaufen")

        // Neuer Druck bei T+287, neue Frist bei T+287+120.
        val neueFrist = spaet + ttlMin * 60_000L
        assertTrue(
            NightWindow.evidenceMayOverrideRebound(true, neueFrist, spaet + 60_000L),
            "der neue Marker MUSS ein neues Privileg geben",
        )
    }

    // ---- 6: fail-closed ---------------------------------------------------

    @Test
    fun `ohne Frist gibt es kein Privileg`() {
        assertFalse(darf(alterMin = 10, frist = 0L), "keine Frist - kein Sonderrecht")
        assertFalse(darf(alterMin = 10, kredit = false), "ohne Kredit ebenso wenig")
    }

    @Test
    fun `die typisierten Ablehnungsgruende benennen die Lage`() {
        val jetzt = marker + 10 * 60_000L
        fun grund(
            kredit: Boolean = true,
            frist: Long = deadline,
            mTs: Long = marker,
            pin: Long = marker,
            revoked: Boolean = false,
        ) = NightWindow.reboundOverrideDenial(kredit, frist, jetzt, mTs, pin, revoked)

        assertNull(grund(), "im gueltigen Fall gibt es keinen Grund")
        assertEquals(NightWindow.ReboundOverrideDenial.REVOKED, grund(revoked = true))
        assertEquals(NightWindow.ReboundOverrideDenial.NO_MARKER, grund(mTs = 0L))
        assertEquals(NightWindow.ReboundOverrideDenial.MARKER_FUTURE, grund(mTs = jetzt + 60_000L))
        assertEquals(NightWindow.ReboundOverrideDenial.MARKER_MISMATCH, grund(pin = marker - 60_000L))
        assertEquals(NightWindow.ReboundOverrideDenial.EXPIRED, grund(frist = jetzt))
        assertEquals(NightWindow.ReboundOverrideDenial.NO_CREDIT, grund(kredit = false))
    }

    /**
     * EINE FRIST GEHOERT GENAU EINEM DRUCK (Codex 19.08.).
     *
     * DER FALL: Marker A hat eine noch laufende Frist gepinnt. Danach steht in
     * den Preferences Marker B - nach einem Warmstart oder weil er extern
     * gesetzt wurde -, und DIESER Prozess hat B nie beobachtet. Ohne den
     * Identitaetsvergleich erbte B die Frist von A und bekaeme ein
     * Sonderrecht, das ihm niemand gegeben hat.
     *
     * "Es gibt eine Frist" ist nicht "es ist SEINE Frist" - dieselbe
     * Unterscheidung, an der die Ledger-Bindung schon einmal haengengeblieben
     * ist.
     */
    @Test
    fun `eine fremde Frist gibt dem neuen Marker kein Sonderrecht`() {
        val markerB = marker + 30 * 60_000L
        val jetzt = markerB + 5 * 60_000L

        // Die Frist von A laeuft noch - sie wuerde ohne Pruefung greifen.
        assertTrue(deadline > jetzt, "der Aufbau braucht eine NOCH LAUFENDE Frist von A")

        assertEquals(
            NightWindow.ReboundOverrideDenial.MARKER_MISMATCH,
            NightWindow.reboundOverrideDenial(
                evidenceCreditActive = true,
                deadlineTs = deadline,
                computeTs = jetzt,
                markerTs = markerB,
                pinnedForTs = marker,
                revoked = false,
            ),
            "Marker B darf die Frist von A nicht erben",
        )
    }

    // ---- 7: die Nacht bleibt unberuehrt ----------------------------------

    /**
     * DIE WICHTIGSTE ABGRENZUNG. Waere die Frist auf das gemeinsame Signal
     * gelegt worden, haette sie unbemerkt auch das Nacht-Totband befristet -
     * eine Aenderung am Nachtverhalten, die niemand angeordnet hat.
     */
    @Test
    fun `das Nacht-Totband bleibt mit spaetem Kredit entwaffnet`() {
        val spaetOhneReboundRecht = darf(alterMin = 287)
        assertFalse(spaetOhneReboundRecht)

        val nurNacht = band(
            reboundOverride = spaetOhneReboundRecht,
            nightOverride = true,
            rebound = false,
            nacht = true,
        )
        assertEquals(0.0, nurNacht, 1e-9, "die Nacht ist weiterhin entwaffnet")
    }

    /** Und beide zusammen: das Rebound-Band greift, die Nacht nicht. */
    @Test
    fun `bei beiden Fenstern gewinnt das schaerfere`() {
        val b = band(reboundOverride = false, nightOverride = true, rebound = true, nacht = true)
        assertEquals(40.0, b, 1e-9, "das Rebound-Band bleibt scharf, die Nacht ist entwaffnet")
    }
}
