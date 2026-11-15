package app.aaps.fuse.plugin

import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.fuse.core.controller.ExpectationAccount
import app.aaps.fuse.core.controller.FuseController
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DER FUSE-CHIP AUF DEM HAUPTBILDSCHIRM (Toni 15.11.2026) - die Werte EINES Zyklus fuer seine neun Kacheln, und die
 * gespeicherte Auswahl der angehefteten Kacheln.
 *
 * Reine Anzeige: kein Zyklus liest etwas hiervon, Dosierung, Trail und Politik-Hash bleiben unberuehrt. Nichts wird ein
 * zweites Mal gerechnet - jeder Wert steht schon im Ausgang des Zyklus oder in seinem Ringpunkt, dem Punkt, den die
 * Graphen zeichnen. Deshalb zeigen Chip und Graph fuer denselben Zyklus dasselbe.
 */
internal object FuseChipStand {

    private const val EPS_U = 1e-9

    /**
     * Namen im `bindingLimit`, die die Menge nur FORMEN und nichts zurueckhalten: der Anteil am Bedarf, der Bedarf des
     * Kontos, die Portion des Kontos, die Autorisierung durch den Marker und "kein Bedarf" (`insulinReq<=0`, hier ohne
     * den Teil ab `=`). Bindet eine davon, zeigt die Kachel "Grenze" frei.
     */
    private val NUR_FORMEND = setOf("smbRatio", "expectationDemand", "expectationAccount", "markerAuth", "insulinReq<")

    /** Der Chip-Stand eines Zyklus - aus seinem endgueltigen Ausgang [o] und seinem Ringpunkt [punkt]. */
    fun aus(
        o: FuseCycleRunner.Outcome,
        punkt: FuseOverviewSource.Point,
        /** Der juengste SMB im Ring; `null` = keiner. */
        letzterSmb: FuseOverviewSource.ChipSmb?,
        /**
         * Das gespeicherte Konto NACH diesem Zyklus (Ledger), fuer den Rest - s. [mahlzeit]. `null` = keins gespeichert
         * oder nicht bekannt: dann gilt der Stand, mit dem der Zyklus gerechnet hat.
         */
        kontoDanach: ExpectationAccount.State? = null,
    ): FuseOverviewSource.Chip {
        val st = o.state
        val smb = punkt.smbU?.takeIf { it.isFinite() && it > 0.0 }?.let {
            FuseOverviewSource.ChipSmb(punkt.timestamp, it, punkt.smbSource ?: FuseOverviewSource.SmbSource.OTHER)
        }
        // Der Bedarf ist der des Reglers. Stoppt der Normalweg am Guard oder am Schwanz, rechnet der Regler keinen - die
        // Portion kommt dann ueber die Marker-Freigabe aus dem Konto, und die Kachel stuende genau in der Phase leer, in
        // der das Konto liefert. Dann zeigt sie den Bedarf des Kontos (Toni 15.11.2026: "Bedarf vom Konto zeigen").
        val kontoBedarf = if (punkt.insulinReqU == null) kontoBedarfU(o) else null
        return FuseOverviewSource.Chip(
            // Wann FUSE gerechnet hat - nicht der Zeitstempel des Messwerts: daran wird der Chip grau.
            timestamp = o.computeTs,
            cycleState = punkt.cycleState ?: FuseOverviewSource.CycleState.UNKNOWN,
            driveMgdlPerMin = punkt.driveMgdlPerMin,
            // 0 heisst beim Konto "es erwartet nichts" ("Nur Marker", vor dem Budget) - dann steht nichts da.
            expectedMealDriveMgdlPerMin = punkt.expectedMealDriveMgdlPerMin?.takeIf { it > 0.0 },
            insulinReqU = punkt.insulinReqU ?: kontoBedarf,
            insulinReqFromAccount = kontoBedarf != null,
            smbRatio = st?.effectiveSmbRatio?.takeIf { it.isFinite() },
            // Dieselbe Dreiteilung wie `FuseController.State.effectiveSmbRatio`: nach einem Tief der Korrekturanteil,
            // ausserhalb des Mahlzeitfensters ebenso, sonst die Rampe.
            ratioMode = st?.let {
                when {
                    it.reboundWindow -> FuseOverviewSource.RatioMode.REBOUND
                    !it.mealWindow   -> FuseOverviewSource.RatioMode.CORRECTION
                    else             -> FuseOverviewSource.RatioMode.RAMP
                }
            },
            rampPercent = st?.let { rampenProzent(it.rSignedMgdlPerMin, it.riseRampLowRPerMin, it.riseRampHighRPerMin) },
            smb = smb,
            // Nur wenn weniger hinausging als die Endanforderung vor den letzten Pruefungen - sonst gibt es nichts zu melden.
            smbWantedU = o.smbRequestedU?.takeIf { it.isFinite() && it > (smb?.units ?: 0.0) + GEKUERZT_EPS_U },
            // Ein SMB dieses Zyklus ist der juengste - auch wenn sein Ringpunkt nicht angehaengt wurde (gleicher Zeitstempel).
            lastSmb = smb ?: letzterSmb,
            limit = grenze(o, smb?.units),
            // Ueber der Grenze ist kein Platz mehr - eine negative Zahl sagte dasselbe wie 0.
            roomU = o.exposureGateHeadroomU?.takeIf { it.isFinite() }?.coerceAtLeast(0.0),
            channel = punkt.channel,
            guardMarginMgdl = punkt.guardMarginMgdl,
            guardWithMealMarginMgdl = punkt.guardWithMealMarginMgdl,
            tailMarginMgdl = punkt.tailMarginMgdl,
            tailWithMealMarginMgdl = punkt.tailWithMealMarginMgdl,
            meal = mahlzeit(o, punkt.accountStatus, kontoDanach),
        )
    }

