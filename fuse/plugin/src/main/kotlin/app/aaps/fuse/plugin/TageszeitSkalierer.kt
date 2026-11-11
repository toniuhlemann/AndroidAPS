package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.MidnightUtils
import app.aaps.fuse.core.controller.EarlySupply

/**
 * DIE SKALIERER JE TAGESZEIT AUS DEN EINSTELLUNGEN (Kandidat, Toni 11.11.2026) - an EINER Stelle gelesen und geprueft,
 * damit Regler, Marker-Angebot und Zusammenfassung dieselbe Regel sehen ([EarlySupply.TimeOfDayScales]).
 */
internal object TageszeitSkalierer {

    /** Wie eingestellt: [skalen] geprueft (auch ohne gesetzten Skalierer) - oder [fehler]. Genau eines ist `null`. */
    class Gelesen(val skalen: EarlySupply.TimeOfDayScales?, val fehler: EarlySupply.TimeOfDayScales.Violation?) {
        /** Die Skalierer, wenn einer gesetzt ist - sonst gilt ueberall der allgemeine. */
        val wirksam: EarlySupply.TimeOfDayScales? get() = skalen?.takeIf { it.active }
    }

    fun lesen(preferences: Preferences): Gelesen {
        val morgens = preferences.get(FuseDoubleKey.EarlySupplyScaleMorning)
        val mittags = preferences.get(FuseDoubleKey.EarlySupplyScaleMidday)
        val abends = preferences.get(FuseDoubleKey.EarlySupplyScaleEvening)
        val mittagAb = preferences.get(FuseIntKey.EarlySupplyMiddayStartMin)
        val abendAb = preferences.get(FuseIntKey.EarlySupplyEveningStartMin)
        val fehler = EarlySupply.TimeOfDayScales.violation(morgens, mittags, abends, mittagAb, abendAb)
        return Gelesen(
            skalen = if (fehler == null) EarlySupply.TimeOfDayScales.of(morgens, mittags, abends, mittagAb, abendAb) else null,
            fehler = fehler,
        )
    }

    /** Die Minute des Tages zur Zeit [ts] - dieselbe Uhr wie Nachtfenster und Profil. */
    fun minuteDesTages(ts: Long): Int = MidnightUtils.secondsFromMidnight(ts) / 60

    /** Der Skalierer, den "Mahlzeit" an einem Marker zur Zeit [markerTs] bucht; `null` = der allgemeine. */
    fun fuerMarker(skalen: EarlySupply.TimeOfDayScales?, markerTs: Long): Double? = skalen?.scaleAt(minuteDesTages(markerTs))

    /** Der Fehler als Satzteil fuer die Zeile am Feld. */
    fun fehlerText(fehler: EarlySupply.TimeOfDayScales.Violation): String = when (fehler) {
        EarlySupply.TimeOfDayScales.Violation.SCALE_OUT_OF_RANGE -> "ein Skalierer je Tageszeit liegt ausserhalb 0 bis 2"
        EarlySupply.TimeOfDayScales.Violation.TIME_OUT_OF_RANGE -> "eine Uhrzeit der Tageszeitfenster ist ungueltig"
        EarlySupply.TimeOfDayScales.Violation.TIMES_OUT_OF_ORDER ->
            "die Uhrzeiten muessen aufsteigen (04:00, dann 'Mittag beginnt', dann 'Abend beginnt')"
    }
}
