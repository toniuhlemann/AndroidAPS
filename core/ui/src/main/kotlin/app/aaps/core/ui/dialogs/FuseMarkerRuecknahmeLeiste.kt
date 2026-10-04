package app.aaps.core.ui.dialogs

import android.view.View
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.R
import com.google.android.material.snackbar.Snackbar

/**
 * DIE RUECKGAENGIG-LEISTE DER MARKER-RUECKNAHME (Toni 04.10.).
 *
 * Steht hier und nicht in den beiden Fragmenten - aus demselben Grund wie
 * [FuseMarkerDialog]: Uebersichtsknopf und FUSE-Tab duerfen fuer denselben
 * Knopf kein unterschiedliches Sicherheitsniveau haben.
 *
 * Die Leiste ist nur die Anzeige. Ob und wann zurueckgenommen wird,
 * entscheidet FUSE: die Vormerkung laeuft auch ab, wenn die Leiste nie
 * erscheint oder der Bildschirm gewechselt wird.
 */
object FuseMarkerRuecknahmeLeiste {

    /**
     * @param restMs Zeit bis zur Faelligkeit der vorgemerkten Ruecknahme.
     * @param rueckgaengig hebt die Vormerkung auf; `false` = es war keine mehr offen.
     * @param aktualisieren Knopfzustand neu zeichnen (nach Rueckgaengig und nach Ablauf).
     */
    fun zeige(anker: View, rh: ResourceHelper, restMs: Long, rueckgaengig: () -> Boolean, aktualisieren: () -> Unit) {
        val sekunden = ((restMs + 999L) / 1_000L).toInt().coerceAtLeast(1)
        Snackbar.make(anker, rh.gs(R.string.fuse_meal_withdraw_pending, sekunden), restMs.toInt().coerceAtLeast(1_000))
            .setAction(rh.gs(R.string.fuse_meal_withdraw_undo)) {
                val ok = rueckgaengig()
                Snackbar.make(
                    anker,
                    rh.gs(if (ok) R.string.fuse_meal_withdraw_undone else R.string.fuse_meal_withdraw_too_late),
                    Snackbar.LENGTH_SHORT,
                ).show()
                aktualisieren()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    aktualisieren()
                }
            })
            .show()
    }
}