    /**
     * DER BEDARF DES KONTOS in diesem Zyklus [U] - dieselbe Groesse, die der Regler in einem Kontozyklus ohne Stopp als
     * Bedarf ausweist (`FuseController.decide`, `kontoReq`): die lieferbare Kontomenge ueber dieselben beiden
     * Kernfunktionen wie dort und im Trail (`deliverableU`), hoechstens die Grenze der gemeinsamen Bilanz der
     * Rechner-Mahlzeit. `null` ohne geltendes Konto - im Rueckfall gibt es keinen Kontobedarf.
     */
    fun kontoBedarfU(o: FuseCycleRunner.Outcome): Double? {
        val eval = o.erwartungskonto?.takeIf { it.gilt }?.zyklus?.eval ?: return null
        val roh = FuseController.accountDemandU(ExpectationAccount.deliverableU(eval)) ?: return null
        val grenze = o.mealBalance?.smbCapU?.takeIf { it.isFinite() }
        return if (grenze == null) roh else min(roh, grenze)
    }

    /**
     * Wo r auf der Anstiegsrampe steht, 0..100 Prozent - dieselbe Rechnung wie der Anteil selbst
     * (`FuseController.rampSmbRatio`), nur als Anzeige. `null` ohne brauchbares r oder ohne gueltige Rampe: dort faellt
     * der Regler auf den Korrekturanteil, und eine Prozentzahl waere erfunden.
     */
    fun rampenProzent(rMgdlPerMin: Double?, lowRPerMin: Double, highRPerMin: Double): Int? {
        val r = rMgdlPerMin?.takeIf { it.isFinite() } ?: return null
        if (!lowRPerMin.isFinite() || !highRPerMin.isFinite() || highRPerMin <= lowRPerMin) return null
        return (((r - lowRPerMin) / (highRPerMin - lowRPerMin)).coerceIn(0.0, 1.0) * 100.0).roundToInt()
    }

