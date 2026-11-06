package app.aaps.fuse.plugin

import app.aaps.core.data.model.CA
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.fuse.core.controller.ECarbs
import app.aaps.fuse.core.controller.MealBalance
import kotlinx.coroutines.runBlocking
import kotlin.math.min

/**
 * DIE ANGEKUENDIGTEN eCARBS EINER RECHNER-MAHLZEIT (Entwurf B, Toni 05.11.2026) - fuer die gemeinsame Bilanz
 * (`MealBalance` in :fuse:core).
 *
 * Die Knoepfe Kuchen und Pizza des Bolusrechners tragen einen Teil der KH als eCarbs ein: ein KH-Eintrag mit Dauer, ohne
 * Insulin. Die Meldung des Rechners an FUSE (`FuseOverviewSource.WizardBolus`) kennt sie nicht - sie stehen nur in der
 * KH-Tabelle. Hier, und NUR hier, liest FUSE diese Tabelle: die Eintraege MIT Dauer, zerlegt wie AAPS ([ECarbs.parts]).
 * Das ist KEIN COB - keine Aufnahme aus der deviation, kein Abbau, nichts in der Bahn. Die Aufnahme ordnet die Bilanz
 * selbst zu, aus der beobachteten Mahlzeitwirkung. Der Waechter `NoCobDependencyTest` erlaubt genau diese eine Lesung.
 *
 * Zur Mahlzeit gehoert ein gueltiger Eintrag mit Dauer, dessen Beginn hoechstens [VOR_MS] vor und hoechstens [NACH_MS]
 * nach dem Marker liegt - und jeder Eintrag, den die Episode von einem frueheren Marker uebernommen hat (Codex 05.11.:
 * laufende eCarbs behalten ihre Herkunft). Ein geloeschter oder geaenderter Eintrag zaehlt ab dem naechsten Zyklus so, wie
 * er dann steht.
 */
internal class ECarbsQuelle(private val persistenceLayer: PersistenceLayer) {

    /** Ein Eintrag der Episode: Kennung und Herkunft, die Menge [g] und die Teile wie AAPS. */
    data class Eintrag(val ref: MealBalance.ECarbsRef, val grams: Double, val teile: List<ECarbs.Part>)

    /**
     * Die eCarbs der Episode am Marker [markerTs]: die von frueheren Markern uebernommenen [uebernommen] und die eigenen;
     * `null` = nicht lesbar - dann rechnet die Bilanz in diesem Zyklus nicht.
     */
    fun eintraege(markerTs: Long, uebernommen: List<MealBalance.ECarbsRef>): List<Eintrag>? {
        val ab = min(markerTs, uebernommen.minOfOrNull { it.originAuthId } ?: markerTs) - VOR_MS
        return runCatching { runBlocking { persistenceLayer.getCarbsFromTimeNotExpanded(ab, true) } }
            .getOrNull()
            ?.let { zuordnen(it, markerTs, uebernommen) }
    }

    companion object {

        /** So weit darf der Beginn vor dem Marker liegen [ms] - so weit wird der Marker hoechstens zurueckgelegt. */
        const val VOR_MS = HclMahlzeiten.RUECKDATIERUNG_MS

        /** So weit darf der Beginn nach dem Marker liegen [ms] - weiter reicht die Verzoegerung im Rechner nicht. */
        const val NACH_MS = 6 * 3600_000L

        /**
         * DIE ZUORDNUNG: ein uebernommener Eintrag behaelt seine Herkunft, wo immer er liegt - auch im Fenster des neuen
         * Markers zaehlt er nicht ein zweites Mal als dessen eigener. Ein anderer gehoert zum Marker [markerTs], wenn sein
         * Beginn im Fenster liegt. Nur gueltige Eintraege mit Dauer; uebernommen wird nur mit einer Kennung aus der
         * Datenbank (> 0).
         */
        fun zuordnen(eintraege: List<CA>, markerTs: Long, uebernommen: List<MealBalance.ECarbsRef>): List<Eintrag> {
            val herkunft = uebernommen.filter { it.entryId > 0L }.associate { it.entryId to it.originAuthId }
            return eintraege.filter { it.isValid && it.duration > 0L }.mapNotNull { e ->
                val origin = herkunft[e.id]
                    ?: markerTs.takeIf { e.timestamp >= markerTs - VOR_MS && e.timestamp <= markerTs + NACH_MS }
                    ?: return@mapNotNull null
                Eintrag(MealBalance.ECarbsRef(e.id, origin), e.amount, ECarbs.parts(e.amount, e.timestamp, (e.duration / 60_000L).toInt()))
            }
        }
    }
}
