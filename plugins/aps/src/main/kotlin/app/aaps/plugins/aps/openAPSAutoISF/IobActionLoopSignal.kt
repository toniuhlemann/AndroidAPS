package app.aaps.plugins.aps.openAPSAutoISF

import android.content.Context
import android.content.Intent

/**
 * Loop-Signal an den Companion-Viewer (Toni 21.07.2026): direkt nach dem Schreiben von
 * iobaction_state.json geht ein expliziter Broadcast an das Viewer-Paket — der Widget-
 * Refresh muss dann nicht mehr auf den naechsten 60s-Poll warten, sondern liest Sekunden
 * nach jedem determine. WRITE-ONLY/dosier-neutral (eigenes runCatching beim Aufrufer);
 * bewusst KEIN Rueckkanal und KEINE Daten im Intent ausser dem Zyklus-Zeitstempel — Daten
 * fliessen weiterhin ausschliesslich ueber die Export-Dateien (Kanal-Doktrin: Signal ja,
 * Datenleitung nein). Empfaenger-seitig ist der Broadcast nur ein "lies jetzt"-Weckruf;
 * ein gefaelschter Broadcast Dritter kann hoechstens einen zusaetzlichen Datei-Read
 * ausloesen (Rate-Limit im Viewer-Receiver).
 */
object IobActionLoopSignal {

    private const val VIEWER_PACKAGE = "de.toniuhlemann.iobactionnativeviewer"
    const val ACTION_LOOP_CYCLE_WRITTEN = "de.toniuhlemann.iobactionnativeviewer.LOOP_CYCLE_WRITTEN"

    fun send(context: Context, cycleTs: Long) {
        context.sendBroadcast(Intent(ACTION_LOOP_CYCLE_WRITTEN).apply {
            setPackage(VIEWER_PACKAGE)
            putExtra("cycleTs", cycleTs)
        })
    }
}
