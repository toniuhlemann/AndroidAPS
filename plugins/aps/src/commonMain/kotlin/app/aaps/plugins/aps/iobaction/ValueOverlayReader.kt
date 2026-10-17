package app.aaps.plugins.aps.iobaction

import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider

/**
 * Die Aufloesungsregel des Wert-Overlays, als eigene Einheit — damit sie PRUEFBAR ist.
 *
 * Die Getter im Plugin sind private und haengen an einem Dutzend injizierter Abhaengigkeiten;
 * die eine Zeile, auf der die OFF-Zusage ruht, waere dort nur per Reflection erreichbar. Hier
 * steht sie fuer sich und wird vom Plugin wie vom Test benutzt — die Zusage ist damit belegt
 * statt begruendet.
 *
 * REGEL: kein Snapshot oder Feld nicht in der Overlay-Map ⇒ Basis-Preference. Ein Leser muss
 * nie zwischen "kein Overlay" und "Overlay mit Basiswert" unterscheiden.
 */
object ValueOverlayReader {

    // The field names of the value overlays. Here in common code, because the APS reads them; the
    // channel policy on Android uses the same constants.
    const val F_RATIO = "ratio"
    const val F_ACCE = "acce"
    const val F_BRAKE = "brake"
    const val F_PP = "pp"
    const val F_DURA = "dura"
    const val F_LOW = "lowRange"
    const val F_HIGH = "highRange"

    fun weight(snapshot: EffectiveAutoIsfSettingsProvider.Snapshot?, field: String, base: Double): Double =
        snapshot?.weightOverrides?.get(field) ?: base

    fun ratio(snapshot: EffectiveAutoIsfSettingsProvider.Snapshot?, base: Double): Double =
        snapshot?.smbRatioEffective ?: base
}
