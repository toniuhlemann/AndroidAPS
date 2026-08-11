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
 * WAS DER TEXT NENNEN MUSS, und jedes aus einem Grund:
 *  - den moeglichen ERSTEN Schritt und die GANZE Huelle, damit die
 *    Groessenordnung VOR dem Ja steht und nicht erst im Log,
 *  - ob der Druck die Prognose ueberstimmt (nur wenn die Einstellung an ist -
 *    sonst waere die Warnung eine Uebertreibung),
 *  - ein gemessenes Tief, weil es die Frage dringlicher macht,
 *  - und dass eine Ruecknahme bereits abgegebenes Insulin NICHT zurueckholt.
 *    Das ist der Punkt, den man ohne Hinweis falsch annimmt: der Knopf sieht
 *    aus wie ein Schalter, aber die eine Richtung ist nicht reversibel.
 */
object FuseMarkerDialog {

    fun show(
        activity: FragmentActivity,
        rh: ResourceHelper,
        facts: FuseOverviewSource.MarkerPromptFacts,
        onConfirm: Runnable,
    ) {
        val rest = (facts.envelopeU - facts.alreadyDeliveredU).coerceAtLeast(0.0)
        val text = StringBuilder(
            rh.gs(R.string.overview_fuse_meal_confirm_body, facts.firstStepU, rest)
        )
        if (facts.authorizesAgainstModel)
            text.append("\n\n").append(rh.gs(R.string.overview_fuse_meal_confirm_authorized))
        if (facts.measuredLow)
            text.append("\n\n").append(rh.gs(R.string.overview_fuse_meal_confirm_low))
        if (facts.alreadyDeliveredU > 0.0)
            text.append("\n\n")
                .append(rh.gs(R.string.overview_fuse_meal_confirm_already, facts.alreadyDeliveredU))
        text.append("\n\n").append(rh.gs(R.string.overview_fuse_meal_confirm_no_undo))

        OKDialog.showConfirmation(
            activity,
            rh.gs(R.string.overview_fuse_meal_confirm_title),
            text.toString(),
            onConfirm,
        )
    }
}
