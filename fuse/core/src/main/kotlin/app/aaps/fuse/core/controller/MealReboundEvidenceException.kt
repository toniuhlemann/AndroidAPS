package app.aaps.fuse.core.controller

import app.aaps.fuse.core.signal.GlucoseStability

/**
 * DARF DAS HISTORISCHE REBOUND-VETO AM LIVENESS-TOR ENTFALLEN, OBWOHL DER
 * VERSIEGELTE EVIDENZKREDIT VERBRAUCHT IST? (Toni 14.09., Variante A)
 *
 * ===================================================================
 * DER ANLASS
 * ===================================================================
 * Das bestehende Sonderrecht (`NightWindow.evidenceMayOverrideRebound`)
 * verlangt versiegelten Evidenzkredit > 0. Jede Insulinbuchung - auch
 * Foundation - zieht Menge x ISF vom Bestand ab, und zwar vom bereits
 * versiegelten Anteil; frischer Zufluss wird erst im Folgezyklus
 * kreditfaehig. In einer fruehen Mahlzeit fiel der Kredit dadurch auf 0,
 * waehrend der Bestand mit frischem Zufluss positiv blieb (PENDING_SEAL).
 * Ein LAUFENDER Kanal endete mit REBOUND_ACTIVE samt Wiederbewaffnungs-
 * sperre. Die Buchhaltung selbst ist richtig und bleibt unveraendert -
 * falsch war nur, den verbrauchbaren Kreditrest als Voraussetzung fuer
 * das Aufheben eines historischen Vetos zu lesen.
 *
 * ===================================================================
 * DER VERTRAG - DREI GETRENNTE FRAGEN
 * ===================================================================
 * 1. AUTORISIERUNG (persistent, eindeutig, nicht widerrufen, befristet):
 *    - der zentrale Dosierkontext ist MEAL (gepinnte Marker-Leistungs-
 *      autorisierung, gesetzt nur beim in diesem Prozess beobachteten
 *      Druck, geloescht bei Widerruf oder Markerwechsel);
 *    - das Rebound-Sonderrecht ist fuer GENAU diesen Marker gepinnt;
 *    - dessen beim Druck eingefrorene Frist laeuft noch (halb offen).
 *    Nicht an eine Upfront-Menge oder Huellengroesse gekoppelt.
 *
 * 2. DIE HISTORISCHE REBOUND-ERKLAERUNG MUSS AELTER SEIN ALS DIE
 *    AUTORISIERUNG. Der Marker widerlegt "ein Anstieg nach dem Tief ist
 *    Traubenzucker" nur fuer ein Tief VOR dem Druck. Ein Tief danach (auch
 *    ein bis zum Druck andauerndes) ist neue Information - dann sind
 *    Gegen-KH wahrscheinlich, und die Ausnahme gilt nicht.
 *
 * 3. MESSLAGE, ausdruecklich in drei Groessen getrennt:
 *    a) EVIDENZBESTAND vorhanden (Phase ACTIVE oder PENDING_SEAL): eine
 *       gemessene, BGI-bereinigte, noch nicht mit Insulin bezahlte
 *       Stoerung existiert. Das ist KEIN Nachweis eines aktuellen Anstiegs -
 *       ACTIVE kann ohne neues Intervall aus aelterem Bestand fortbestehen,
 *       und ein positiver bereinigter Zufluss heisst nicht, dass der
 *       tatsaechliche Glukosewert steigt.
 *    b) FRISCHER ZUFLUSS dieses Zyklus: wird exportiert, ist aber bewusst
 *       KEINE Bedingung (ein einzelnes flaches Minutenintervall wuerde den
 *       laufenden Kanal sonst mit Sperre beenden).
 *    c) TATSAECHLICHER MESSVERLAUF: der bestehende Stabilitaetsnachweis auf
 *       der ROHEN Reihe muss STABLE melden. FALLING (auch wenn UKF noch nicht
 *       faellt und die bereinigte Evidenz positiv bleibt) und UNDETERMINED
 *       verweigern. Keine neue Schwelle: dieselben Parameter wie beim
 *       Stabilitaetsnachweis der Direktdosis.
 *    Der ANSTIEG selbst bleibt Sache der unveraenderten Druckbedingung
 *    (BG ueber Schwelle und r >= 1) hinter diesem Tor.
 *
 * GUELTIGKEIT: jeden Zyklus neu, ohne Gedaechtnis und ohne Uebertrag.
 * Endet die Ausnahme waehrend eines Laufs im rohen Fenster, gilt der
 * unveraenderte REBOUND_ACTIVE-Ausgang mit Sperre.
 *
 * WIRKUNGSBEREICH: nur das Rebound-Veto im Liveness-Tor. Da das Tief vor
 * dem Marker liegen muss, wirkt die Ausnahme hoechstens bis zum Ende des
 * rohen Rebound-Fensters nach DIESEM Tief, und nie ueber eine der beiden
 * Fristen hinaus. Normalpfad, Totbaender, NightWindow und die
 * Direktdosis-Kette bleiben unberuehrt.
 *
 * KEINE MENGE: Kandidat, Deckel, Buchungen, Evidenzabzuege, Endpruefung
 * und Publikations-Gate sind unveraendert. Die Erlaubnis wird im selben
 * Zyklus zusammen mit dem Evidenzzustand und den Buchungen persistiert;
 * scheitert der Persist, entfernt das Gate die Menge.
 */
