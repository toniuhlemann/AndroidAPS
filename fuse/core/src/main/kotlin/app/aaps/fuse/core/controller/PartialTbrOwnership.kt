package app.aaps.fuse.core.controller

import kotlin.math.abs
import kotlin.math.min

/**
 * DER LEBENSZYKLUS DER EIGENEN TEIL-TBR.
 *
 * ===================================================================
 * WARUM EIN EINZIGER DATENSATZ NICHT REICHT (Review-P0-1)
 * ===================================================================
 * Die Vorfassung fuehrte EINEN Datensatz mit Rate, Phase und einem
 * `everRunning`-Merker. Damit ging beim Ratenwechsel die BESTAETIGTE
 * alte Identitaet verloren:
 *
 *     0,85 laeuft bestaetigt   -> confirmed = 0,85
 *     Guard fordert 1,00       -> Datensatz = 1,00 REQUESTED
 *     Pumpe zeigt weiter 0,85  -> passt nicht zur neuen Kennung
 *     Latch-Ende               -> "autoritativ weg" -> Besitz GELOESCHT
 *                                 obwohl unsere 0,85 weiterlaeuft
 *     -> kein Abbruch, SMB wieder offen
 *
 * `everRunning` machte es zusaetzlich schlimmer: es liess den
 * Timeout-/Retry-Pfad einer FEHLGESCHLAGENEN Ratenaenderung ausfallen,
 * weil "war schon mal bestaetigt" die Bestaetigungsfrist uebersprang.
 * Und die blosse Erneuerung DERSELBEN Rate setzte `setAtTs` neu, blieb
 * aber RUNNING - meldete die Pumpe noch die alte Restlaufzeit, passte
 * gar nichts mehr und der Besitz verschwand.
 *
 * DIE TRENNUNG IST DIE LOESUNG: was NACHWEISLICH LAEUFT und was
 * ANGEFORDERT IST, sind zwei verschiedene Dinge und werden getrennt
 * gefuehrt ([State.confirmedRunning] / [State.pendingRequest]). Beide
 * sind restartfest; der Besitz endet erst, wenn KEINES von beiden mehr
 * auf einen autoritativen Snapshot passt.
 *
 * ===================================================================
 * WAS AAPS AUS EINEM KOMMANDO MACHT (Review-P0-2)
 * ===================================================================
 * `LoopPlugin.applyAPSRequest` behandelt eine Rate innerhalb EINES
 * Basalschritts um `pump.baseBasalRate` als **Abbruch**, nicht als
 * Setzen. Die Guard-Suche darf aber exakt Profilbasal liefern (im
 * Mehrnaechte-Rig war das die Mehrheit der Teilstufen-Zyklen). Ein
 * solcher Wunsch als "erwartete positive 30-min-TBR" gebucht, waere
 * eine Kennung fuer etwas, das nie laeuft - und der Besitz haenge
 * daran fest, bis die Frist ablaeuft.
 *
 * Gebucht wird deshalb die EFFEKTIVE Wirkung ([Wirkung]), und nur
 * eine, die auch wirklich zur Ausgabe zugelassen wurde: ein vom
 * Aktuationstor verworfener Wunsch ist [Wirkung.NO_REQUEST] und
 * aendert nichts.
 *
 * ===================================================================
 * DIE FEHLERRICHTUNGEN SIND NICHT GLEICH SCHLIMM
 * ===================================================================
 * Eine FREMDE Absenkung faelschlich beenden hiesse, ungefragt Insulin
 * zu erhoehen - das darf nie passieren, und deshalb wird bei unklarer
 * Sicht NIE abgebrochen. Eine EIGENE faelschlich halten heisst, laenger
 * weniger zu geben, bei gesperrtem SMB. Unschoen, aber sicher.
 */
object PartialTbrOwnership {

    /** Eine angeforderte oder laufende eigene TBR - Rate plus Uhr. */
    data class Identity(
        val rateUPerH: Double,
        /** Zeitpunkt der ANFORDERUNG, nicht der Bestaetigung. */
        val setAtTs: Long,
        val durationMin: Int,
    ) {

        val valid: Boolean
            get() = rateUPerH.isFinite() && rateUPerH > 0.0 && setAtTs > 0L && durationMin > 0
    }

    /** Der Abbruchzustand - Versuche und Backoff. */
    data class Ending(
        val sinceTs: Long,
        val attempts: Int = 0,
        val lastRequestTs: Long = 0L,
    )

