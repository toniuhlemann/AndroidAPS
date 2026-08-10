package app.aaps.fuse.plugin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.fuse.plugin.databinding.FuseFragmentBinding
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Der FUSE-Reiter — ein Leseglas, kein Bedienfeld.
 *
 * KEIN "jetzt rechnen"-Knopf, und der Grund ist pruefbar, nicht Geschmack: ein
 * manueller Lauf dosiert zwar nichts (das entscheidet der Loop nach den
 * Constraints), er ruft aber `observer.step` erneut auf. Bei gleichem
 * `sourceTs` ist der Schritt zwar `accepted = false`, die Health-Uhr laeuft
 * trotzdem weiter. Ein Knopf wuerde also genau den Zustand verstellen, den man
 * gerade beobachten will.
 *
 * `FusePlugin` wird DIREKT injiziert und nicht ueber `activePlugin.activeAPS`
 * geholt. Zwei Gruende: der Zugriff dort wirft, wenn kein APS gewaehlt ist
 * (`checkNotNull`), und ein `as?` faengt einen Wurf nicht. Und der Schirm soll
 * gerade dann noch lesbar sein, wenn FUSE NICHT mehr das aktive APS ist.
 */
class FuseFragment : DaggerFragment() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var fusePlugin: FusePlugin

    private val disposable = CompositeDisposable()
    private var _binding: FuseFragmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FuseFragmentBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.fuseMealMarkerS.setOnClickListener { fusePlugin.toggleMealMarker(dateUtil.now(), 0); update() }
        binding.fuseMealMarker.setOnClickListener { fusePlugin.toggleMealMarker(dateUtil.now(), 1); update() }
        binding.fuseMealMarkerL.setOnClickListener { fusePlugin.toggleMealMarker(dateUtil.now(), 2); update() }
    }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventAPSCalculationFinished::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ update() }, fabricPrivacy::logException)
        update()
    }

    override fun onPause() {
        disposable.clear()
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun update() {
        // Nach onDestroyView kann ein Ereignis noch eintreffen — dann gibt es
        // kein Binding mehr, und ein Zugriff waere ein Absturz im Beobachter.
        _binding ?: return
        binding.fuseState.text = FuseScreenModel.render(
            fusePlugin.lastOutcome, fusePlugin.lastAPSResult, dateUtil.now(),
            FuseScreenModel.MarkerInfo(
                armedTs = fusePlugin.mealMarkerArmedTs(),
                tier = fusePlugin.mealMarkerTier(),
                windowMin = app.aaps.fuse.core.controller.OnsetChannel.MARKER_WINDOW_MIN,
                envelopeU = fusePlugin.mealMarkerEnvelopeU(),
            ),
            // Eigene Groesse neben Health: der Ledger kann die Abgabe ganz
            // zumachen, waehrend der Beobachter tadellos READY meldet.
            fusePlugin.ledgerInfo(),
        )
        val armed = fusePlugin.mealMarkerActive(dateUtil.now())
        val tier = fusePlugin.mealMarkerTier()
        binding.fuseMealMarkerS.text = if (armed && tier == 0) "◉ S AKTIV" else getString(R.string.fuse_marker_s)
        binding.fuseMealMarker.text = if (armed && tier == 1) "◉ M AKTIV" else getString(R.string.fuse_marker_m)
        binding.fuseMealMarkerL.text = if (armed && tier == 2) "◉ L AKTIV" else getString(R.string.fuse_marker_l)
    }
}
