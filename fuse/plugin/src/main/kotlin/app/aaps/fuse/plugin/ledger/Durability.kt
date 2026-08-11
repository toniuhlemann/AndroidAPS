package app.aaps.fuse.plugin.ledger

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

/**
 * DER DURABILITAETS-NACHWEIS des Ledger-Schreibwegs, als einspeisbare Abhaengigkeit.
 *
 * WARUM ALS SCHNITTSTELLE UND NICHT REFLEKTIV: die erste Fassung rief
 * `android.system.Os` ueber Reflexion und schluckte jeden Fehler, weil die
 * Klasse im JVM-Testpfad fehlt. Das loeste das Testproblem und schuf ein
 * schlimmeres - in PRODUKTION fiel der Verzeichnis-Sync damit still auf
 * "best effort" zurueck, und niemand haette es gemerkt. Ein Nachweis, der im
 * Fehlerfall schweigt, ist kein Nachweis.
 *
 * Jetzt ist die Produktionsfassung hart und die Tests speisen einen Fake ein.
 * Beides ist ausdruecklich, keines ist ein Nebeneffekt der Umgebung.
 *
 * WARUM DAS VERZEICHNIS ueberhaupt: nach dem Rename ist der INHALT durabel,
 * der Verzeichniseintrag aber noch nicht zwingend. Ein Stromverlust dazwischen
 * kann eine Datei hinterlassen, deren Bytes auf der Platte stehen und deren
 * Name nicht.
 *
 * WARUM FAIL-CLOSED VERTRETBAR IST, obwohl der Rename da schon gelaufen ist:
 * ein nicht belegbarer Verzeichnis-Sync kostet hoechstens eine unterlassene
 * Dosis. Die Revisionsauswahl beim naechsten Start rettet weiterhin aus
 * `target`, `.tmp` oder `.bak` - es geht also keine Generation verloren, nur
 * dieser eine Zyklus publiziert nicht.
 */
interface Durability {

    /** Bytes dieses Deskriptors auf das Medium zwingen. Wirft bei Fehlschlag. */
    fun syncFile(fd: FileDescriptor)

    /** Verzeichniseintrag auf das Medium zwingen. Wirft bei Fehlschlag. */
    fun syncDirectory(dir: File)

    companion object {

        /**
         * Die Produktionsfassung - direkt, nicht reflektiv, nicht tolerant.
         *
         * `android.system.Os` ist auf allen Zielversionen vorhanden. Im
         * JVM-Unittest ist es ein Stub, der wirft; deshalb speisen Tests einen
         * Fake ein, statt dass hier geschluckt wird.
         */
        val ANDROID: Durability = object : Durability {

            override fun syncFile(fd: FileDescriptor) = fd.sync()

            override fun syncDirectory(dir: File) {
                val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
                try {
                    Os.fsync(fd)
                } finally {
                    // Ein Fehler beim Schliessen aendert nichts am Nachweis -
                    // gesynct ist gesynct. Ihn durchzureichen wuerde einen
                    // gelungenen Persist nachtraeglich verwerfen.
                    runCatching { Os.close(fd) }
                }
            }
        }
    }
}
