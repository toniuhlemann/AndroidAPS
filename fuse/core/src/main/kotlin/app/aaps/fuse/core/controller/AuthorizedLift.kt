package app.aaps.fuse.core.controller

import kotlin.math.floor
import kotlin.math.min

/**
 * DER GEMEINSAME LIFT EINER AUTORISIERTEN MENGE (Toni 18.08.).
 *
 * Prime (Phase A) und das Mahlzeitenfundament (Phase B) heben beide eine
 * Basisentscheidung auf einen autorisierten BODEN an. Was sie dabei tun, ist
 * bis auf drei Groessen identisch - und diese drei sind hier Parameter statt
 * zweier Implementierungen:
 *
 *   GEMEINSAM        Markerpolitik, `max(base, floor)`, maxSMB, iobTH,
 *                    maxIOB, Transporthaftung, Pumpenraster
 *   PRIME-SPEZIFISCH Prime-Restbudget, Onset-Huelle
 *   FUNDAMENT        dueU, Phase-B-Restbudget, Zeitfenster
 *
 * WARUM NICHT `PrimeRelease.lift` FUER BEIDE. Der Fundament-Aufruf muesste
 * dort einen Prime-`Plan` erfinden, und die Onset-Huelle wuerde Phase B
 * mitdeckeln - beides falsch. Toni ausdruecklich: "Die Onset-Huelle darf
 * nicht versehentlich Phase B deckeln. Normale Onset-/Evidenzabgaben werden
 * bereits ueber die max-Semantik und die tatsaechlich gelieferte
 * Gesamtmenge auf das Fundament-Soll angerechnet."
 *
 * WO DIESE STUFE IM ZYKLUS SITZT:
 *
 *     Basisentscheidung
 *  -> autorisierten Boden bis zum jeweiligen Soll anheben   <- HIER
 *  -> gemeinsame harte Mengenkappen                         <- HIER
 *  -> Modell-Veto
 *  -> autorisierten Boden ggf. wiederherstellen             (finalVerify)
 *  -> Ledger-/Publikations-/Pumpengate GENAU EINMAL         (Plugin)
 *
 * Die letzten beiden Stufen liegen bewusst NICHT hier: sie gehoeren dem
 * Aufrufer, und sie laufen ueber die EINE finale Menge - nicht zweimal ueber
 * zwei Teilmengen. Zwei Gate-Laeufe waeren ein zweiter Kanal, und der duerfte
 * doppelt so viel wie erlaubt.
 */
object AuthorizedLift {

    /**
     * WOHER die autorisierte Menge stammt.
     *
     * TYPISIERT UND NICHT AUS DEM GRUNDTEXT GERATEN, aus demselben Grund wie
     * bei [FuseController.Decision.markerAuthorizedU]: Texte sind fuer
     * Menschen, an ihnen darf keine Insulinabgabe haengen. Ein Export, der
     * beide Quellen als "primeRelease" fuehrt, macht im Replay nicht mehr
     * unterscheidbar, welche Phase geliefert hat.
     */
    enum class Source {
        /** Die Huelle des Markerdrucks im Prime-Fenster. */
        PRIME,

        /** Die nachlaufende Mindestversorgung des Mahlzeitenfundaments. */
        FOUNDATION,

        /**
         * Der PHASE-A-SOFORTANTEIL (iLet-Prinzip, Bauauftrag Toni 24.08.):
         * die beim Markerdruck gepinnte Sofort-Menge
         * `phaseABudgetU x UpfrontShare`. Die EINZIGE Quelle, deren
         * Einzeldosis nicht von maxSMB zerteilt wird - sonst waere
         * UpfrontShare 1,0 keine Sofortdosis, sondern dieselbe Rampe in
         * groesseren Schritten. Budget, iobTH, maxIOB, Transporthaftung
         * und Pumpenraster bleiben unveraendert hart.
         */
        MEAL_UPFRONT,
    }

