package app.aaps.fuse.core.ledger

/**
 * DER BEWEIS "ES GING NIE EIN BOLUS-KOMMANDO HINAUS".
 *
 * DER GEMESSENE ANLASS (Toni, 15.08.2026, erste Produktiv-Mahlzeit): FUSE hatte
 * um 19:07 einen SMB ueber 0,20 U beschlossen und gebucht. Das AAPS-Log zeigt
 * fuer diesen Zyklus KEINEN Eintrag in der Kommando-Warteschlange - die Adds
 * sind lueckenlos 19:00..19:06 und 19:08, dazwischen steht zweimal
 * "Medtrum connect - reason: Connection needed". Der Bolus wurde also
 * angefordert und nie ausgefuehrt. Der Ledger hatte fuer diese Tatsache
 * KEINEN EINGANG: die Zeile blieb als Haftung stehen und wurde erst nach
 * DIA + 2 h (bei DIA 9 also elf Stunden) durch den Zeitverfall abgeschrieben.
 * Bis dahin rechnete FUSE mit Insulin, das nie geflossen ist, und dosierte
 * entsprechend zu wenig.
 *
 * WARUM EIN EIGENER BEWEIS UND NICHT EINFACH EIN KUERZERER VERFALL: die
 * Richtungen sind nicht symmetrisch. Zu viel Haftung heisst "FUSE dosiert zu
 * wenig" - unangenehm. Zu wenig Haftung heisst "FUSE dosiert zu viel" -
 * gefaehrlich. Ein Zeitablauf ist KEIN Beweis dafuer, dass nichts geflossen
 * ist; er ist nur die Abwesenheit einer Beobachtung. Deshalb liest jede Regel
 * hier eine GESCHRIEBENE Tatsache, und keine einzige lautet "wir haben nichts
 * gesehen".
 *
 * Das Ereignis [LedgerEvent.QueueRejected] und seine gesamte Verarbeitung
 * existieren im Reducer bereits samt Persistenz und Validierung - es fehlte
 * ausschliesslich der Erzeuger. Diese Klasse ist er, und sie ist bewusst reine
 * Logik ohne Android: die Entscheidung, eine Haftung ohne IOB-Nachweis
 * freizugeben, gehoert an eine Stelle, die man vollstaendig durchtesten kann.
 */
object NotSentProof {

    /**
     * Was ueber den VORIGEN Zyklus bekannt ist. Alle Felder stammen aus
     * derselben Lesung - Zahlen aus verschiedenen Momenten zu paaren war in
     * diesem Projekt schon zweimal die Fehlerquelle.
     *
     * @param correlated Die Identitaetsprobe des Vorlaufs (dieselbe RT-Instanz,
     *   nicht nur dieselben Zahlen). Ist sie `false`, beschreibt `lastRun`
     *   einen fremden oder veralteten Lauf - dann gilt hier NICHTS als bewiesen.
     * @param ledgerPublishedU Die Menge, die auf der offenen Zeile steht. Ohne
     *   sie gibt es nichts zu entlasten.
     * @param gateStripped FUSEs eigenes Publikationsgate hat die Menge aus der
     *   RT entfernt. Beweis erster Ordnung: dann liest AAPS gar keine Menge.
     * @param gateSealed Das Gate hat seinen Beschluss durabel festgeschrieben.
     *   Ohne Siegel ist der Strip nicht belastbar.
     * @param gatePersistFailed Das Festschreiben ist gescheitert - dann ist der
     *   Zustand ungewiss und nichts gilt als bewiesen.
     * @param aapsConstrainedU Was AAPS nach seinen eigenen Beschraenkungen von
     *   der Menge uebrig liess. Exakt `0.0` heisst: AAPS selbst hat genullt.
     * @param smbSetByPumpPresent Ob AAPS im Apply-Block einen Platzhalter
     *   gesetzt hat. `false` beweist, dass der Block nie betreten und damit
     *   kein Bolus kommandiert wurde. `null` = nicht auswertbar.
     */
    data class Observation(
        val correlated: Boolean,
        val ledgerPublishedU: Double?,
        val gateStripped: Boolean,
        val gateSealed: Boolean,
        val gatePersistFailed: Boolean,
        val aapsConstrainedU: Double?,
        val smbSetByPumpPresent: Boolean?,
    )

    /**
     * Der Grund, aus dem die Zeile entlastet werden darf - oder `null`, wenn
     * nichts bewiesen ist. `null` ist der Normalfall und der sichere Ausgang:
     * die Zeile bleibt dann als Haftung stehen, genau wie bisher.
     */
    fun reasonFor(o: Observation): QueueRejectReason? {
        // OHNE KORRELATION GAR NICHTS. Ein nicht zuzuordnender `lastRun` darf
        // keine Haftung loeschen - er koennte einen ganz anderen Zyklus
        // beschreiben.
        if (!o.correlated) return null
        // Ohne gebuchte Menge gibt es nichts freizugeben.
        val menge = o.ledgerPublishedU ?: return null
        if (!menge.isFinite() || menge <= 0.0) return null
        // Ein gescheitertes Festschreiben laesst den Zustand ungewiss.
        if (o.gatePersistFailed) return null

        // (A) FUSE HAT SELBST GESTRIPPT. Dann stand in der RT keine Menge, die
        // AAPS haette weiterreichen koennen - der staerkste der drei Belege,
        // weil er ganz ohne Fremdannahme auskommt.
        if (o.gateStripped && o.gateSealed) return QueueRejectReason.GATE_BLOCKED

        // (B) AAPS HAT GENULLT. Dieselbe Kette, nur von der anderen Seite:
        // eine Menge von exakt 0 wird nicht kommandiert.
        val nachConstraints = o.aapsConstrainedU
        if (nachConstraints != null && nachConstraints.isFinite() && nachConstraints == 0.0)
            return QueueRejectReason.CONSTRAINT_ZERO

        // (C) DER APPLY-BLOCK WURDE NIE BETRETEN. Nur gueltig, wenn ueberhaupt
        // eine Menge uebrig war (sonst greift schon B) - und nur, wenn die
        // Angabe auswertbar ist. Das ist Tonis 19:07-Fall.
        if (o.smbSetByPumpPresent == false && nachConstraints != null &&
            nachConstraints.isFinite() && nachConstraints > 0.0
        ) return QueueRejectReason.BOLUS_IN_QUEUE

        return null
    }
}
