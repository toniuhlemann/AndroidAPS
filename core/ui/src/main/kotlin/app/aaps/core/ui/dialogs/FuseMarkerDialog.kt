package app.aaps.core.ui.dialogs

import androidx.fragment.app.FragmentActivity
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.R

/**
 * DIE RUECKFRAGE ZUM MAHLZEITEN-KNOPF - einmal, fuer beide Knoepfe.
 *
 * Es gibt zwei: einen auf dem Uebersichtsschirm und einen im FUSE-Tab. Haetten
 * sie eigene Dialoge, haetten sie frueher oder spaeter verschiedene Texte -
 * und damit verschiedene Sicherheitsniveaus fuer dieselbe Handlung. Deshalb
 * steht der Text hier und die Regel, OB gefragt wird, in
 * `app.aaps.fuse.core.controller.MarkerPrompt`.
 *
 * SO KURZ WIE MOEGLICH, und das ist eine Anforderung und keine Nachlaessigkeit:
 * der Knopf wird mehrmals taeglich gedrueckt. Ein Dialog, den man vier Mal am
 * Tag wegwischt, wird nicht gelesen - und dann schuetzt er nicht mehr, er
 * gewoehnt nur ans Wegwischen. Die erste Fassung war ein Aufsatz; Toni hat sie
 * am Geraet gesehen und gestrichen.
 *
 * GEBLIEBEN ist genau das, was sich VON DRUCK ZU DRUCK aendert:
 *  - die Zahlen (erster Schritt, Rest der Huelle) - die Groessenordnung
 *    gehoert vor das Ja, nicht ins Log,
 *  - ein gemessenes Tief, die einzige Lage, die diesen Druck von einem
 *    gewoehnlichen unterscheidet.
 *
 * WEGGEFALLEN ist alles Gleichbleibende - dass der Druck die Prognose
 * ueberstimmt und dass eine Ruecknahme Abgegebenes nicht zurueckholt. Das
 * steht jetzt in der Einstellungsbeschreibung, wo man es EINMAL liest.
 */
object FuseMarkerDialog {

    fun show(
        activity: FragmentActivity,
        rh: ResourceHelper,
        facts: FuseOverviewSource.MarkerPromptFacts,
        onConfirm: Runnable,
    ) {
        val rest = (facts.envelopeU - facts.alreadyDeliveredU).coerceAtLeast(0.0)
        // EINE Zeile Zahlen. Der Rest steht in der Einstellungsbeschreibung -
        // dort liest man ihn EINMAL, hier saehe man ihn mehrmals taeglich.
        val text = StringBuilder(
            rh.gs(R.string.overview_fuse_meal_confirm_body, facts.firstStepU, rest)
        )
        // Das gemessene Tief ist die einzige Lage, die den Druck von einem
        // gewoehnlichen unterscheidet - deshalb als einziger Zusatz.
        if (facts.measuredLow)
            text.append("\n\n").append(rh.gs(R.string.overview_fuse_meal_confirm_low))

        OKDialog.showConfirmation(
            activity,
            rh.gs(R.string.overview_fuse_meal_confirm_title),
            text.toString(),
            onConfirm,
        )
    }
}
