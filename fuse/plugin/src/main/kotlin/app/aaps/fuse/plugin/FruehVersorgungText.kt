package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.EarlySupply
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * DIE KOMPAKTEN ZUSAMMENFASSUNGEN von Erwartungskonto und frueher Versorgung (Toni): je Feld nur der eigene Wert und
 * seine Wirkung auf die Dosierung; die lange Erklaerung steht einmal je Block und ausfuehrlich im Eingabedialog.
 *
 * Reine Texte aus einer [Lage] - ohne Android, damit testbar. Die Zahlen rechnet der Aufrufer mit DEMSELBEN
 * Insulinmodell wie der Regler ([EarlySupply.need], [EarlySupply.cornerRole]).
 */
object FruehVersorgungText {

    /** Alles, was die Zusammenfassungen brauchen; `null` = nicht berechenbar (kein Modell, ungueltige Werte). */
    data class Lage(
        val kontoAn: Boolean,
        val fruehAn: Boolean,
        /** Eckwerte nach 30/60/90/120 min [U] (0 = nicht gesetzt). */
        val ecken: List<Double>,
        val kurveGueltig: Boolean,
        /** Je Eckpunkt seine Rolle fuer den fruehen Bedarf; `null` = nicht berechenbar. */
        val rollen: List<EarlySupply.CornerRole?>,
        /** Frueher Bedarf ohne Skalierer [U] und der bestimmende Punkt; `null` = nicht berechenbar. */
        val bedarf: EarlySupply.Need?,
        /** Je Eckpunkt: um wie viel der fruehe Bedarf bei +0,1 U an diesem Punkt waechst [U]; `null` = nicht berechnet. */
        val bedarfJeZehntelU: List<Double?>,
        /** Die v2-Einstellungen, geprueft; `null` = ungueltig. */
        val einstellung: EarlySupply.Settings?,
        /** Expositionsgrenze im Mahlzeitkontext [U IOB]. */
        val expositionU: Double,
    ) {
        val budgetStabilU: Double? get() = budget(einstellung?.scaleStable)
        val budgetTiefU: Double? get() = budget(einstellung?.scaleAfterLow)
        private fun budget(skala: Double?): Double? {
            val b = bedarf ?: return null
            val e = einstellung ?: return null
            return max(0.0, min(e.budgetMaxU, (skala ?: return null) * b.rawU))
        }
    }

    private fun u(v: Double, stellen: Int = 1) = String.format(Locale.GERMANY, "%.${stellen}f U", v)
    private fun z(v: Double, stellen: Int = 1) = String.format(Locale.GERMANY, "%.${stellen}f", v)

    /** Beschreibung des Kontoblocks - einmal, ueber den Feldern. */
    const val BLOCK_KONTO =
        "Die Kurve sagt in vier Werten, wie viel Mahlzeitwirkung (in Insulin umgerechnet) bis 30, 60, 90 und 120 min " +
            "nach dem Marker typischerweise ankommt - kein Dosierplan. Die Werte muessen steigen, dazwischen wird linear " +
            "gerechnet. Ein Punkt bestimmt das Budget (steht am Feld); alle vier zusammen sind die Erwartung, an der ab " +
            "+20 min gemessen wird: zeigt sich weniger als erwartet, fliesst weniger nach. Die Stabilitaet entscheidet " +
            "am Marker, ob der Start als stabil oder als 'nach Tief' gilt. Tippe auf ein Feld fuer die ausfuehrliche Erklaerung."

    /** Beschreibung des v2-Blocks - einmal, ueber den Feldern. */
    const val BLOCK_FRUEH =
        "Gilt nur, wenn du am Marker 'Nur Erwartung' waehlst. Budget = die fruehe Gabe, die deine Kurve rechtzeitig " +
            "deckt, mal Skalierer. Davon fliesst der Anfangsanteil sofort; der Rest ab +20 min nur, wenn sich die " +
            "Mahlzeit zeigt. Gemessene Sperren, maxIOB, iobTH und die Expositionsgrenze bleiben immer hart."