    /**
     * DER PERSISTENTE ZUSTAND. Restartfest zu halten: ginge er beim
     * Neustart verloren, waere die eigene laufende Absenkung danach
     * "fremd" und bliebe bis zum Ablauf stehen, waehrend FUSE die
     * normale Freigabe meldet.
     */
    data class State(
        /** Die zuletzt AUTORITATIV BESTAETIGTE eigene TBR. */
        val confirmedRunning: Identity? = null,
        /** Die zuletzt zur Ausgabe ZUGELASSENE Anforderung, noch offen. */
        val pendingRequest: Identity? = null,
        /** Gesendete Setzkommandos fuer [pendingRequest]. */
        val pendingAttempts: Int = 0,
        val ending: Ending? = null,
    ) {

        val leer: Boolean get() = confirmedRunning == null && pendingRequest == null

        /**
         * Solange irgendetwas von uns laeuft oder angefordert ist, bleibt
         * der schnelle Kanal zu. Waehrend des Abbruchs erst recht: der
         * Abbruch HEBT die Rate aufs Profilbasal, und "anheben plus SMB"
         * darf denselben Zyklus nicht verlassen.
         */
        /**
         * ACHTUNG, EIGENE ZEILE FUER `ending`: ein Profil-Abbruch kann
         * einen Zustand hinterlassen, in dem NUR `ending` gesetzt ist -
         * dann ist [leer] true. Ohne den zweiten Term waere der SMB in
         * genau dem Zyklus offen, in dem ein UNBESTAETIGTER Abbruch
         * laeuft; wird die Pumpensicht dabei unbrauchbar, weiss niemand,
         * ob die Rate noch steht.
         */
        val smbBlocked: Boolean get() = !leer || ending != null
    }

    /** Die Sicht auf die Pumpe - typisiert, weil "nichts da" und "nichts
     *  gesehen" NICHT dasselbe sind. */
    sealed interface View {

        /** Belastbare Sicht. `current == null` heisst NACHGEWIESEN keine TBR. */
        data class Authoritative(val current: TbrPolicy.Current?) : View

        /** Snapshot fehlt, stammt aus einer Ersatzquelle oder ist unbrauchbar. */
        data object Unknown : View
    }

    /**
     * WAS AAPS AUS DEM KOMMANDO TATSAECHLICH MACHT.
     *
     * Nicht was FUSE gerne haette, sondern was `LoopPlugin` ausfuehrt.
     */
    enum class Wirkung {
        /** Eine echte abgesenkte TBR - die einzige, die Besitz begruendet. */
        SET_PARTIAL,

        /**
         * NACH DIESEM KOMMANDO LAEUFT KEINE EIGENE TEILRATE MEHR.
         *
         * Drei Faelle: der ausdrueckliche Abbruch (Rate 0, Dauer 0), eine
         * Rate innerhalb eines Basalschritts um das Profilbasal (dafuer
         * ruft `LoopPlugin` `cancelTempBasal`) und die Schutz-Null (Rate 0
         * ueber volle Dauer - sie ersetzt unsere Teilrate). Eine Kennung
         * dafuer waere eine Kennung fuer nichts.
         */
        CANCEL_TO_PROFILE,

        /**
         * EINE ECHTE ZERO-TBR (Rate 0 ueber positive Dauer).
         *
         * Bei AAPS ist das KEIN Abbruch, sondern eine gesetzte Null - sie
         * ersetzt eine laufende Teilrate, aber sie ist kein Abbruchversuch.
         * Beides zu vermischen hiesse: drei erfolglose Zero-Ersetzungen
         * verbrauchen den Deckel, und der spaeter noetige ECHTE Abbruch
         * kaeme nie mehr raus.
         */
        REPLACE_WITH_ZERO,

        /** Nichts ging raus: kein Wunsch, Aktuationstor zu, Pumpe belegt. */
        NO_REQUEST,
    }

    enum class Reason {
        NONE,
        WAITING_CONFIRM,
        CONFIRMED_RUNNING,
        VIEW_UNKNOWN_HELD,
        END_REQUESTED,
        END_RETRY,
        END_BACKOFF_WAIT,
        END_GIVEN_UP,
        CLEARED_CONFIRMED,
        CONFIRM_TIMEOUT,
        SET_SUPPRESSED_DUPLICATE,
        SET_LOWERED,
        SET_HELD_HIGHER,
        SET_RETRY,
        SET_GIVEN_UP,
    }