    /**
     * BETRAG UND QUELLE ALS EINE EINHEIT (Toni 18.08.).
     *
     * DER FEHLER, DEN DIESER TYP VERHINDERT. Vorher standen die beiden als
     * getrennte Felder in der Entscheidung: `markerAuthorizedU` und
     * `authorizedSource`. Der Lift setzte die Quelle AUCH dann, wenn der
     * Betrag 0 war - es konnte also eine "Quelle FOUNDATION ohne autorisierte
     * Menge" entstehen. Eine Herkunft ohne Menge ist keine Aussage, sondern
     * ein Widerspruch, und ein Leser, der auf die Quelle prueft statt auf den
     * Betrag, haette daraus eine Autorisierung gelesen, die es nicht gab.
     *
     * Zwei Felder, die nur gemeinsam Sinn ergeben, gehoeren in einen Typ.
     * `null` heisst: KEINE Autorisierung - eindeutig, ohne zweite Lesart.
     *
     * Der Konstruktor ist privat: [of] ist die einzige Quelle, und sie gibt
     * bei einem unbrauchbaren Betrag `null` zurueck statt eines halben
     * Grants.
     */
    class AuthorizedGrant private constructor(
        val amountU: Double,
        val source: Source,
    ) {

        override fun equals(other: Any?): Boolean =
            other is AuthorizedGrant && other.amountU == amountU && other.source == source

        override fun hashCode(): Int = amountU.hashCode() * 31 + source.hashCode()

        override fun toString(): String = "AuthorizedGrant($amountU, $source)"

        companion object {

            /**
             * FAIL-CLOSED: nur ein endlicher, POSITIVER Betrag ergibt einen
             * Grant. 0 oder NaN ergeben `null` - "keine Autorisierung", nicht
             * "eine ueber nichts".
             */
            fun of(amountU: Double, source: Source): AuthorizedGrant? =
                if (amountU.isFinite() && amountU > 0.0) AuthorizedGrant(amountU, source) else null
        }
    }

    /** Was im `bindingLimit` steht, wenn dieser Lift gebunden hat. */
    fun bindingLimitOf(source: Source): String = when (source) {
        Source.PRIME        -> "primeRelease"
        Source.FOUNDATION   -> "mealFoundation"
        Source.MEAL_UPFRONT -> "mealUpfront"
    }

    fun stageOf(source: Source): String = when (source) {
        Source.PRIME        -> FuseController.STAGE_PRIME
        Source.FOUNDATION   -> FuseController.STAGE_FOUNDATION
        Source.MEAL_UPFRONT -> FuseController.STAGE_UPFRONT
    }

