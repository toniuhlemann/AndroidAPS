package app.aaps.fuse.core.controller

/**
 * REBOUND NACH DER ANFANGSPHASE (Toni 27.11.2026).
 *
 * Gilt das Erwartungskonto, hebt die Mahlzeit-Vollmacht die Rebound-Bremse auf (Tonis Regel: "das Rebound-Fenster
 * darf eine Mahlzeit nie blockieren"). Toni 27.11.: das soll nicht fuer das ganze Fenster gelten, sondern fuer die
 * Anfangsphase, in der die Kohlenhydrate zu wirken beginnen. Ein Tief DANACH heisst: das Insulin der Mahlzeit ist
 * schon da. Steigt der Zucker danach wieder, ist das zuerst die Gegen-KH des Tiefs und keine neue Mahlzeit. Dann gilt
 * der Rebound-Schutz wieder wie ohne Marker - fuer alles, was das Rebound-Fenster liest: Totband, Korrektur-Anteil ohne
 * Rampe, kurze Fortschreibung positiver Steigungen, Totband des langsamen Kanals.
 *
 * Die Anfangsphase endet, wenn die HAELFTE der angenommenen Mahlzeitwirkung erreicht ist
 * ([ExpectationAccount.Curve.halfTimeMin], aus der am Marker gepinnten Kurve dieser Mahlzeit mit ihrer Streckung).
 * Ein leeres Konto (Rest hoechstens ein Abgabeschritt) beendet die Aufhebung ebenfalls: die geplante Mahlzeitmenge ist
 * dann ganz geliefert.
 *
 * Unveraendert bleiben: ein Tief vor oder am Marker, ein Tief in der Anfangsphase (Insulin vor den KH), Mahlzeiten ohne
 * Budget ("Nur Marker": dort traegt der Normalweg die Mahlzeit), der Rueckfall ohne Konto und die Buchung des Kontos.
 * Ueber dem Totband korrigiert der Normalweg weiter, dann mit dem Korrektur-Anteil und der kurzen Fortschreibung.
 *
 * REINE FUNKTION. Die Verdrahtung lebt im Runner.
 */
object MealReboundPhase {

    /** Rest [U], bis zu dem das Konto als leer gilt: ein Abgabeschritt der Pumpe. */
    const val EMPTY_REST_U = 0.05

    enum class Reason {

        /** Das juengste Tief lag nach der Halbzeit der angenommenen Mahlzeitwirkung. */
        AFTER_HALF_TIME,

        /** Nach dem Marker gab es ein Tief, und das Konto ist leer. */
        ACCOUNT_EMPTY,
    }

    /**
     * Warum die Vollmacht den Rebound NICHT mehr aufhebt. `null`: sie hebt ihn auf wie bisher.
     *
     * @param markerTs gepinnte Markerzeit (Kennung der Autorisierung) [ms]
     * @param lastLowTs juengstes Tief (q1 unter der Rebound-Grenze) [ms], 0 = keines
     * @param a0U gebuchtes Budget dieser Mahlzeit [U]
     * @param restU was das Konto noch liefern darf [U]
     * @param halfTimeMin Halbzeit der angenommenen Mahlzeitwirkung [min nach Marker], `null` = keine gueltige Kurve
     */
    fun rearmReason(markerTs: Long, lastLowTs: Long, a0U: Double, restU: Double, halfTimeMin: Double?): Reason? {
        if (markerTs <= 0L || lastLowTs <= markerTs) return null
        if (!a0U.isFinite() || a0U <= EMPTY_REST_U) return null
        val lowAgeMin = (lastLowTs - markerTs) / 60_000.0
        if (halfTimeMin != null && halfTimeMin.isFinite() && lowAgeMin >= halfTimeMin) return Reason.AFTER_HALF_TIME
        if (restU.isFinite() && restU <= EMPTY_REST_U) return Reason.ACCOUNT_EMPTY
        return null
    }
}