    /**
     * DER ZUSTAND FUER ANZEIGE UND TRAIL.
     *
     * Der Viewer muss IST und ABSICHT trennen koennen: links die
     * tatsaechlich laufende Pumpenrate, rechts was FUSE will. Ohne diese
     * Trennung sieht eine bloss ANGEFORDERTE Teilrate aus wie eine
     * laufende.
     */
    enum class Anzeige {
        /** Keine Teilstufe im Spiel. */
        NONE,

        /** Angefordert, Bestaetigung steht aus. */
        PENDING,

        /** Autoritativ bestaetigt: unsere Teilrate laeuft. */
        RUNNING,

        /** Abbruch angefordert. */
        ENDING,

        /** Abbruch oder Setzen wartet auf seinen Backoff. */
        BACKOFF,

        /** Versuchsdeckel erreicht - kein weiteres Kommando. */
        GIVEN_UP,

        /** Die Pumpensicht ist unbrauchbar - der Zustand ist GEHALTEN,
         *  nicht bestaetigt. Ein eigener Zustand, kein "laeuft". */
        VIEW_UNKNOWN,
    }

    /**
     * DIE ZIELRATE FUER DIE ANZEIGE - eine Stelle, nicht im Runner verstreut.
     *
     * WAEHREND EINES ABBRUCHS ist das Ziel die RUECKKEHRBASIS, nicht die
     * Rate, die gerade beendet wird. Sonst zeigte die Zeile als "Ziel"
     * genau das, was verschwinden soll.
     *
     * `null` heisst "kein Ziel" - nicht 0.
     */
    fun anzeigeZiel(
        state: State,
        wunschGeklemmtUPerH: Double?,
        rueckkehrBasisUPerH: Double,
    ): Double? {
        if (state.ending != null)
            return rueckkehrBasisUPerH.takeIf { it.isFinite() && it > 0.0 }
        state.pendingRequest?.let { return it.rateUPerH }
        state.confirmedRunning?.let { return it.rateUPerH }
        return wunschGeklemmtUPerH?.takeIf { it.isFinite() && it > 0.0 }
    }

    /** Der Anzeigezustand zu einem [Step] - eine Stelle, nicht drei. */
    fun anzeige(step: Step): Anzeige = when (step.reason) {
        Reason.VIEW_UNKNOWN_HELD                              -> Anzeige.VIEW_UNKNOWN
        Reason.END_GIVEN_UP, Reason.SET_GIVEN_UP              -> Anzeige.GIVEN_UP
        Reason.END_BACKOFF_WAIT                               -> Anzeige.BACKOFF
        Reason.END_REQUESTED, Reason.END_RETRY                -> Anzeige.ENDING
        Reason.CONFIRMED_RUNNING                              -> Anzeige.RUNNING
        Reason.WAITING_CONFIRM, Reason.SET_SUPPRESSED_DUPLICATE,
        Reason.SET_HELD_HIGHER, Reason.SET_RETRY,
        Reason.SET_LOWERED                                    -> Anzeige.PENDING
        Reason.NONE, Reason.CLEARED_CONFIRMED,
        Reason.CONFIRM_TIMEOUT                                ->
            if (step.state.confirmedRunning != null) Anzeige.RUNNING
            else if (step.state.pendingRequest != null) Anzeige.PENDING
            else Anzeige.NONE
    }

    data class Step(
        val state: State,
        val smbBlocked: Boolean,
        val sendCancel: Boolean,
        val allowSet: Boolean,
        val reason: Reason,
    )

    /**
     * Wieviele Minuten eine Anforderung ohne Sichtbarkeit gehalten wird.
     *
     * DIESES FENSTER IST ZUGLEICH DER TAKT DER SETZVERSUCHE: ein
     * Neuversuch ist erst nach seinem Ablauf zulaessig und eroeffnet dann
     * ein neues. Ein zusaetzlicher 3-min-Setz-Backoff lag dahinter und war
     * damit toter Zustand - er ist entfernt, nicht bloss ungenutzt
     * stehengeblieben. Der ABBRUCH-Backoff bleibt: dort gibt es kein
     * solches Fenster.
     */
    const val CONFIRM_WINDOW_MIN = 5

    /** Mindestabstand zwischen zwei Abbruchkommandos [min]. */
    const val END_BACKOFF_MIN = 3

    /** Deckel der Abbruchversuche - dieselbe Idee wie beim Medtrum-Backoff
     *  der Null: ohne ihn wiederholt FUSE das Kommando minuetlich. */
    const val END_MAX_ATTEMPTS = 3

    /** Deckel der Setzversuche fuer dieselbe Anforderung. */
    const val SET_MAX_ATTEMPTS = 3

