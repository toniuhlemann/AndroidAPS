package app.aaps.fuse.core.adapter

import app.aaps.fuse.core.controller.TbrPolicy

/**
 * Die Uebersetzung von Bauhelfer-Ausnahmen in einen Fehlercode (R81-F6).
 *
 * Zwei Sorten von Fehlern, absichtlich verschieden behandelt:
 *
 *   ENTSCHEIDUNGSFUNKTIONEN (`CandidateSearch.search`, `TbrPolicy.decide`)
 *       werfen NIE. Ungueltige Eingaben ergeben einen benannten Ablehnungsgrund.
 *
 *   BAUHELFER (`compressBasal`, `compressIsfSlots`, Kernel-Builder)
 *       werfen bei falsch geformten Arrays — das ist ein Programmierfehler des
 *       Adapters, kein Betriebszustand. Damit er trotzdem NIE aus dem Loop
 *       austritt, laeuft jeder Aufbau durch [build].
 *
 * Rein: der Android-Adapter benutzt dieselbe Funktion, aber sie braucht ihn
 * nicht — und damit ist die Regel schon heute pruefbar.
 */
object CoreInputGuard {

    data class Failure(val fault: TbrPolicy.FaultCode, val detail: String)

    sealed interface Outcome<out T> {
        data class Built<T>(val value: T) : Outcome<T>
        data class Failed(val failure: Failure) : Outcome<Nothing>
    }

    /**
     * Baut eine Kern-Eingabe. Jede `IllegalArgumentException`/`IllegalStateException`
     * aus einem Bauhelfer wird zu `CORE_INPUT_INVALID` — dem Fehlercode, der im
     * TBR-Vertrag genau eine Gegenmassnahme ausloest: kein SMB, laufende
     * positive TBR abbrechen, alles andere unveraendert.
     *
     * Fehler, die KEINE Eingabefehler sind, werden bewusst nicht gefangen.
     */
    fun <T> build(block: () -> T): Outcome<T> =
        try {
            Outcome.Built(block())
        } catch (e: IllegalArgumentException) {
            Outcome.Failed(Failure(TbrPolicy.FaultCode.CORE_INPUT_INVALID, e.message ?: "IllegalArgumentException"))
        } catch (e: IllegalStateException) {
            Outcome.Failed(Failure(TbrPolicy.FaultCode.CORE_INPUT_INVALID, e.message ?: "IllegalStateException"))
        }
}