    /** Ein Eckpunkt der Kurve: eigener Wert und ob/ab wann er das Budget bestimmt. */
    fun kurvenpunkt(index: Int, lage: Lage): String {
        val wert = lage.ecken.getOrElse(index) { 0.0 }
        val eigen = u(wert, 3)
        if (lage.ecken.all { it == 0.0 }) return "$eigen - Kurve nicht gesetzt (dann gilt die Expertenzeile)"
        if (!lage.kurveGueltig) return "$eigen - Kurve ungueltig: die Werte muessen steigen, Erwartung aus"
        if (!lage.fruehAn) return eigen
        return when (val r = lage.rollen.getOrNull(index)) {
            EarlySupply.CornerRole.Binding -> {
                val budget = lage.budgetStabilU ?: return "$eigen - bestimmt das Budget"
                val e = lage.einstellung ?: return "$eigen - bestimmt das Budget"
                val roh = lage.bedarf?.rawU ?: return "$eigen - bestimmt das Budget"
                if (e.scaleStable * roh >= e.budgetMaxU) return "$eigen - bestimmt das Budget, aber Budget-Max deckelt (${u(budget)})"
                val plus = lage.bedarfJeZehntelU.getOrNull(index)
                    ?.let { min(e.budgetMaxU, e.scaleStable * (roh + it)) - budget }
                    ?: return "$eigen - bestimmt das Budget (jetzt ${u(budget)})"
                "$eigen - bestimmt das Budget: je +0,1 U hier +${u(plus, 2)} (stabil, jetzt ${u(budget)})"
            }
            is EarlySupply.CornerRole.From -> "$eigen - bestimmt das Budget erst ab ${u(r.valueU, 2)}"
            EarlySupply.CornerRole.Never -> "$eigen - bestimmt das Budget nicht (dafuer muesste er ueber den naechsten Punkt steigen)"
            null -> eigen
        }
    }

    fun budgetMax(wert: Double, lage: Lage): String {
        val eigen = u(wert)
        val roh = lage.bedarf?.rawU ?: return eigen
        val e = lage.einstellung ?: return "$eigen - Einstellungen ungueltig, fruehe Versorgung aus"
        val stabil = e.scaleStable * roh
        return if (stabil > e.budgetMaxU) "$eigen - deckelt: Budget statt ${u(stabil)} nur $eigen"
        else "$eigen - greift nicht (Budget ${u(stabil)})"
    }

    fun skalierer(wert: Double, nachTief: Boolean, lage: Lage): String {
        val eigen = z(wert)
        val b = lage.bedarf ?: return eigen
        val budget = (if (nachTief) lage.budgetTiefU else lage.budgetStabilU) ?: return eigen
        if (wert == 0.0) return "$eigen - " + (if (nachTief) "nach Tief keine fruehe Versorgung" else "keine fruehe Versorgung")
        return "$eigen - frueher Bedarf ${u(b.rawU)} x ${z(wert)} = Budget ${u(budget)}"
    }

    fun anfangsanteil(wert: Double, lage: Lage): String {
        val eigen = z(wert, 2)
        val stabil = lage.budgetStabilU ?: return eigen
        val p0 = wert * stabil
        val p0Tief = lage.budgetTiefU?.let { wert * it }
        val text = StringBuilder("$eigen - am Druck ${u(p0)}")
        if (p0Tief != null) text.append(" (nach Tief ${u(p0Tief)})")
        text.append(", Rest ${u(max(0.0, stabil - p0))} ab +20 min")
        if (p0 > lage.expositionU) text.append("; Expositionsgrenze ${u(lage.expositionU)} begrenzt")
        return text.toString()
    }

    fun portion(wert: Double, lage: Lage): String {
        val eigen = u(wert)
        val stabil = lage.budgetStabilU ?: return "$eigen je Zyklus"
        val e = lage.einstellung ?: return "$eigen je Zyklus"
        val rest = max(0.0, stabil - e.initialShare * stabil)
        if (wert <= 0.0) return "$eigen - keine weiteren Portionen"
        return "$eigen je Zyklus - Rest ${u(rest)} in mindestens ${kotlin.math.ceil(rest / wert - 1e-9).toInt().coerceAtLeast(1)} Gaben"
    }

    fun jeZeitraum(wert: Double, zeitraumMin: Int, portionU: Double): String {
        if (wert <= 0.0) return "${u(wert)} - keine weiteren Portionen"
        val basis = "${u(wert)} je $zeitraumMin min"
        if (portionU <= 0.0) return basis
        if (wert < portionU) return "$basis - kleiner als eine Portion, begrenzt jede auf ${u(wert)}"
        val volle = kotlin.math.floor(wert / portionU + 1e-9).toInt()
        return "$basis - hoechstens $volle volle Portion${if (volle == 1) "" else "en"} darin (gleitend, kein Ansparen)"
    }

    fun hauptschalter(lage: Lage): String {
        if (!lage.fruehAn) return "Aus - 'Nur Erwartung' laeuft ueber das Konto ohne fruehe Versorgung"
        if (!lage.kontoAn) return "An, aber wirkungslos: das Erwartungskonto ist aus"
        val s = lage.budgetStabilU ?: return "An - Budget nicht berechenbar (Kurve oder Werte pruefen)"
        val t = lage.budgetTiefU
        return "An - Budget heute ${u(s)} stabil" + (t?.let { ", ${u(it)} nach Tief" } ?: "")
    }

    const val G1 = "Anfangsportion uebergeht den Guard (wie die Direktdosis)"
    const val G2 = "Anfangsportion uebergeht den Schwanz (wie die Direktdosis)"
    const val G3 = "Weitere Portionen: Guard rechnet die bestaetigte Mahlzeit mit (ab +20 min)"
    const val G4 = "Weitere Portionen: Schwanz rechnet die bestaetigte Mahlzeit mit"
}