    /**
     * Toleranz der Restlaufzeit nach UNTEN [min] - Rundung und
     * Zyklusversatz. Nach OBEN gilt statt dessen die bisher verstrichene
     * Wartezeit (gedeckelt auf [CONFIRM_WINDOW_MIN]): die Pumpe kann nur
     * SPAETER starten als angefordert, nie frueher.
     */
    const val REMAINING_TOLERANCE_MIN = 3

    /**
     * DARF EINE LAUFENDE TBR ZUM PROFILBASAL ABGEBROCHEN WERDEN?
     *
     * NICHT jede: eine FREMDE nicht-nullende Absenkung abzubrechen hiesse,
     * fremdes abgesenktes Basal anzuheben - das ist C7b, und es gilt hier
     * genauso wie im KEEP- und NO_POSITIVE-Pfad. Erlaubt ist der Abbruch
     * nur, wo er kein Insulin hinzufuegt, das niemand angeordnet hat:
     *
     *  - echte NULL: das ist die Rueckkehr, um die es geht,
     *  - unsere BESTAETIGTE Teilrate: sie gehoert uns,
     *  - POSITIVE TBR: ihr Abbruch SENKT Insulin.
     */
    fun profilCancelZulaessig(
        state: State,
        current: TbrPolicy.Current?,
        nowTs: Long,
        basalStepUPerH: Double,
        sicherheitsDeckelUPerH: Double,
    ): Boolean {
        if (current == null) return false
        if (!basalStepUPerH.isFinite() || basalStepUPerH <= 0.0) return false
        // DIE HART GEDECKELTE Fassung der Tabelle, nicht `step/2`: bei
        // einem Basalschritt von 0,10 galte sonst eine FREMDE Rate von
        // 0,04 als Null und duerfte abgebrochen werden.
        if (TbrPolicy.isZeroRate(current.absoluteRateUPerH, basalStepUPerH)) return true
        if (matches(state.confirmedRunning, current, nowTs, basalStepUPerH)) return true
        if (matches(state.pendingRequest, current, nowTs, basalStepUPerH)) return true
        if (sicherheitsDeckelUPerH.isFinite() &&
            current.absoluteRateUPerH > sicherheitsDeckelUPerH + basalStepUPerH / 2.0
        ) return true
        return false
    }

    /** Passen Rate UND Restlaufzeit zu dieser Kennung? */
    fun matches(
        id: Identity?,
        current: TbrPolicy.Current?,
        nowTs: Long,
        basalStepUPerH: Double,
    ): Boolean {
        if (id == null || !id.valid) return false
        if (current == null || current.violation() != null) return false
        // Ein als TBR gelesener Extended Bolus ist nie unsere Teilrate.
        if (current.sourceType != TbrPolicy.SourceType.TEMP_BASAL) return false
        if (!basalStepUPerH.isFinite() || basalStepUPerH <= 0.0) return false
        if (nowTs < id.setAtTs) return false
        if (abs(current.absoluteRateUPerH - id.rateUPerH) > basalStepUPerH / 2.0) return false
        val wartenMin = (nowTs - id.setAtTs) / 60_000.0
        val erwartetRest = id.durationMin - wartenMin
        if (erwartetRest <= 0.0) return false
        val verspaetungMax = min(wartenMin, CONFIRM_WINDOW_MIN.toDouble())
        return current.remainingMin >= erwartetRest - REMAINING_TOLERANCE_MIN &&
            current.remainingMin <= erwartetRest + verspaetungMax
    }