    /**
     * WAS HAELT DEN SMB ZURUECK? Der Name, wie FUSE ihn im `bindingLimit` schreibt; `null` = nichts ("frei").
     *
     * "Frei" ist die Aussage, die nie falsch sein darf - sie hiesse "nichts haelt zurueck". Deshalb in dieser Reihenfolge:
     *  1. Gewollt war eine Menge, aber nichts ging hinaus: der letzte Riegel (`smbFinalBlock`).
     *  2. Ein spaeteres Tor hat die Menge nach der Grundentscheidung gekuerzt oder genullt: sein Name steht als Zusatz
     *     hinter `|` ([spaeteresTor]: Endpruefung, Abwaertsrisiko, Korrekturriegel) - der letzte zaehlt. Andere Zusaetze
     *     sind Vermerke (`subStep`, das Urteil des Low-Tors hinter `guardFloor`) und halten nichts zurueck.
     *  3. Der typisierte SMB-Status sagt STOP: die bindende Grenze. Nennt sie nur etwas Formendes (ein bedarfsloser
     *     Zyklus an einer vollen Mengengrenze), der typisierte Stop-Grund.
     *  4. Frei oder ohne Bedarf: die bindende Grenze, wenn sie mehr ist als der Anteil am Bedarf ([NUR_FORMEND]) - eine
     *     Mengengrenze, der Schwanz, ein Totband.
     *  5. Nicht auswertbar (Abbruch): die bindende Grenze, sonst der Block.
     *  6. Netz: kaeme "frei" heraus, obwohl weniger hinausging als gewollt, steht die bindende Grenze da.
     *
     * @param gegebenU der SMB, den AAPS in diesem Zyklus bekam; `null` = keiner.
     */
    fun grenze(o: FuseCycleRunner.Outcome, gegebenU: Double? = null): String? {
        // "NONE" ist bei `TbrPolicy.SmbBlockCause` kein Riegel, sondern "keiner benannt" - dann zaehlen die Regeln darunter.
        o.smbFinalBlock?.takeIf { it.isNotBlank() && it != "NONE" }?.let { return it }
        val roh = o.decision.bindingLimit.trim()
        val teile = roh.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        teile.drop(1).lastOrNull(::spaeteresTor)?.let { return it }
        val erste = teile.firstOrNull().orEmpty()
        val formend = erste.isEmpty() || kern(erste) in NUR_FORMEND
        val name = when (o.smbState) {
            "STOP"              -> if (formend) stopName(o.smbStopReason) else erste
            "FREE", "NO_DEMAND" -> erste.takeUnless { formend }
            else                -> erste.ifEmpty { o.decision.block.name }
        }
        if (name != null) return name
        val gewollt = o.smbRequestedU?.takeIf { it.isFinite() } ?: 0.0
        val gegeben = gegebenU?.takeIf { it.isFinite() } ?: 0.0
        return if (gewollt > gegeben + GEKUERZT_EPS_U) roh.ifEmpty { o.decision.block.name } else null
    }

    /** Ab diesem Abstand [U] gilt ein SMB als gekuerzt - ein Fuenftel des kleinsten Pumpenschritts, ueber jeder Rundung. */
    const val GEKUERZT_EPS_U = 0.01

    /**
     * Ist dieser Zusatz im `bindingLimit` ein Tor, das die Menge NACH der Grundentscheidung gekuerzt oder genullt hat?
     * Die Liste folgt den Stellen, die im Runner einen Zusatz anhaengen: die Endpruefung (`finalShortened:`,
     * `primeVeto:`), der Korrekturriegel (`REVERSAL_...`, `REARM_...`), das Abwaertsrisiko am Konto und der Aufschub.
     * Ein neuer Zusatz, der hier fehlt, faellt ins Netz von [grenze] - die Kachel sagt dann nicht "frei".
     */
    private fun spaeteresTor(zusatz: String): Boolean =
        zusatz.startsWith("finalShortened:") || zusatz.startsWith("primeVeto:") ||
            zusatz.startsWith("REVERSAL_") || zusatz.startsWith("REARM_") ||
            zusatz == "expectationDescentRisk" || zusatz == "deferredPrime"

    /** Der Kern eines Namens: ohne Wert hinter `=` und ohne den Vorsatz des Liveness-Kanals. */
    private fun kern(name: String): String = name.substringBefore('=').trim().removePrefix("liveness:")

