package app.aaps.fuse.plugin.ledger

import java.io.File
import java.io.FileDescriptor
import java.io.SyncFailedException

/**
 * Der Durabilitaets-Nachweis fuer JVM-Tests.
 *
 * Die Produktionsfassung ruft `android.system.Os` direkt - im Unittest ist das
 * ein Stub, der wirft. Frueher war der Verzeichnis-Sync deshalb reflektiv und
 * fehlertolerant; das machte die Tests gruen und liess PRODUKTION still auf
 * "best effort" zurueckfallen. Dieser Fake ersetzt die Toleranz durch eine
 * ausdrueckliche Einspeisung: Erfolg und Fehlschlag sind hier beide
 * herstellbar, und keiner davon passiert aus Versehen.
 */
class FakeDurability(
    private val fileFails: Boolean = false,
    private val dirFails: Boolean = false,
    /**
     * Erst AB dem n-ten Aufruf scheitern (1-basiert), davor gelingen.
     *
     * Seit dem Write-ahead-Marker braucht ein Zyklus MEHRERE Syncs - erst den
     * Marker, dann den Zustand. Ein pauschales "scheitert immer" laesst schon
     * den Marker platzen, und der interessante Fall - Marker liegt, Zustand
     * scheitert SPAET - waere gar nicht herstellbar.
     */
    private val fileFailsFrom: Int = Int.MAX_VALUE,
    private val dirFailsFrom: Int = Int.MAX_VALUE,
) : Durability {

    var fileSyncs = 0
        private set
    var dirSyncs = 0
        private set

    override fun syncFile(fd: FileDescriptor) {
        fileSyncs++
        if (fileFails || fileSyncs >= fileFailsFrom)
            throw SyncFailedException("eingespeist: Datei-Sync fehlgeschlagen (#$fileSyncs)")
    }

    override fun syncDirectory(dir: File) {
        dirSyncs++
        if (dirFails || dirSyncs >= dirFailsFrom)
            throw SyncFailedException("eingespeist: Verzeichnis-Sync fehlgeschlagen (#$dirSyncs)")
    }
}