    /**
     * DIE EFFEKTIVE WIRKUNG EINES KOMMANDOS - so, wie AAPS sie ausfuehrt.
     *
     * @param ausgegeben hat das Kommando das Aktuationstor passiert und
     *                   steht wirklich in der APSResult-Ausgabe?
     */
    /**
     * ERZEUGT DIE TEILSTUFE IN DIESEM ZYKLUS UEBERHAUPT EINE BASALAKTION?
     *
     * ===================================================================
     * WARUM DIESE FRAGE VOR DER BOLUSFRAGE STEHEN MUSS
     * ===================================================================
     * Traegt die Bahn bereits das VOLLE Profilbasal, laeuft autoritativ
     * keine TBR und steht kein eigener Vorgang offen, dann kommt aus der
     * Teilstufe in diesem Zyklus GAR KEIN Kommando - die Tabelle antwortet
     * mit `PARTIAL_ALREADY_AT_PROFILE` und fordert nichts an.
     *
     * Der Runner hat den Bolus trotzdem genullt, WEIL das Teilstufen-Flag
     * gesetzt war, und erst danach festgestellt, dass gar nichts zu tun
     * ist. Eine autorisierte Mahlzeitendosis verschwand damit ohne
     * Basalgrund. Die Nullung hat einen technischen Zweck (C7a verwirft
     * eine ANHEBENDE TBR-Anforderung neben einem positiven SMB) - aber wo
     * keine Anforderung entsteht, gibt es auch nichts zu schuetzen.
     *
     * ===================================================================
     * WAS HIER AUSDRUECKLICH NICHT GILT
     * ===================================================================
     * Eine echt laufende Teilrate, eine offene Anforderung, ein
     * unbestaetigter Abbruch und eine unbrauchbare Pumpensicht sind
     * EIGENE Faelle und liefern hier `false`. Diese Funktion ist keine
     * "Mahlzeit darf immer"-Ausnahme, sondern die Feststellung, dass in
     * diesem Zyklus keine Basalaktion existiert, die zu schuetzen waere.
     */
    fun ohneAktion(
        wunschRateUPerH: Double,
        aapsBasisUPerH: Double,
        basalStepUPerH: Double,
        durationMin: Int,
        view: View,
        state: State,
    ): Boolean {
        // Ein eigener Vorgang - bestaetigt, offen oder im Abbruch - ist ein
        // eigener Fall und behaelt seinen Schutz.
        if (!state.leer || state.ending != null) return false
        // Nur eine BEWIESENE Abwesenheit zaehlt. `View.Unknown` heisst
        // "nicht belastbar bekannt" und traegt diese Feststellung nicht.
        val sicht = view as? View.Authoritative ?: return false
        if (sicht.current != null) return false
        return klassifiziere(
            rateUPerH = wunschRateUPerH,
            durationMin = durationMin,
            scheduledBasalUPerH = aapsBasisUPerH,
            basalStepUPerH = basalStepUPerH,
            ausgegeben = true,
        ) == Wirkung.CANCEL_TO_PROFILE
    }

    fun klassifiziere(
        rateUPerH: Double?,
        durationMin: Int?,
        scheduledBasalUPerH: Double,
        basalStepUPerH: Double,
        ausgegeben: Boolean,
    ): Wirkung {
        if (!ausgegeben || rateUPerH == null || durationMin == null) return Wirkung.NO_REQUEST
        if (!rateUPerH.isFinite() || rateUPerH < 0.0 || durationMin < 0) return Wirkung.NO_REQUEST
        // Der AUSDRUECKLICHE Abbruch zuerst - Rate 0 UND Dauer 0.
        if (rateUPerH == 0.0 && durationMin == 0) return Wirkung.CANCEL_TO_PROFILE
        // Eine Rate 0 mit positiver Dauer ist eine GESETZTE Null: sie
        // ersetzt unsere Teilrate, ist aber kein Abbruchversuch.
        if (rateUPerH == 0.0) return Wirkung.REPLACE_WITH_ZERO
        if (!scheduledBasalUPerH.isFinite() || !basalStepUPerH.isFinite() || basalStepUPerH <= 0.0)
            return Wirkung.NO_REQUEST
        // GENAU die Bedingung aus LoopPlugin.applyAPSRequest - ein GANZER
        // Basalschritt, nicht ein halber.
        if (abs(rateUPerH - scheduledBasalUPerH) < basalStepUPerH) return Wirkung.CANCEL_TO_PROFILE
        if (durationMin == 0) return Wirkung.CANCEL_TO_PROFILE
        return Wirkung.SET_PARTIAL
    }