object MealReboundEvidenceException {

    /** Warum die Ausnahme NICHT gilt. Reihenfolge = Pruefreihenfolge. */
    enum class Denial {
        DISABLED,
        NOT_MEAL_AUTHORIZED,
        OVERRIDE_PIN_MISMATCH,
        OVERRIDE_EXPIRED,
        LOW_AFTER_AUTHORIZATION,
        NO_EVIDENCE_STOCK,
        MEASURED_NOT_STABLE,
    }

    data class Input(
        val enabled: Boolean,
        val computeTs: Long,
        /** Der aktive, gegen die Widerrufsmarke abgeglichene Marker. */
        val markerTs: Long,
        /** `DosingContext.Decision.mealAuthorized` dieses Zyklus. */
        val mealAuthorized: Boolean,
        val reboundOverridePinnedForTs: Long,
        val reboundOverrideDeadlineTs: Long,
        /** Juengstes gemessenes Tief (0 = keines bekannt). */
        val lastLowTs: Long,
        val evidencePhase: EvidenceStock.Phase?,
        val measuredVerdict: GlucoseStability.Verdict?,
    )

    data class Decision(val allowed: Boolean, val denial: Denial?)

    fun decide(i: Input): Decision {
        fun nein(d: Denial) = Decision(false, d)
        if (!i.enabled) return nein(Denial.DISABLED)
        if (i.markerTs <= 0L || !i.mealAuthorized) return nein(Denial.NOT_MEAL_AUTHORIZED)
        if (i.reboundOverridePinnedForTs <= 0L || i.reboundOverridePinnedForTs != i.markerTs)
            return nein(Denial.OVERRIDE_PIN_MISMATCH)
        if (i.reboundOverrideDeadlineTs <= 0L || i.computeTs >= i.reboundOverrideDeadlineTs)
            return nein(Denial.OVERRIDE_EXPIRED)
        if (i.lastLowTs >= i.markerTs) return nein(Denial.LOW_AFTER_AUTHORIZATION)
        if (i.evidencePhase != EvidenceStock.Phase.ACTIVE && i.evidencePhase != EvidenceStock.Phase.PENDING_SEAL)
            return nein(Denial.NO_EVIDENCE_STOCK)
        if (i.measuredVerdict != GlucoseStability.Verdict.STABLE) return nein(Denial.MEASURED_NOT_STABLE)
        return Decision(true, null)
    }

    /** Sperrt das rohe Rebound-Fenster den Kanal? Die EINE Formel des Tors. */
    fun gateBlocks(reboundRaw: Boolean, evidenceOverride: Boolean, exception: Decision): Boolean =
        reboundRaw && !evidenceOverride && !exception.allowed

    /**
     * Hat die Ausnahme das Veto TATSAECHLICH aufgehoben - nicht nur zugelassen?
     * Nur wenn das rohe Fenster lief UND das bestehende Sonderrecht fehlte.
     */
    fun vetoLifted(reboundRaw: Boolean, evidenceOverride: Boolean, exception: Decision): Boolean =
        reboundRaw && !evidenceOverride && exception.allowed
}
