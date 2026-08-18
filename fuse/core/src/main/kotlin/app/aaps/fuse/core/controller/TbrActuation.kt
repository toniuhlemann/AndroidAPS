package app.aaps.fuse.core.controller

import kotlin.math.abs

/**
 * FAEHRT DIE PUMPE DANACH ANDERS ALS VORHER?
 *
 * Toni 18.08.: "`tbrChanged` muss echte Aktuation bedeuten: neue Rate,
 * Abbruch/Rueckkehr zum Profil oder relevante Laufzeitverlaengerung - nicht
 * lediglich ein nicht-null TBR-Feld."
 *
 * WARUM DAS NICHT EGAL IST. Der Eingriffsstempel entwertet jede offene
 * Prognose, die er ueberholt. Wuerde jede gesetzte TBR als Aenderung zaehlen,
 * stiege er in fast jedem Zyklus - FUSE erneuert die laufende Anforderung
 * regelmaessig, ohne dass sich an der Pumpe etwas tut. Es entstuende nie eine
 * Strecke ohne Eingriff und damit nie lambda-Evidenz. Der Nachweis waere
 * formal korrekt und praktisch tot.
 *
 * DIE UMGEKEHRTE RICHTUNG IST GEFAEHRLICHER. Eine uebersehene Aenderung
 * erfindet Nachweis, wo eingegriffen wurde. Deshalb liefert diese Funktion
 * `null` - "nicht beurteilbar" -, sobald eine der Zahlen unbrauchbar ist, und
 * der Stempel wertet das als Eingriff.
 */
object TbrActuation {

    /**
     * Ab wann eine laengere Laufzeit ein eigener Eingriff ist.
     *
     * Eine Zero-TBR mit 3 Minuten Rest auf 30 zu verlaengern ist KEINE
     * Bestaetigung, sondern 27 zusaetzliche Minuten Zurueckhaltung - eine
     * Bahn, die mit der urspruenglichen Behauptung nichts mehr zu tun hat.
     * Fuenf Minuten sind der kleinste Abstand, der bei Tonis Ein-Minuten-Takt
     * nicht schon durch das normale Nachfuehren entsteht.
     */
    const val DURATION_TOLERANCE_MIN = 5

    /**
     * @param current die LAUFENDE Anforderung, `null` wenn keine laeuft.
     * @param requestRateUPerH die neue Rate, `null` wenn dieser Zyklus keine
     *   TBR anfordert.
     * @param requestDurationMin die neue Laufzeit, `null` wie oben.
     * @param profileBasalUPerH was die Pumpe OHNE TBR faehrt - der
     *   Bezugspunkt, wenn gerade keine laeuft.
     * @param basalStepUPerH die Rasterung der Pumpe. Die halbe Schrittweite
     *   ist die Toleranz: feiner kann die Pumpe nicht unterscheiden, und was
     *   sie nicht unterscheiden kann, hat sie nicht geaendert.
     * @return `true` bei echter Aktuation, `false` bei Bestaetigung des
     *   Laufenden, `null` wenn es sich nicht entscheiden laesst.
     */
    fun changed(
        current: Current?,
        requestRateUPerH: Double?,
        requestDurationMin: Int?,
        profileBasalUPerH: Double,
        basalStepUPerH: Double,
    ): Boolean? {
        if (!profileBasalUPerH.isFinite() || !basalStepUPerH.isFinite() || basalStepUPerH <= 0.0) return null
        current?.let { if (!it.absoluteRateUPerH.isFinite() || it.remainingMin < 0) return null }

        // KEINE ANFORDERUNG heisst: es bleibt, wie es ist. Eine laufende TBR
        // laeuft weiter, sonst faehrt das Profil weiter - in beiden Faellen
        // aendert DIESER Zyklus nichts. Das Auslaufen einer TBR ist kein
        // Eingriff dieses Zyklus, sondern die Folge eines frueheren, der
        // damals gezaehlt wurde.
        if (requestRateUPerH == null || requestDurationMin == null) return false
        if (!requestRateUPerH.isFinite() || requestDurationMin < 0) return null

        // ABBRUCH: Dauer 0 beendet eine laufende TBR - und aendert nur dann
        // etwas, wenn ueberhaupt eine lief.
        if (requestDurationMin == 0) return current != null

        // OHNE LAUFENDE TBR IST JEDE ANFORDERUNG EIN EINGRIFF - auch eine auf
        // Profilhoehe.
        //
        // Das sieht zunaechst zu streng aus (die Pumpe faehrt im selben
        // Moment gleich), ist es aber nicht: eine TBR nagelt die Rate fuer
        // ihre Laufzeit FEST, auch gegen einen Profilwechsel. Tonis Profil
        // wechselt stuendlich; eine 30-Minuten-TBR ueber eine solche Kante
        // laesst die Pumpe danach anders fahren als ohne sie. Diesen Verlauf
        // kennt der Baustein nicht - also zaehlt er, statt zu raten.
        //
        // Ohne diese Zeile entschiede hier zufaellig die Laufzeitregel weiter
        // unten (30 > 0 + 5), und dieselbe Anforderung mit kurzer Dauer
        // faellte das umgekehrte Urteil. Eine Regel, die vom Zufall ihrer
        // Reihenfolge lebt, ist keine.
        if (current == null) return true

        val faehrtJetzt = current.absoluteRateUPerH
        // Halbe Schrittweite: was unter der Rasterung liegt, kann die Pumpe
        // gar nicht anders fahren.
        if (abs(requestRateUPerH - faehrtJetzt) > basalStepUPerH / 2.0) return true

        // Gleiche Rate - dann entscheidet die Laufzeit. Nur die VERLAENGERUNG
        // zaehlt: eine kuerzere Anforderung nimmt Zurueckhaltung zurueck, und
        // das ist die Richtung, in der die urspruengliche Behauptung eher
        // wieder gilt als weniger.
        val restJetzt = current.remainingMin
        return requestDurationMin > restJetzt + DURATION_TOLERANCE_MIN
    }

    /** Was gerade laeuft - nur die beiden Groessen, auf die es hier ankommt. */
    data class Current(val absoluteRateUPerH: Double, val remainingMin: Int)
}