    /**
     * DIE FORTSCHREIBUNG DES ZUSTANDS - Berechtigungen, keine Tatsachen.
     * Was tatsaechlich hinausging, bucht [buche].
     */
    fun advance(
        state: State,
        view: View,
        nowTs: Long,
        basalStepUPerH: Double,
        wunschRate: Double? = null,
        wantEnd: Boolean = false,
        /**
         * ZURUECK AUFS PROFILBASAL - der zentrale Fall der Stufe: die Bahn
         * traegt das volle Profilbasal, also soll die laufende Null enden.
         *
         * Das ist ein ABBRUCH und braucht denselben Backoff wie der
         * Abbruch der eigenen Teilrate. Ohne ihn ginge er jeden Zyklus
         * erneut raus, solange die Pumpe ihn nicht annimmt - und der
         * Besitz ist dabei LEER, also griff die bisherige Buchhaltung
         * nicht.
         */
        wantProfile: Boolean = false,
        /** Der Sicherheitsdeckel `min(Therapieprofil, pump.baseBasalRate)` -
         *  nur fuer die Frage, ob eine laufende TBR POSITIV ist. */
        sicherheitsDeckelUPerH: Double = Double.NaN,
    ): Step {
        val wunsch = wunschRate?.takeIf { it.isFinite() && it > 0.0 }
        // DER SCHNELLPFAD GILT NUR OHNE SETZWUNSCH.
        //
        // Er lag frueher VOR der Sichtpruefung, und damit konnte bei
        // leerem Besitz, unbrauchbarer Sicht und einem neuen
        // Teilratenwunsch `allowSet = true` entstehen (Review-P0):
        // `TEMP_BASAL_FALLBACK` sperrt zwar den SMB, entfernt die
        // TBR-Anforderung aber nicht - FUSE haette eine Teilrate gesetzt,
        // ohne eine moeglicherweise laufende FREMDE TBR zu kennen. C7b
        // kann nicht schuetzen, was es nicht sieht.
        //
        // Ohne Wunsch bleibt der Pfad ein neutraler No-op, damit der
        // normale Altpfad unveraendert bleibt.
        if (state.leer && state.ending == null && !wantProfile && wunsch == null)
            return Step(state, smbBlocked = false, sendCancel = false, allowSet = false, reason = Reason.NONE)

        // (1) UNBRAUCHBARE SICHT SAGT NICHTS. Halten, nichts abbrechen,
        //     nichts setzen, SMB gesperrt lassen. Auch ein NEUER Wunsch
        //     wartet hier - und sperrt den schnellen Kanal mit, weil FUSE
        //     gleich etwas stellen will, ohne die Lage zu kennen.
        val current = when (view) {
            is View.Unknown       -> return Step(
                state, smbBlocked = state.smbBlocked || wunsch != null,
                sendCancel = false, allowSet = false, reason = Reason.VIEW_UNKNOWN_HELD,
            )
            is View.Authoritative -> view.current
        }
        val pendingPasst = matches(state.pendingRequest, current, nowTs, basalStepUPerH)
        val confirmedPasst = matches(state.confirmedRunning, current, nowTs, basalStepUPerH)

        // (2) DER AUTORITATIVE ABGLEICH - EINMAL, FUER ALLE PFADE.
        //
        // Er stand frueher HINTER dem Profil-Abbruch, und der hatte einen
        // eigenen, unvollstaendigen Abgleich. Folge (Review-P0): nach
        // einem erfolgreichen Cancel wurde nur `ending` entfernt, die
        // bestaetigte Kennung blieb stehen - waehrend derselbe Step
        // `smbBlocked=false` meldete. Zustand, Phase und SMB-Freigabe
        // widersprachen sich.
        var s = state
        if (pendingPasst)
            s = s.copy(confirmedRunning = s.pendingRequest, pendingRequest = null, pendingAttempts = 0)
        else if (s.confirmedRunning != null && !confirmedPasst)
        // Die bestaetigte Rate ist autoritativ weg. Eine noch offene
        // Anforderung bleibt davon UNBERUEHRT - sie hat ihre eigene Frist.
            s = s.copy(confirmedRunning = null)
        val laeuftEigenes = pendingPasst || confirmedPasst
        // Eine offene Anforderung, die nie aufgetaucht ist, verfaellt nach
        // ihrer Frist - auch hier gemeinsam, nicht je Zweig.
        val pendingAbgelaufen = !pendingPasst && s.pendingRequest != null &&
            nowTs - s.pendingRequest!!.setAtTs > CONFIRM_WINDOW_MIN * 60_000L

        // (3) ZURUECK AUFS PROFILBASAL - erst NACH dem Abgleich.
        if (wantProfile && !wantEnd) {
            // C7b: eine FREMDE nicht-nullende Absenkung wird NICHT
            // abgebrochen - das waere eine Insulin-Erhoehung ohne Auftrag.
            if (!profilCancelZulaessig(s, current, nowTs, basalStepUPerH, sicherheitsDeckelUPerH)) {
                val ohne = s.copy(
                    ending = null,
                    pendingRequest = if (pendingAbgelaufen) null else s.pendingRequest,
                    pendingAttempts = if (pendingAbgelaufen) 0 else s.pendingAttempts,
                )
                return Step(
                    ohne, smbBlocked = ohne.smbBlocked, sendCancel = false, allowSet = false,
                    // DIE REIHENFOLGE ZAEHLT: solange eine Anforderung
                    // GEHALTEN wird, ist das die Auskunft - nicht
                    // "beendet". `CLEARED_CONFIRMED` kommt erst, wenn
                    // nichts mehr aussteht UND vorher etwas von uns da war.
                    reason = when {
                        ohne.pendingRequest != null -> Reason.WAITING_CONFIRM
                        pendingAbgelaufen           -> Reason.CONFIRM_TIMEOUT
                        current == null && !state.leer -> Reason.CLEARED_CONFIRMED
                        else                        -> Reason.NONE
                    },
                )
            }
            val e = s.ending ?: Ending(sinceTs = nowTs)
            val s2 = s.copy(ending = e)
            if (e.attempts >= END_MAX_ATTEMPTS)
                return Step(s2, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.END_GIVEN_UP)
            val darf = e.lastRequestTs == 0L || nowTs - e.lastRequestTs >= END_BACKOFF_MIN * 60_000L
            return if (darf)
                Step(s2, smbBlocked = true, sendCancel = true, allowSet = false,
                     reason = if (e.attempts == 0) Reason.END_REQUESTED else Reason.END_RETRY)
            else Step(s2, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.END_BACKOFF_WAIT)
        }
        if (s.leer && s.ending == null)
        // Der Grund bleibt lesbar: war vorher etwas von uns da, ist es
        // JETZT autoritativ beendet - das ist eine andere Auskunft als
        // "war nie etwas".
            return Step(
                s, smbBlocked = false, sendCancel = false,
                allowSet = wunsch != null && !wantEnd,
                reason = if (state.leer) Reason.NONE else Reason.CLEARED_CONFIRMED,
            )

        // (4) BEENDEN GEWOLLT ODER SCHON IM GANG - schlaegt jeden Setzwunsch.
        if (wantEnd || s.ending != null) {
            val e = s.ending ?: Ending(sinceTs = nowTs)
            s = s.copy(ending = e)
            if (!laeuftEigenes) {
                // Eine offene Anforderung kann noch verspaetet auftauchen.
                val offen = s.pendingRequest
                val nochInFrist = offen != null && nowTs - offen.setAtTs <= CONFIRM_WINDOW_MIN * 60_000L
                return if (nochInFrist)
                    Step(s, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.WAITING_CONFIRM)
                else Step(State(), smbBlocked = false, sendCancel = false, allowSet = false, reason = Reason.CLEARED_CONFIRMED)
            }
            if (e.attempts >= END_MAX_ATTEMPTS)
                return Step(s, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.END_GIVEN_UP)
            val darfAbbrechen = e.lastRequestTs == 0L ||
                nowTs - e.lastRequestTs >= END_BACKOFF_MIN * 60_000L
            return if (darfAbbrechen)
                Step(
                    s, smbBlocked = true, sendCancel = true, allowSet = false,
                    reason = if (e.attempts == 0) Reason.END_REQUESTED else Reason.END_RETRY,
                )
            else Step(s, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.END_BACKOFF_WAIT)
        }

        // (4) EINE OFFENE ANFORDERUNG REGELT DAS SETZEN.
        val offen = s.pendingRequest
        if (offen != null) {
            val fristOffen = nowTs - offen.setAtTs <= CONFIRM_WINDOW_MIN * 60_000L
            return when {
                wunsch == null ->
                    if (fristOffen) Step(s, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.WAITING_CONFIRM)
                    // Frist verstrichen und niemand will mehr: verwerfen, aber
                    // BENANNT - und nie eine fremde Absenkung anfassen. Eine
                    // bestaetigt laufende Rate bleibt davon unberuehrt.
                    else s.copy(pendingRequest = null, pendingAttempts = 0).let {
                        Step(it, smbBlocked = !it.leer, sendCancel = false, allowSet = false, reason = Reason.CONFIRM_TIMEOUT)
                    }

                // SICHERER GEHT SOFORT - aber nur, wenn wirklich etwas
                // von uns LAEUFT, das gesenkt werden koennte. Nimmt die
                // Pumpe gar nichts an, hat ein niedrigerer Wunsch keinen
                // Sicherheitswert; er waere bloss ein weiterer Versuch und
                // wuerde den Deckel aushebeln, sobald die Guard-Rate
                // wandert (vom Runner-Ausgabetest gefunden: 34 Kommandos
                // statt 3).
                s.confirmedRunning != null && wunsch < offen.rateUPerH - basalStepUPerH / 2.0 ->
                    Step(s, smbBlocked = true, sendCancel = false, allowSet = true, reason = Reason.SET_LOWERED)

                fristOffen && wunsch > offen.rateUPerH + basalStepUPerH / 2.0 ->
                    Step(s, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.SET_HELD_HIGHER)

                fristOffen ->
                    // DIE RACE: hier ging bisher jede Minute ein Kommando
                    // raus und die Frist begann neu.
                    Step(s, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.SET_SUPPRESSED_DUPLICATE)

                // Der Deckel gilt fuer die offene Anforderung als GANZES,
                // nicht je Rate - sonst setzt eine wandernde Rate ihn
                // jedes Mal zurueck.
                s.pendingAttempts >= SET_MAX_ATTEMPTS ->
                    Step(s, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.SET_GIVEN_UP)

                else ->
                    Step(s, smbBlocked = true, sendCancel = false, allowSet = true, reason = Reason.SET_RETRY)
            }
        }

        // (5) NICHTS OFFEN.
        if (laeuftEigenes)
        // Eine bestaetigt laufende Rate darf erneuert, gesenkt und
        // angehoben werden; ueber die Erneuerung selbst entscheidet die
        // Tabelle (ALREADY_RUNNING/RENEW).
            return Step(s, smbBlocked = true, sendCancel = false, allowSet = wunsch != null, reason = Reason.CONFIRMED_RUNNING)
        return Step(
            State(), smbBlocked = false, sendCancel = false, allowSet = wunsch != null,
            reason = if (state.leer) Reason.NONE else Reason.CLEARED_CONFIRMED,
        )
    }

