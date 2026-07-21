package app.aaps.core.interfaces.aps

/**
 * Capability-Matrix A1 (R10-G1-Fix): schmaler WRITER-Port fuer therapeutische Writer
 * (z.B. ActionSetIobTH), die eine Basis-Preference schreiben wollen, waehrend eine
 * Kanal-Lease aktiv sein koennte.
 *
 * Vertrag (R9-F2 "Schutz gewinnt beweisbar, nicht eventual-consistent"):
 *  - Der Writer ruft [invalidateBeforeExternalWrite] SYNCHRON VOR seinem SP-Write.
 *  - true  = Invalidation abgeschlossen ODER nachweislich keine aktive Lease → Write darf laufen.
 *  - false = Invalidation fehlgeschlagen/unklar → der Writer bricht fail-closed ab
 *            (definierter Action-Fehler, KEIN stiller Weiter-Write; R10-Test 5).
 *  - Bei Feature/Kanal/Capability OFF ist der Aufruf ein sicherer No-op (true).
 *  - AUSSCHLIESSLICH Invalidation — kein SET/CLEAR, kein Lease-Zugriff (R9-G1 bleibt
 *    read-only); die Implementierung ist derselbe ValueLeaseCoordinator.
 *  - Nie im APS-/DetermineBasal-Hotpath aufrufen (Actions sind Writer-Pfad).
 */
interface AutoIsfValueLeaseInvalidator {

    fun invalidateBeforeExternalWrite(capability: String, reason: String): Boolean

    companion object {

        const val CAPABILITY_IOBTH = "IOBTH"
    }
}
