package app.aaps.fuse.core.controller

import kotlin.math.abs

/**
 * WEM GEHOERT DIE LAUFENDE ABGESENKTE TBR?
 *
 * ===================================================================
 * DAS PROBLEM, DAS DIESE DATEI LOEST
 * ===================================================================
 * [TbrPolicy.keep] beendet eine laufende TBR nur in zwei Faellen: sie
 * ist eine Null, oder sie liegt UEBER dem Profilbasal. Eine ABGESENKTE
 * TBR bleibt bewusst stehen - das ist C7b, der Schutz fremder
 * Absenkungen, und der soll bleiben.
 *
 * Mit der Teilbasal-Rueckkehr setzt FUSE aber selbst eine abgesenkte
 * TBR. Ohne Besitznachweis entstuende nach dem Uebergang
 * `PARTIAL -> RELEASED` eine Luege: im Widget stuende die normale
 * Freigabe, der SMB waere wieder offen, und die Pumpe liefe bis zum
 * Ablauf weiter mit reduzierter Rate. Bis zu 30 Minuten lang wuerde
 * FUSE ueber den eigenen Aktuatorzustand falsch berichten.
 *
 * ===================================================================
 * WARUM EIN PERSISTIERTER NACHWEIS NOETIG IST
 * ===================================================================
 * [TbrPolicy.Current] traegt Rate, Restdauer und Quelle - KEINE
 * Kennung und KEINEN Startzeitpunkt. Aus der laufenden TBR allein ist
 * "meine" nicht von "fremd" zu unterscheiden. Deshalb schreibt FUSE
 * beim Setzen mit, was es gesetzt hat, und der Nachweis muss einen
 * Neustart ueberleben - sonst waere nach jedem Prozessstart jede
 * eigene Teilrate ploetzlich "fremd" und bliebe stehen.
 *
 * ===================================================================
 * DIE ERKENNUNG IST FAIL-CLOSED IN RICHTUNG "FREMD"
 * ===================================================================
 * Im Zweifel gehoert sie NICHT uns, und dann wird sie NICHT beendet.
 * Das ist die richtige Richtung: eine fremde Absenkung faelschlich zu
 * beenden hiesse, ohne Auftrag Insulin zu erhoehen. Eine eigene
 * faelschlich stehen zu lassen heisst, laenger weniger zu geben - und
 * der SMB bleibt dabei gesperrt, weil er an derselben Bedingung haengt.
 *
 * Geprueft wird deshalb doppelt: die RATE muss passen UND die
 * RESTLAUFZEIT muss zu dem passen, was wir gesetzt haben. Ohne die
 * zweite Bedingung waere eine fremde Absenkung mit zufaellig gleicher
 * Rate als unsere gelesen worden.
 */
object PartialTbrOwnership {

    /**
     * Was FUSE zuletzt als eigene Teil-TBR gesetzt hat. Restartfest zu
     * halten; `null` heisst "keine eigene bekannt", nicht "keine laeuft".
     */
    data class Own(
        val rateUPerH: Double,
        /** Zeitpunkt der ANFORDERUNG, nicht der Bestaetigung. */
        val setAtTs: Long,
        val durationMin: Int,
    ) {

        val valid: Boolean
            get() = rateUPerH.isFinite() && rateUPerH > 0.0 &&
                setAtTs > 0L && durationMin > 0
    }

    /**
     * Toleranz der Restlaufzeit [min]. Die Pumpe bestaetigt spaeter als
     * wir anfordern, AAPS rundet auf ganze Minuten, und der Zyklus liegt
     * irgendwo dazwischen. Drei Minuten decken das; mehr wuerde anfangen,
     * fremde Absenkungen einzusammeln.
     */
    const val REMAINING_TOLERANCE_MIN = 3

    /**
     * Ist [current] nachweislich die von uns gesetzte Teil-TBR?
     *
     * `false` bei jedem Zweifel - einschliesslich fehlendem Nachweis,
     * abgelaufenem Nachweis, abweichender Rate, abweichender Restlaufzeit
     * und [TbrPolicy.SourceType.FAKE_EXTENDED] (ein als TBR gelesener
     * Extended Bolus ist nie unsere Teilrate und darf ohnehin nur gelesen
     * werden).
     */
    fun isOurs(
        own: Own?,
        current: TbrPolicy.Current?,
        nowTs: Long,
        basalStepUPerH: Double,
    ): Boolean {
        if (own == null || !own.valid) return false
        if (current == null || current.violation() != null) return false
        if (current.sourceType != TbrPolicy.SourceType.TEMP_BASAL) return false
        if (!basalStepUPerH.isFinite() || basalStepUPerH <= 0.0) return false
        if (nowTs < own.setAtTs) return false
        if (abs(current.absoluteRateUPerH - own.rateUPerH) > basalStepUPerH / 2.0) return false
        val vergangenMin = (nowTs - own.setAtTs) / 60_000.0
        val erwartetRest = own.durationMin - vergangenMin
        // Abgelaufen: dann laeuft dort etwas anderes, was auch immer.
        if (erwartetRest <= 0.0) return false
        return abs(current.remainingMin - erwartetRest) <= REMAINING_TOLERANCE_MIN
    }

    /**
     * WANN DER NACHWEIS VERFAELLT.
     *
     * Nach Ablauf der gesetzten Dauer plus Toleranz kann dort nichts mehr
     * von uns laufen. Den Nachweis dann stehen zu lassen hiesse, eine
     * spaetere fremde Absenkung mit derselben Rate faelschlich als unsere
     * zu lesen.
     */
    fun expired(own: Own?, nowTs: Long): Boolean {
        if (own == null || !own.valid) return true
        return nowTs - own.setAtTs > (own.durationMin + REMAINING_TOLERANCE_MIN) * 60_000L
    }
}