    /** Ein typisierter Stop-Grund (`SmbStatus.StopReason`) in den Namen, den dieselbe Grenze im `bindingLimit` traegt. */
    private fun stopName(grund: String?): String = when (grund) {
        "IOB_TH"   -> "iobThHeadroom"
        "MAX_IOB"  -> "maxIobHeadroom"
        "EXPOSURE" -> "correctionExposureLimit"
        "GUARD"    -> "guardFloor"
        "TAIL"     -> "tailHeadroom"
        "HEALTH"   -> "drive not estimable"
        "SAFETY"   -> "SAFETY_HOLD"
        "DESCENT"  -> "measuredDescentRisk"
        "LEDGER"   -> "ledgerHold"
        "PUMP"     -> "PUMP_GATE"
        "DEFERRED" -> "deferredPrime"
        else       -> grund ?: "STOP"
    }

    /**
     * Die Mahlzeit dieses Zyklus: das Erwartungskonto (Rest, Fensterende, gebuchter Skalierer) und bei einer Mahlzeit aus
     * dem Bolusrechner die Bilanz G. `null` ohne Konto, ohne Rueckfall und ohne Bilanz.
     *
     * DER REST IST DER NACH DER BUCHUNG DIESES ZYKLUS. Der Ausgang traegt den Kontostand, mit dem der Zyklus gerechnet hat -
     * VOR seiner eigenen Buchung (so steht er auch im Trail, mit der Buchung daneben). Auf der Kachel stuende ein SMB
     * aus dem Konto dann neben einem Rest, von dem er noch nicht abgezogen ist; erst der naechste Zyklus zeigte ihn. Der
     * gespeicherte Stand derselben Autorisierung ([kontoDanach]) traegt die Buchung schon - gelesen, nicht gerechnet.
     * Gehoert er zu einer anderen Autorisierung (neuer Druck) oder fehlt er, gilt der Stand des Zyklus.
     */
    private fun mahlzeit(
        o: FuseCycleRunner.Outcome,
        status: FuseOverviewSource.AccountStatus?,
        kontoDanach: ExpectationAccount.State?,
    ): FuseOverviewSource.ChipMeal? {
        val bilanz = o.mealBalance?.takeIf { it.markerTs > 0L }
        if (status == null && bilanz == null) return null
        // Der Kontostand zaehlt nur, wenn das Konto in diesem Zyklus gilt - im Rueckfall gibt es keinen.
        val konto = o.erwartungskonto?.takeIf { it.gilt }?.zyklus?.state
        val danach = kontoDanach?.takeIf { konto != null && it.authId == konto.authId }
        val rechner = bilanz != null || o.hcl != null
        val mitBudget = konto != null && konto.a0U > EPS_U
        return FuseOverviewSource.ChipMeal(
            status = status,
            restU = (danach ?: konto)?.restU?.takeIf { it.isFinite() },
            withoutBudget = konto != null && !mitBudget,
            windowEndTs = konto?.windowEndTs ?: 0L,
            // Bei einer Rechner-Mahlzeit folgt der Skalierer aus den Kohlenhydraten - er ist keine Einstellung.
            scale = konto?.phi0?.takeIf { mitBudget && !rechner && it.isFinite() && it > 0.0 },
            calculator = rechner,
            balanceU = bilanz?.balanceU?.takeIf { it.isFinite() },
        )
    }

    // ---- Die gespeicherte Auswahl ------------------------------------------------------------------------------

    /**
     * Die angehefteten Kacheln aus dem gespeicherten Text ([FuseStringNonKey.ChipPins]): Namen mit Komma, in der
     * Reihenfolge des Anheftens. Unbekannte Namen (aus einem anderen Stand) und Doppelte fallen weg, mehr als
     * [FuseOverviewSource.ChipLayout.MAX_PINS] ebenso. Ein leerer Text heisst "nichts angeheftet", nicht "Vorgabe".
     */
    fun pinsAusText(text: String): List<FuseOverviewSource.ChipTile> =
        text.split(',')
            .mapNotNull { name -> FuseOverviewSource.ChipTile.entries.firstOrNull { it.name == name.trim() } }
            .distinct()
            .take(FuseOverviewSource.ChipLayout.MAX_PINS)

    /** Der Text zu einer Auswahl - dieselben Regeln wie beim Lesen, damit nie mehr gespeichert als gezeigt wird. */
    fun pinsAlsText(pins: List<FuseOverviewSource.ChipTile>): String =
        pins.distinct().take(FuseOverviewSource.ChipLayout.MAX_PINS).joinToString(",") { it.name }
}
