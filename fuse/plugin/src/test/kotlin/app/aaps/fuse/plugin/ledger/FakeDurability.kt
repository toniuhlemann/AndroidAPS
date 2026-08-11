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
) : Durability {

    var fileSyncs = 0
        private set
    var dirSyncs = 0
        private set

    override fun syncFile(fd: FileDescriptor) {
        fileSyncs++
        if (fileFails) throw SyncFailedException("eingespeist: Datei-Sync fehlgeschlagen")
    }

    override fun syncDirectory(dir: File) {
        dirSyncs++
        if (dirFails) throw SyncFailedException("eingespeist: Verzeichnis-Sync fehlgeschlagen")
    }
}