    /**
     * BUCHT, WAS TATSAECHLICH HINAUSGING - nach [Wirkung], nicht nach
     * Wunsch. Nur hier bewegen sich Kennungen und Zaehler.
     */
    fun buche(
        state: State,
        wirkung: Wirkung,
        rateUPerH: Double,
        durationMin: Int,
        nowTs: Long,
        basalStepUPerH: Double,
    ): State = when (wirkung) {
        // Nichts ging raus: nichts aendert sich. Ein vom Aktuationstor
        // verworfener Wunsch darf setAtTs und Zaehler NICHT bewegen.
        Wirkung.NO_REQUEST -> state

        // AAPS bricht ab. Danach laeuft nichts von uns - eine Kennung
        // dafuer waere eine Kennung fuer nichts. Der Abbruchversuch wird
        // gezaehlt, damit Backoff und Deckel greifen.
        // Der Abbruchversuch wird IMMER gezaehlt - auch bei leerem Besitz.
        // Genau dort sass die Luecke: der zentrale Fall "aktive Null ->
        // Profilbasal" bricht etwas ab, das uns nicht gehoert, und ohne
        // Buchung ginge das Kommando jeden Zyklus erneut raus.
        Wirkung.CANCEL_TO_PROFILE -> {
            val e = state.ending ?: Ending(sinceTs = nowTs)
            state.copy(
                pendingRequest = null, pendingAttempts = 0,
                ending = e.copy(attempts = e.attempts + 1, lastRequestTs = nowTs),
            )
        }

        // Eine gesetzte Null ersetzt unsere Teilrate - aber sie ist KEIN
        // Abbruchversuch und verbraucht keinen. Die offene Anforderung ist
        // damit erledigt; ob die bestaetigte Rate noch laeuft, sagt der
        // naechste autoritative Snapshot.
        Wirkung.REPLACE_WITH_ZERO -> state.copy(pendingRequest = null, pendingAttempts = 0)

        Wirkung.SET_PARTIAL -> {
            // Der Zaehler laeuft fuer die offene ANFORDERUNG, nicht je
            // Rate: eine wandernde Guard-Rate darf ihn nicht bei jedem
            // Versuch zuruecksetzen. Neu beginnt er erst, wenn wieder
            // etwas BESTAETIGT laeuft - dann ist die vorige Anforderung
            // geklaert.
            // NUR eine leere Anforderung beginnt eine neue Serie.
            //
            // Frueher stand hier zusaetzlich `|| confirmedRunning != null` -
            // damit setzte jeder Retry der offenen Anforderung den Zaehler
            // auf 1 zurueck, solange die ALTE bestaetigte Rate weiterlief,
            // und der Dreierdeckel griff nie. Eine bestaetigte alte Rate
            // bestaetigt die offene NEUE Anforderung nicht.
            state.copy(
                pendingRequest = Identity(rateUPerH, nowTs, durationMin),
                pendingAttempts = if (state.pendingRequest == null) 1 else state.pendingAttempts + 1,
                ending = null,
            )
        }
    }
}