    /**
     * Hebt [base] auf den autorisierten Boden [floorU] an - hoechstens.
     *
     * @param floorU das Soll dieser Phase. Prime: die Freigabe aus der
     *   Huelle. Fundament: `dueU`. NIE mehr als das - der Lift ist ein Boden,
     *   kein Aufschlag.
     * @param remainingU das Restbudget dieser Phase. Prime: die unverbrauchte
     *   Huelle. Fundament: das offene Phase-B-Budget im Fenster.
     * @param authorized hat ein bewusster Markerdruck Insulin autorisiert?
     *   Der Aufrufer hat geprueft, dass die Einstellung an ist UND ein Marker
     *   laeuft - mehr nicht.
     * @param tailHeadroomU die Schwanzkappe. Sie bindet den AUTORISIERTEN
     *   Anteil nicht (s. [MarkerAuthorization]: derselbe Grund, aus dem
     *   `Block.TAIL` hebbar ist - beide Gestalten sind dieselbe
     *   Haftungsprognose ueber 120 Minuten).
     * @param extraCapU eine phasenspezifische Zusatzkappe. Prime reicht hier
     *   die Onset-Huelle herein; das Fundament reicht `null` - seine
     *   Onset-Abgaben sind ueber die max-Semantik bereits angerechnet.
     */
    @Suppress("LongParameterList")
    fun lift(
        base: FuseController.Decision,
        source: Source,
        floorU: Double,
        remainingU: Double,
        state: FuseController.State,
        authorized: Boolean,
        tailHeadroomU: Double? = null,
        extraCapU: Double? = null,
        transportCommitmentU: Double = 0.0,
        tickEps: Double,
    ): FuseController.Decision {
        // ---- Eingaben pruefen, BEVOR gerechnet wird (Toni 18.08.) --------
        //
        // NICHT BERECHENBAR IST NICHT NULL - derselbe Vertrag wie in
        // [MealFoundation.contribute]. Ein NaN im Restbudget oder in einer
        // Kappe wuerde durch `min` durchschlagen und am Ende eine Menge
        // ergeben, die auf einer Zahl beruht, die es nicht gibt. Ein
        // negatives Restbudget kann kein Schreiber dieses Codes erzeugen -
        // es waere Korruption.
        //
        // Jeder dieser Faelle gibt die Basisentscheidung UNVERAENDERT und
        // OHNE Grant zurueck: der bestehende Pfad laeuft weiter wie ohne
        // Lift.
        if (!floorU.isFinite() || !remainingU.isFinite() || remainingU < 0.0) return base
        if (tailHeadroomU != null && !tailHeadroomU.isFinite()) return base
        if (extraCapU != null && !extraCapU.isFinite()) return base
        if (!transportCommitmentU.isFinite() || transportCommitmentU < 0.0) return base
        // capIobU ist die EINZIGE dieser Groessen, die der State-Konstruktor
        // NICHT prueft. Die uebrigen wirft er selbst ab (isFinite und Bereich)
        // - sie stehen hier als Verteidigung in der Tiefe, nicht weil dieser
        // Zustand erreichbar waere.
        if (!state.pumpIncrementU.isFinite() || state.pumpIncrementU <= 0.0) return base
        if (!state.maxSmbU.isFinite() || !state.maxIobU.isFinite() ||
            !state.iobThU.isFinite() || !state.capIobU.isFinite()
        ) return base
        if (!tickEps.isFinite() || tickEps < 0.0) return base

        // Kein Soll, kein Lift. Das ist keine Sicherheitspruefung, sondern
        // die Feststellung, dass es nichts zu heben gibt.
        if (floorU <= 0.0) return base

        // DIE POLITIK - eine Stelle fuer beide Phasen.
        if (!MarkerAuthorization.lifts(base.block, authorized)) return base

        // ---- Die gemeinsamen harten Mengenkappen -------------------------
        //
        // Sie gelten UNVERAENDERT auch fuer eine autorisierte Menge: maxSMB
        // begrenzt die Einzeldosis, iobTH und maxIOB den Bestand, die
        // Transporthaftung das bereits unterwegs Befindliche. Keine
        // Autorisierung hebt sie - sie sagen nichts ueber eine Prognose,
        // sondern ueber eine Obergrenze.
        //
        // EINE AUSNAHME, quellengebunden (Bauauftrag Toni 24.08., gleiches
        // Muster wie die `!authorized`-Schwanzkappe unten): der
        // Phase-A-SOFORTANTEIL wird nicht von maxSMB in Einzelzyklen
        // zerteilt - seine absolute Obergrenze ist der gepinnte
        // Sofortbetrag selbst (floorU), das Restbudget (remainingU) und
        // die Bestandsgrenzen. Das ist eine gezielte Markerautorisierung,
        // KEINE Erhoehung von maxSMB: Normal-, Liveness-, Prime- und
        // Fundamentpfad behalten die Einzeldosisgrenze unveraendert.
        val singleDoseCapU =
            if (source == Source.MEAL_UPFRONT) remainingU
            else min(state.maxSmbU, remainingU)
        // A2: dieselbe gemeinsame Expositionssicht wie in der
        // Kandidatensuche und im SubStep - identische Ausdrucksreihenfolge,
        // bitgleiche Werte (s. ExposureView-KDoc).
        val exposure = ExposureView.of(
            iobThU = state.iobThU, maxIobU = state.maxIobU,
            capIobU = state.capIobU, transportU = transportCommitmentU,
        )
        var caps = min(
            singleDoseCapU,
            min(exposure.maxIobHeadroomU, exposure.iobThHeadroomU),
        )
        // Die Schwanzkappe bindet nur OHNE Autorisierung.
        if (!authorized) tailHeadroomU?.let { caps = min(caps, it) }
        // Die phasenspezifische Zusatzkappe (Prime: Onset-Huelle).
        extraCapU?.let { caps = min(caps, it) }

        val stepped = floor(min(floorU, caps) / state.pumpIncrementU + tickEps) * state.pumpIncrementU
        // Der Grant entsteht NUR bei einem tragfaehigen Betrag - `of` gibt
        // sonst null. Damit kann keine Quelle ohne Menge existieren.
        //
        // UND ER SCHRUMPFT NIE EINEN BESTEHENDEN (v28-Baubefund): laeuft der
        // Prime-Lift auf einer Basis, die der Sofort-Lift bereits auf 3,0 U
        // gehoben hat, ist sein eigener kleiner Boden (0,25/Zyklus) kein
        // Gewinn - aber der alte Code kopierte trotzdem SEINEN Grant in die
        // Entscheidung. Nach einem Modell-Veto stellte MarkerFloor dann nur
        // noch die 0,25 wieder her, und die Sofortdosis war still zerhackt.
        // Der Boden der Entscheidung ist der GROESSTE autorisierte Betrag.
        val eigener =
            if (authorized && stepped >= state.pumpIncrementU) AuthorizedGrant.of(stepped, source)
            else null
        val grant = when {
            eigener == null -> base.grant
            base.grant == null -> eigener
            base.grant!!.amountU >= eigener.amountU -> base.grant
            else -> eigener
        }

        // KEIN GEWINN: entweder reicht es nicht fuer einen Pumpenschritt, oder
        // die Basis liegt ohnehin schon hoeher. Der zweite Fall ist die
        // Mindestversorgung in Reinform - das Fundament legt NICHTS drauf,
        // wenn der normale Pfad schon liefert.
        if (stepped < state.pumpIncrementU || stepped <= base.smbU)
            return if (grant !== base.grant) base.copy(grant = grant) else base

        return base.copy(
            smbU = stepped,
            block = FuseController.Block.NONE,
            bindingLimit = bindingLimitOf(source),
            caps = emptyList(),
            capsStage = stageOf(source),
            grant = grant,
        )
    }
}
