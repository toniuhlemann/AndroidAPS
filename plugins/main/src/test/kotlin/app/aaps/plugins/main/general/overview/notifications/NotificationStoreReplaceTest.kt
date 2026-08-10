package app.aaps.plugins.main.general.overview.notifications

import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

/**
 * ATOMARES ERSETZEN EINER MELDUNG (Audit 10.08.2026).
 *
 * `add` frischt bei belegter Kennung nur Datum und Gueltigkeit auf - kein
 * neuer Text, keine neue Stufe. Fuer eine Warnung, die sich geaendert hat und
 * wieder auffallen soll, ist das zu wenig.
 *
 * Der naheliegende Ausweg - erst `EventDismissNotification`, dann
 * `EventNewNotification` - ist ein RENNEN: beide laufen in `OverviewPlugin`
 * ueber getrennte Rx-Streams mit je eigenem `observeOn(io)`. Wird das
 * Hinzufuegen zuerst verarbeitet, scheitert es an der belegten Kennung, und das
 * spaetere Entfernen raeumt die alte Meldung weg. Uebrig bleibt GAR KEINE - fuer
 * den Kanal, der einen stillen Dosierstopp melden soll, der schlechteste
 * Ausgang.
 *
 * Deshalb eine EINZIGE Operation im Speicher, und deshalb dieser Test.
 */
class NotificationStoreReplaceTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var context: android.content.Context
    @Mock lateinit var iconsProvider: IconsProvider
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var notificationHolder: NotificationHolder
    @Mock lateinit var activePlugin: ActivePlugin

    private lateinit var store: NotificationStore

    @BeforeEach
    fun vorbereiten() {
        val preferences = mock(Preferences::class.java)
        // Ohne System-Benachrichtigung: die braeuchte einen echten
        // NotificationManager, und geprueft wird hier der SPEICHER.
        whenever(preferences.get(BooleanKey.AlertUrgentAsAndroidNotification)) doReturn false
        store = NotificationStore(
            aapsLogger, preferences, rh, context, iconsProvider, uiInteraction,
            dateUtil, notificationHolder, activePlugin,
        )
    }

    private fun inhalt(): List<Notification> {
        val f = NotificationStore::class.java.getDeclaredField("store")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (f.get(store) as MutableList<Notification>).toList()
    }

    /** Die Ausgangslage, die den ganzen Aufwand rechtfertigt: `add` ersetzt
     *  eben NICHT. */
    @Test
    fun `add ersetzt einen belegten Platz nicht`() {
        assertTrue(store.add(Notification(Notification.FUSE_LEDGER_HOLD, "alt", Notification.NORMAL)))
        assertFalse(store.add(Notification(Notification.FUSE_LEDGER_HOLD, "neu", Notification.URGENT)))

        val n = inhalt().single { it.id == Notification.FUSE_LEDGER_HOLD }
        assertEquals("alt", n.text) { "genau das ist das Problem - der Text bleibt der alte" }
        assertEquals(Notification.NORMAL, n.level) { "und die Dringlichkeit auch" }
    }

    /**
     * DER PFLICHTNACHWEIS: gegen einen belegten Platz werden Text UND Stufe
     * ersetzt, und die neue Meldung bleibt vorhanden.
     */
    @Test
    fun `replace ersetzt Text und Dringlichkeit am belegten Platz`() {
        store.add(Notification(Notification.FUSE_LEDGER_HOLD, "alte Warnung", Notification.NORMAL))

        assertTrue(store.replace(Notification(Notification.FUSE_LEDGER_HOLD, "neue Warnung", Notification.URGENT))) {
            "die neue Meldung muss tatsaechlich aufgenommen worden sein"
        }

        val treffer = inhalt().filter { it.id == Notification.FUSE_LEDGER_HOLD }
        assertEquals(1, treffer.size) { "genau eine - kein Duplikat, keine Leere" }
        assertEquals("neue Warnung", treffer.single().text)
        assertEquals(Notification.URGENT, treffer.single().level)
    }

    /** Auf einem freien Platz verhaelt es sich wie `add` - der Aufrufer soll
     *  nicht vorher wissen muessen, ob schon etwas steht. */
    @Test
    fun `replace legt auch auf einem freien Platz an`() {
        assertTrue(store.replace(Notification(Notification.FUSE_LEDGER_HOLD, "erste", Notification.URGENT)))
        assertEquals("erste", inhalt().single { it.id == Notification.FUSE_LEDGER_HOLD }.text)
    }

    /** Fremde Meldungen bleiben unberuehrt - ersetzt wird EIN Platz, nicht der
     *  Speicher. */
    @Test
    fun `replace laesst andere Meldungen stehen`() {
        store.add(Notification(Notification.PUMP_WARNING, "Pumpe", Notification.URGENT))
        store.add(Notification(Notification.FUSE_LEDGER_HOLD, "alt", Notification.NORMAL))

        store.replace(Notification(Notification.FUSE_LEDGER_HOLD, "neu", Notification.URGENT))

        assertEquals("Pumpe", inhalt().single { it.id == Notification.PUMP_WARNING }.text)
        assertEquals("neu", inhalt().single { it.id == Notification.FUSE_LEDGER_HOLD }.text)
    }
}
