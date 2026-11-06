package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.DriveDecayModel
import app.aaps.fuse.core.predictor.DriveEstimate
import app.aaps.fuse.core.predictor.InsulinLineage
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.core.predictor.IobPoint
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.predictor.PredictorInput
import app.aaps.fuse.core.predictor.PredictorOutcome
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryCore
import app.aaps.fuse.core.predictor.VirtualTrajectoryFactory
import app.aaps.fuse.core.signal.BgiAdjustedSeries
import app.aaps.fuse.core.signal.PairSlopeBand
import app.aaps.fuse.core.signal.UkfQ1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Der gueltige Modellbereich: der Messbereich des Sensors (Libre 3) fuer den WAHREN Zucker [mg/dl]. */
private const val MODELL_UNTEN_MGDL = 40.0
private const val MODELL_OBEN_MGDL = 400.0

/** Punkt 3: der Kopf der Zeilen je Zyklus (s. `lauf`, zyklusZeilen). */
private const val ZYKLUS_KOPF = "m;bg;q1;r;bahnFreigabe;normalU;iobU;bolusIobU;gU;grenzeU;smbU;kanalUPerH;bindung;block;ratio\n"

/**
 * DIE RECHNER-MAHLZEIT IM GESCHLOSSENEN KREIS - Entwurf B, Abschnitte 16 bis 24 (`fuse-entwuerfe/ENTWURF_B_ECARBS_SPAETKONTO.md`):
 * die gemeinsame Bilanz G ([MealBalance]) mit dem eCarbs-Rest begrenzt den SMB-Weg und den langsamen Kanal, und bei
 * negativer Bilanz nach dem Anstieg nimmt FUSE Basal zurueck (GV-R) - gegen heute. Grundfunktion ohne Schalter (Toni
 * 05.11.2026).
 *
 * SYNTHETISCHER PATIENT, keine Messdaten: ISF 50, IC 10, oref 45/540, Basal 0,45 U/h neutral. Rechner-Bolus 4 U fuer
 * 40 g Sofort-KH, eCarbs 17 g ab +60 min ueber 3 h ohne Insulin. Dazu eine grosse Mahlzeit mit grosser Spaetwirkung
 * (Pizza). Die Sofort-KH-Aufnahme weicht von der Erwartungskurve ab (schneller, wie die Kurve, langsamer).
 *
 * Die FUSE-Einstellungen sind die Werkseinstellungen (Kanal eingeschaltet). Was keine Einstellung ist - Sensor, Rauschen,
 * Patient -, ist ein synthetischer Testwert.
 *
 * Wie im Runner (`FuseCycleRunner.hclAnrechnung`): Guard und Schwanz sehen die Sofort-KH ueber die Erwartungskurve mit
 * [EarlySupply.hclGuardScale] (ab +20 min, hoechstens die Kurve, nach der beobachteten Stoerung), der Bedarf nicht. Die
 * eCarbs sehen Guard und Schwanz NICHT - sie zaehlen nur in G.
 *
 * Die Schutz-Null kommt aus dem echten [LowThreatGate.evaluate] (Bolusanteil, gemessener 5-min-Trend) und nimmt das
 * Basal weg, solange decide ZERO_TEMP sagt; der Kanal endet dann (harter Stopp). Die Ruecknahme-Befehle laufen ueber ein
 * Pumpenmodell: angefordert, ausstehend und laufend getrennt, mit Ersetzen, Erneuern und Abbruch (Abschnitt 23).
 *
 * Beobachter und Kanal wie im Runner (unten). Die Tief-Kennzahlen laufen auf dem WAHREN Zucker.
 *
 * VEREINFACHT: kein Marker-Prior, keine Nacht, Kanalbuchung zum Plan, die ISF ueber den ganzen Lauf gleich, kein
 * Transport (SMB sind sofort im IOB). Gueltig nur im Modellbereich ([MODELL_UNTEN_MGDL] bis [MODELL_OBEN_MGDL]); jede
 * Tiefverschlechterung gegen heute wird ausgewiesen.
 */
class ECarbsHclClosedLoopTest {

    private val isf = 50.0
    private val ic = 10.0
    private val peak = 45.0
    private val dia = 540.0
    private val basalUPerH = 0.45
    private val t0 = 1_800_000_000_000L
    private val minMs = 60_000L
    private val startBg = 110.0
    private val nowCarbsG = 40.0
    private val modelT = 30.0
    private val referenceCurveEndU = 3.0
    private val eCarbsG = 17
    private val eStartMin = 60
    private val eDurationMin = 180
    private val horizon = 120
    private val endMin = 480
    private val cycleMin = 5
    /** Die Werkseinstellungen: Guard-Boden 70 mg/dl, Freigabehorizont 30 min. */
    private val limits = FuseController.Limits()
    private val tailFloor = 70.0
    private val maxIob = 8.0

    // ---- DER KANAL: eingeschaltet, sonst mit den Werkseinstellungen (Faktor, Schwelle, Einstieg). Der Nachtfaktor wirkt
    // hier nicht, die Mahlzeit liegt am Tag.
    private val kanal = SlowCorrectionChannel.Settings(enabled = true)

    // ---- DER BEOBACHTER WIE IM RUNNER. Der Sensor folgt dem Blut mit Verzug erster Ordnung und Rauschen; daraus der echte
    // UKF ([UkfQ1], q1 und Trend), die BGI-bereinigte Reihe und die echte Theil-Sen-Spanne ([PairSlopeBand]) mit Fenster
    // und Quantil der Werkseinstellung (18 min, 50 = Untergrenze gleich Mittel, kein Band). Je EIN Wert fuer Verzug und
    // Rauschen, keine Reihe; dieselbe Rauschfolge in allen Laeufen.
    // ---- Die Anstiegsrampe mit den Werkseinstellungen: sie bestimmt die wirksame SMB-Ratio und - ohne eigene
    // Mahlzeit-laeuft-Schwelle - ab wann die HCL-Fortsetzung gilt. Ihre Unterkante ist auch die Grenze, unter der die
    // Ruecknahme "Anstieg vorbei" annimmt.
    private val rampeUnten = 0.5
    private val rampeOben = 2.0
    private val sensorVerzugMin = 5.0
    private val sensorRauschenMgdl = 2.5
    private val theilSenFensterMin = 18
    private val antriebQuantilPct = 50
    private val rauschen: DoubleArray = Random(4711L).let { z -> DoubleArray(1201) { z.nextGaussian() * sensorRauschenMgdl } }

    // oref-Exponentialkurve wie AAPS - Aktivitaet je Einheit [1/min].
    private val tau = peak * (1 - peak / dia) / (1 - 2 * peak / dia)
    private val aa = 2 * tau / dia
    private val ss = 1 / (1 - aa + (1 + aa) * exp(-dia / tau))
    private fun act(t: Double) = if (t <= 0.0 || t >= dia) 0.0 else ss / (tau * tau) * t * (1 - t / dia) * exp(-t / tau)
    private fun iobFrac(t: Double) = if (t <= 0.0) 1.0 else 1.0 - EarlySupply.orefActionFraction(t, peak, dia)

    private fun gammaAnteil(t: Double, tc: Double) = if (t <= 0.0) 0.0 else 1.0 - (1.0 + t / tc) * exp(-t / tc)
    private fun gammaRate(t: Double, tc: Double) = if (t <= 0.0) 0.0 else t / (tc * tc) * exp(-t / tc)

    /** Die Erwartungskurve der Sofort-KH: Gamma-Form mit [modelT], auf 120 min normiert, Referenzmahlzeit in U. */
    private val kurve = ExpectationAccount.Curve.of(
        (0 until ExpectationAccount.CURVE_POINTS).map { i ->
            referenceCurveEndU * gammaAnteil(i * 5.0, modelT) / gammaAnteil(120.0, modelT)
        },
    )!!
    private val phi0 = EarlySupply.hclScale(nowCarbsG / ic, kurve)

    /** Der Patient der Simulation: der Standard oder die grosse Mahlzeit mit Spaetwirkung (Pizza). */
    private data class Patient(val isf: Double, val ic: Double, val sofortG: Double, val eCarbsG: Int, val kurve: ExpectationAccount.Curve)

    private val standard = Patient(isf, ic, nowCarbsG, eCarbsG, kurve)

    /**
     * DIE GROSSE MAHLZEIT MIT SPAETWIRKUNG (Pizza), synthetisch: ISF 80, IC 8. 48 g Sofort-KH mit Rechner-Bolus 6 U, dazu
     * 24 g eCarbs fuer den Fett-/Eiweissanteil ohne Insulin - unbedeckt rund 240 mg/dl Spaetwirkung (beim Standard 85).
     * Ihre Erwartungskurve hat die Gamma-Form mit 45 min ([Sofort.LANGSAMER]), auf 120 min normiert, Ende 4 U - bei der
     * Pizza ist "langsamer" also "wie die Kurve".
     */
    private val pizza = Patient(
        80.0, 8.0, 48.0, 24,
        ExpectationAccount.Curve.of(
            (0 until ExpectationAccount.CURVE_POINTS).map { i ->
                4.0 * gammaAnteil(i * 5.0, Sofort.LANGSAMER.tc) / gammaAnteil(120.0, Sofort.LANGSAMER.tc)
            },
        )!!,
    )

    private enum class Sofort(val tc: Double) { SCHNELLER(20.0), WIE_KURVE(30.0), LANGSAMER(45.0) }

    /**
     * Wie die eCarbs wirklich wirken. ABBRUCH (Codex-Pruefung, Toni 05.11.): die erste Haelfte der Teile kommt wie
     * angekuendigt, dann nichts mehr.
     */
    private enum class Wirkung(
        val faktor: Double, val versatzMin: Int, val anteil: Double = 1.0,
        /** Abstand der wahren Teile in Vielfachen des angekuendigten Abstands (2 = halbe Rate ueber die doppelte Zeit). */
        val abstandFaktor: Double = 1.0,
        /** Pause vor der zweiten Haelfte der Teile [min] - eine zweite Welle. */
        val pauseMin: Int = 0,
    ) {
        WIE_ANGEKUENDIGT(1.0, 0), KEINE(0.0, 0), HALB(0.5, 0), SPAET(1.0, 45), MEHR(1.5, 0), ABBRUCH(1.0, 0, anteil = 0.5),

        /**
         * GEGENPROBEN ZUR RUECKNAHME (Codex-Pruefung, Toni 05.11.): "r unter der Rampengrenze" kann auch eine langsame,
         * weiter laufende Aufnahme bedeuten. LANGSAM: dieselbe Menge mit halber Rate ueber die doppelte Zeit.
         */
        LANGSAM(1.0, 0, abstandFaktor = 2.0),

        /** ZWEITE_WELLE: die erste Haelfte wie angekuendigt, dann 60 min nichts, dann die zweite Haelfte. */
        ZWEITE_WELLE(1.0, 0, pauseMin = 60),
    }

    /**
     * Wie der Lauf bei Pumpe und Schleife ankommt (Codex-Pruefung, Toni 05.11.: Ruecknahme "einschliesslich laufender
     * Planung und Pumpenbestaetigung begrenzen").
     */
    private enum class Technik(
        /** Nach so vielen Minuten fuehrt die Pumpe eine Anforderung aus; bis dahin laeuft der alte Befehl weiter. */
        val ausfuehrungMin: Int = 0,
        /** Nach so vielen Minuten erfaehrt die Schleife, ob die Anforderung ausgefuehrt wurde (Bestaetigung). */
        val meldungMin: Int = 0,
    ) {
        NORMAL,

        /** Die Pumpe fuehrt sofort aus, die Bestaetigung kommt erst einen Zyklus spaeter. */
        BESTAETIGUNG_SPAET(meldungMin = 5),

        /** Die Pumpe fuehrt erst einen Zyklus spaeter aus (und bestaetigt dann); bis dahin laeuft der alte Befehl weiter. */
        AUSFUEHRUNG_SPAET(ausfuehrungMin = 5, meldungMin = 5),

        /** Jede zweite Anforderung scheitert; die Schleife erfaehrt es einen Zyklus spaeter. Der alte Befehl laeuft weiter. */
        BEFEHL_FEHLT(meldungMin = 5),

        /** Jeder Abbruch scheitert; der laufende Befehl laeuft bis zu seinem Ende (hoechstens 30 min). */
        ABBRUCH_FEHLT(meldungMin = 5),

        /** Nach dem ersten Ruecknahme-Befehl laufen 30 min lang keine Zyklen; was laeuft, laeuft bis zu seinem Ende weiter. */
        AUSFALL,
    }

    /**
     * Die Laeufe: HEUTE ohne Bilanz, PRODUKT mit der Bilanz G ([MealBalance.cycle]) - der SMB-Weg unter G minus Kanalplan
     * ([MealBalance.smbCapU]), der Kanal mit G als Luecke - und der Ruecknahme ([MealBalance.withdrawal]) ueber das
     * Pumpenmodell.
     */
    private enum class Arm { HEUTE, PRODUKT }

    /**
     * So lange gilt eine Rechner-Mahlzeit als angekuendigt [min] - das Kontofenster der Werkseinstellung (Marker plus
     * 2 h). Mit eCarbs laenger: bis ihr letzter Teil aufgenommen ist.
     */
    private val kontoFensterMin = 120

    /** Die Zeitkonstante, mit der der gemessene Antrieb in der Prognose abklingt [min] - die Werkseinstellung (driveTauMin). */
    private val antriebTauMin = 60.0

    /**
     * Wie der Rechner-Bolus und die wahre Sofort-KH-Menge zur MELDUNG stehen (Toni 05.11.: die gemeldete Mahlzeitgroesse
     * darf keine unveraenderliche Obergrenze sein).
     */
    private enum class Meldung(val bolusFaktor: Double, val sofortFaktor: Double) {
        WIE_GEMELDET(1.0, 1.0),
        UNTERBOLUS(0.7, 1.0),
        UNTERSCHAETZT(1.0, 1.5),
        BEIDES(0.7, 1.5),

        /** Ein wenig zu viel Bolus - eine kleine, anhaltende Ueberdeckung nach der Mahlzeit. */
        LEICHT_UEBER(1.03, 1.0),
    }

    private class Block(val rateUPerH: Double, val startMin: Int, val endMin: Int)

    private class Lauf(
        val bg: DoubleArray,
        val gabe: DoubleArray,
        val smbU: Double,
        val kanalU: Double,
        /** Minuten mit Schutz-Null (LowThreatGate) - das Basal fehlt dort. */
        val nullMin: Int,
        /** Zyklen, in denen G den Kanal begrenzte (gesenkt, kein Block oder Ende). */
        val kanalGedeckt: Int,
        /**
         * Netto-Insulin je Minute gegen das geplante Basal [U]: Bolus, SMB, Kanalzusatz, Null und Ruecknahme negativ - genau
         * das, was der Patient bekommt. Fuer Vergleiche ausserhalb des Tests (Export), etwa mit OpenAPS am selben Patienten.
         */
        val netto: DoubleArray,
        /** Die wahre Kohlenhydrat-Aufnahme je Minute [g/min] - fuer denselben Patienten in einem Vergleich (Export). */
        val khRate: DoubleArray,
        /** Das wirklich zurueckgehaltene Basal je Minute [U]. */
        val rueckVerlauf: DoubleArray,
        /** Die SMB je Minute [U] - fuer einen Vergleich je Zyklus (Export). */
        val smbVerlauf: DoubleArray,
        /** Zahl der Ruecknahme-Befehle. */
        val rueckBefehle: Int,
        /** Verletzte Grenzen (geplant oder zurueckgehalten ueber der Ueberdeckung) - muss leer sein. */
        val rueckFehler: List<String>,
        /** Minute des ersten Befehls, null = nie. */
        val rueckErsteMin: Int?,
        /** Pumpenmodell (Abschnitt 23): Anforderungen (Befehle und Abbrueche) und davon Erneuerungen bei gleicher Rate. */
        val rueckAnforderungen: Int,
        val rueckErneuerungen: Int,
        /** Minuten mit weniger Ruecknahme als gewollt (Luecke) und ungewollte Ruecknahme [U] mit ihren Minuten ohne Wunsch. */
        val rueckLueckenMin: Int,
        val rueckUngewolltU: Double,
        val rueckNachlaufMin: Int,
        /** Groesste geplante Ruecknahme ueber der Ueberdeckung in einem Zyklus [U] (0 = nie darueber). */
        val rueckUeberPlanU: Double,
    ) {
        val minBg get() = bg.min()
        val maxBg get() = bg.max()
        fun minutenUnter(s: Double) = bg.count { it < s }
        fun ueber(s: Double) = bg.count { it > s }

        /**
         * Erste Minute AUSSERHALB DES GUELTIGEN MODELLBEREICHS (Toni 05.11.), sonst null: der wahre Zucker unter
         * [MODELL_UNTEN_MGDL] oder ueber [MODELL_OBEN_MGDL]. Darunter fehlen Gegenregulation, Rettungs-KH und Sensor (LO);
         * was danach kommt, sagt nichts mehr ueber das Geraet.
         */
        val verlassenAb: Int? get() = bg.indices.firstOrNull { bg[it] < MODELL_UNTEN_MGDL || bg[it] > MODELL_OBEN_MGDL }
    }

    /**
     * TIEFVERSCHLECHTERUNG gegen die Basis (Toni 05.11.: ausdruecklich ausweisen), leer wenn keine:
     *  - der Lauf verlaesst den gueltigen Modellbereich, die Basis nicht oder spaeter;
     *  - im gemeinsamen gueltigen Fenster (bis einer der beiden den Bereich verlaesst): tiefer unter 70, mehr Minuten unter
     *    70 oder mehr Episoden.
     * Nach dem Verlassen wird nichts mehr verglichen - dort sind die Zahlen keine Aussage.
     */
    private fun tiefVerschlechterung(l: Lauf, basis: Lauf): String {
        val gruende = mutableListOf<String>()
        val la = l.verlassenAb
        val ba = basis.verlassenAb
        if (la != null && (ba == null || la < ba)) {
            gruende += "verlaesst den Modellbereich ${if (ba == null) "neu" else "frueher"} (+$la statt ${ba?.let { "+$it" } ?: "nie"})"
        }
        val ende = minOf(la ?: l.bg.lastIndex, ba ?: basis.bg.lastIndex)
        fun tiefst(x: Lauf) = (0..ende).minOf { x.bg[it] }
        fun minuten(x: Lauf) = (0..ende).count { x.bg[it] < 70.0 }
        fun episoden(x: Lauf) = (1..ende).count { x.bg[it] < 70.0 && x.bg[it - 1] >= 70.0 } + if (x.bg[0] < 70.0) 1 else 0
        if (tiefst(l) < 70.0 && tiefst(l) < tiefst(basis) - 0.5) gruende += "tiefer ${f0(tiefst(l))} statt ${f0(tiefst(basis))}"
        if (minuten(l) > minuten(basis)) gruende += "laenger unter 70 (${minuten(l)} statt ${minuten(basis)} min)"
        if (episoden(l) > episoden(basis)) gruende += "mehr Episoden (${episoden(l)} statt ${episoden(basis)})"
        val fenster = if (la != null || ba != null) " [verglichen bis +$ende]" else ""
        return if (gruende.isEmpty()) "" else gruende.joinToString("; ") + fenster
    }

    /** Die erste Minute nach dem Rechner-Bolus, in der FUSE etwas gibt (SMB oder Kanal); null = nie. */
    private fun ersteFuseGabe(l: Lauf): Int? = (1 until l.gabe.size).firstOrNull { l.gabe[it] > 0.0 }

    /**
     * DIE GETRENNTE BEWERTUNG (Toni 05.11.): Tiefs, verspaetete Versorgung und zusaetzliche Hochs je fuer sich, gegen die
     * Basis. "Verspaetet" heisst: die erste FUSE-Gabe kommt spaeter; bei [unterversorgt] (Unterbolus, unterschaetzte
     * Mahlzeit) auch weniger Insulin bis +60 oder +120. Kleine Gipfelunterschiede allein entscheiden nichts - der Gipfel
     * steht nur dabei.
     */
    private class Bewertung(val tief: String, val spaeter: Boolean, val versorgung: String, val mehrHoch: Boolean, val hochs: String) {
        val tiefSchlechter get() = tief.isNotEmpty()
        override fun toString() =
            "Tief: ${tief.ifEmpty { "nicht schlechter" }} | Versorgung: ${if (spaeter) "SPAETER/WENIGER" else "nicht spaeter"} " +
                "($versorgung) | Hochs: ${if (mehrHoch) "MEHR" else "nicht mehr"} ($hochs)"
    }

    private fun bewertung(l: Lauf, basis: Lauf, unterversorgt: Boolean): Bewertung {
        fun bis(x: Lauf, t: Int) = (0..t).sumOf { x.gabe[it] }
        val ersteL = ersteFuseGabe(l)
        val ersteB = ersteFuseGabe(basis)
        val spaeter = (ersteL ?: Int.MAX_VALUE) > (ersteB ?: Int.MAX_VALUE) ||
            (unterversorgt && (bis(l, 60) < bis(basis, 60) - 0.05 || bis(l, 120) < bis(basis, 120) - 0.05))
        val mehr = l.ueber(180.0) > basis.ueber(180.0) || l.ueber(250.0) > basis.ueber(250.0)
        return Bewertung(
            tief = tiefVerschlechterung(l, basis),
            spaeter = spaeter,
            versorgung = "erste Gabe ${ersteL?.let { "+$it" } ?: "-"} statt ${ersteB?.let { "+$it" } ?: "-"}, bis +60 " +
                "${f(bis(l, 60))} statt ${f(bis(basis, 60))} U, bis +120 ${f(bis(l, 120))} statt ${f(bis(basis, 120))} U",
            mehrHoch = mehr,
            hochs = "ueber 180 ${l.ueber(180.0)} statt ${basis.ueber(180.0)} min, ueber 250 ${l.ueber(250.0)} statt " +
                "${basis.ueber(250.0)} min, Gipfel ${f0(l.maxBg)} statt ${f0(basis.maxBg)}",
        )
    }

    private fun lauf(
        sofort: Sofort,
        wirkung: Wirkung,
        arm: Arm,
        meldung: Meldung = Meldung.WIE_GEMELDET,
        /** Beginn und Dauer der eCarbs [min] und die Laufdauer - fuer den Fall "eCarbs ab +3 h" (Toni 05.11.). */
        eBeginnMin: Int = eStartMin,
        eDauerMin: Int = eDurationMin,
        laufMin: Int = endMin,
        patient: Patient = standard,
        /** Wie Pumpe und Schleife die Ruecknahme-Befehle umsetzen. */
        technik: Technik = Technik.NORMAL,
        /**
         * NUR ALS VERGLEICH IM TEST: PRODUKT ohne die Basal-Ruecknahme - zeigt, dass die Ruecknahme vor ihrem ersten Befehl
         * nichts aendert, und was sie bringt.
         */
        ohneRuecknahme: Boolean = false,
        /**
         * Das Zerlegungs-Protokoll (Toni 05.11.): je Zyklus, was im Bedarf steht und was nicht - Bahn, Normalbedarf, IOB, die
         * Wirkung des schon abgegebenen Insulins und des Kanalplans bis und nach dem Freigabehorizont, Sperre, Schwanz,
         * G und die Grenze des SMB-Wegs, SMB und Kanal.
         */
        protokoll: StringBuilder? = null,
        /**
         * Das R-Protokoll (Codex-Pruefung, Toni 05.11.): je Zyklus G, Ueberdeckung, Wunsch oder Grund dagegen, Befehl,
         * geplante und schon zurueckgehaltene Ruecknahme, die prognostizierte Untergrenze mit Zeitpunkt und was eine Null von
         * jetzt bis dahin noch verhindert.
         */
        rProtokoll: StringBuilder? = null,
        /** Das Zeitfenster der Protokolle [min]. */
        protokollVon: Int = 0,
        protokollBis: Int = 300,
        /**
         * PUNKT 3 (Freigabe bei schneller Aufnahme): je Zyklus eine CSV-Zeile mit dem, was die Freigabe bestimmt - Bahn am
         * Freigabehorizont, Normalbedarf, IOB, G, Grenze des SMB-Wegs, SMB, Kanal und die bindende Grenze.
         */
        zyklusZeilen: StringBuilder? = null,
    ): Lauf {
        @Suppress("NAME_SHADOWING") val isf = patient.isf
        @Suppress("NAME_SHADOWING") val ic = patient.ic
        @Suppress("NAME_SHADOWING") val nowCarbsG = patient.sofortG
        @Suppress("NAME_SHADOWING") val eCarbsG = patient.eCarbsG
        @Suppress("NAME_SHADOWING") val kurve = patient.kurve
        @Suppress("NAME_SHADOWING") val phi0 = EarlySupply.hclScale(nowCarbsG / ic, kurve)
        @Suppress("NAME_SHADOWING") val eStartMin = eBeginnMin
        @Suppress("NAME_SHADOWING") val eDurationMin = eDauerMin
        @Suppress("NAME_SHADOWING") val endMin = laufMin
        val produkt = arm == Arm.PRODUKT
        val mitRuecknahme = produkt && !ohneRuecknahme
        val bolus = nowCarbsG / ic * meldung.bolusFaktor
        val sofortFaktor = meldung.sofortFaktor
        val bg = DoubleArray(endMin + 1)
        bg[0] = startBg
        // Sensor und Beobachter (Korrektur 2): Verzug erster Ordnung, Rauschen, UKF je Minute, Aktivitaet fuer die
        // BGI-bereinigte Reihe. Entschieden wird auf q1 und dem UKF-Trend, gemessen wird der wahre Zucker.
        val sensor = DoubleArray(endMin + 1)
        val q1 = DoubleArray(endMin + 1)
        val ukfRate = DoubleArray(endMin + 1)
        val aktivitaet = DoubleArray(endMin + 1)
        var verzug = startBg
        // Alles Insulin netto (Bolus, SMB, Kanalzusatz, Null und Ruecknahme als negatives Basal) - und der Bolusanteil
        // allein, den das Low-Tor liest (FuseCycleRunner: "NUR der Bolusanteil").
        val dosen = mutableListOf(0 to bolus)
        val bolusDosen = mutableListOf(0 to bolus)
        var nullBis = -1
        var nullMin = 0
        val gabe = DoubleArray(endMin + 1)
        gabe[0] = bolus
        val angekuendigt = ECarbs.parts(eCarbsG.toDouble(), t0 + eStartMin * minMs, eDurationMin)
        // Die wahren Teile: Anteil, Menge und Versatz; dazu der Abstand (LANGSAM) und die Pause vor der zweiten Haelfte
        // (ZWEITE_WELLE). Mit Abstand 1 und ohne Pause sind es genau die angekuendigten Zeitpunkte plus Versatz.
        val wahr = angekuendigt.take(ceil(angekuendigt.size * wirkung.anteil - 1e-9).toInt()).mapIndexed { i, p ->
            val abstandMs = (i * ECarbs.PART_MIN * wirkung.abstandFaktor * minMs).toLong()
            val pauseMs = if (i >= angekuendigt.size / 2) wirkung.pauseMin * minMs else 0L
            ECarbs.Part(t0 + eStartMin * minMs + abstandMs + pauseMs + wirkung.versatzMin * minMs, p.grams * wirkung.faktor)
        }
        var block: Block? = null
        var gedaechtnis = SlowCorrectionChannel.Memory()
        var cobZuordnung = ECarbs.CobAttribution()
        var fensterBis = -1
        var smbSumme = 0.0
        var kanalSumme = 0.0
        var kanalGedeckt = 0
        // ---- DIE RUECKNAHME MIT PUMPENMODELL (Abschnitt 23, Codex-Pruefung Toni 05.11.): angefordert, ausstehend und
        // laufend getrennt. Ein Befehl ist eine Absenkung fuer 30 min und nimmt hoechstens seine Grenze weg. Die Pumpe gibt
        // ab, was LAEUFT; eine Anforderung wird erst bei ihrer Ausfuehrung wirksam (oder nie, wenn sie scheitert), bis dahin
        // laeuft der alte Befehl weiter. Die Schleife kennt einen Befehl erst nach der Meldung; bis dahin plant sie mit
        // ihrer letzten Anforderung.
        class RueckBefehl(val id: Int, val rateUPerH: Double, val startMin: Int, val endMin: Int, val grenzeU: Double)
        /** Eine Anforderung an die Pumpe: [ziel] null heisst Abbruch. */
        class Anforderung(val ziel: RueckBefehl?, val ausfuehrungMin: Int, val meldungMin: Int, val scheitert: Boolean) {
            var bearbeitet = false
        }
        var laufend: RueckBefehl? = null
        var bekannt: RueckBefehl? = null
        var ausstehend: Anforderung? = null
        var anforderungen = 0
        var erneuerungen = 0
        // Der zuletzt ausgefuehrte Ruecknahme-Befehl - um Erneuerungen bei gleicher Rate zu erkennen, auch direkt nach dem
        // Ablauf.
        var letzterBefehl: RueckBefehl? = null
        fun entzug(b: RueckBefehl?, von: Int, bis: Int) =
            b?.let { (basalUPerH - it.rateUPerH) / 60.0 * max(0, minOf(bis, it.endMin) - max(von, it.startMin)) } ?: 0.0
        // Was die Schleife jetzt fuer laufend haelt: eine ausstehende Anforderung, deren Ausfuehrung faellig ist, sonst den
        // zuletzt bestaetigten Befehl.
        fun geglaubt(m: Int): RueckBefehl? {
            val a = ausstehend
            val b = if (a != null && a.ausfuehrungMin <= m) a.ziel else bekannt
            return b?.takeIf { it.endMin > m }
        }
        // Die geplante Ruecknahme ab m: der geglaubte Befehl bis zur Ausfuehrung der ausstehenden Anforderung, danach diese.
        fun geplant(m: Int): Double {
            val a = ausstehend
            return if (a == null || a.ausfuehrungMin <= m) entzug(geglaubt(m), m, Int.MAX_VALUE)
            else entzug(bekannt, m, a.ausfuehrungMin) + entzug(a.ziel, a.ausfuehrungMin, Int.MAX_VALUE)
        }
        // Ein Pumpenschritt in Minute m: faellige Anforderung ausfuehren (oder scheitern lassen), faellige Meldung an die
        // Schleife, abgelaufene Befehle beenden. Er laeuft zu Beginn jeder Minute und direkt nach jeder Ruecknahme-Entscheidung.
        fun pumpeSchritt(m: Int) {
            val a = ausstehend
            if (a != null && !a.bearbeitet && m >= a.ausfuehrungMin) {
                a.bearbeitet = true
                if (!a.scheitert) {
                    laufend = a.ziel
                    a.ziel?.let { letzterBefehl = it }
                }
            }
            if (a != null && a.bearbeitet && m >= a.meldungMin) {
                if (!a.scheitert) bekannt = laufend
                ausstehend = null
            }
            if (laufend?.let { m >= it.endMin } == true) laufend = null
            if (bekannt?.let { m >= it.endMin } == true) bekannt = null
        }
        // Was die Schleife gerade will: die Rate der gewuenschten Absenkung, null = keine.
        var gewollt: Double? = null
        var luecken = 0
        var ungewolltU = 0.0
        var nachlaufMin = 0
        var ueberPlanMax = 0.0
        val rueckVerlauf = DoubleArray(endMin + 1)
        val smbVerlauf = DoubleArray(endMin + 1)
        val rueckJeBefehl = mutableMapOf<Int, Double>()
        val rueckGrenzen = mutableMapOf<Int, Double>()
        var rueckBefehle = 0
        var rueckErste: Int? = null
        val rueckFehler = mutableListOf<String>()
        var ausfallBis = -1

        fun insulinRate(m: Int) = dosen.sumOf { (td, u) -> u * act((m - td).toDouble()) }
        fun carbRate(m: Int): Double {
            val sofortG = nowCarbsG * sofortFaktor * gammaRate(m.toDouble(), sofort.tc)
            val spaet = wahr.sumOf { p ->
                val x = (t0 + m * minMs - p.ts) / 60_000.0
                if (x > 0.0 && x <= ECarbs.ABSORPTION_MIN) p.grams / ECarbs.ABSORPTION_MIN else 0.0
            }
            return sofortG + spaet
        }

        for (m in 0..endMin) {
            if (m > 0) bg[m] = bg[m - 1] + isf / ic * carbRate(m) - isf * insulinRate(m)
            if (m > 0) verzug += (bg[m] - verzug) / sensorVerzugMin
            sensor[m] = verzug + rauschen[m]
            val ukf = UkfQ1.leadingEdge((max(0, m - UkfQ1.WINDOW_SAMPLES + 1)..m).map { UkfQ1.Point(t0 + it * minMs, sensor[it]) })!!
            q1[m] = ukf.glucose
            ukfRate[m] = if (ukf.rateUnavailable) 0.0 else ukf.ratePerMin
            aktivitaet[m] = insulinRate(m)
            // Was in dieser Minute bei der Pumpe faellig ist (Ausfuehrung, Meldung, Ablauf), vor jeder Entscheidung.
            if (mitRuecknahme) pumpeSchritt(m)
            // Technik AUSFALL: in den 30 min nach dem ersten Befehl laufen keine Zyklen.
            if (m % cycleMin == 0 && m >= ausfallBis) {
                val nowTs = t0 + m * minMs
                // Der Antrieb wie im Runner: Theil-Sen-Spanne der BGI-bereinigten q1-Reihe; ohne Schaetzung (erster
                // Zyklus) flach.
                val schaetzung = PairSlopeBand.estimate(
                    BgiAdjustedSeries.adjust((max(0, m - 20)..m).map { BgiAdjustedSeries.Sample(t0 + it * minMs, q1[it], aktivitaet[it], isf) }).points,
                    nowTs, antriebQuantilPct, theilSenFensterMin * minMs,
                )
                val r = schaetzung?.mean ?: 0.0
                val rUnten = schaetzung?.lower ?: 0.0
                val trend = ukfRate[m]
                val extra = block?.let { max(0.0, it.rateUPerH - basalUPerH) / 60.0 } ?: 0.0
                val geplant = block?.let { b -> (m until b.endMin).map { it to extra } } ?: emptyList()
                val p = predict(nowTs, q1[m], r, rUnten, dosen, geplant, m, isf)
                val iob = dosen.sumOf { (td, u) -> u * iobFrac((m - td).toDouble()) }
                val bolusIob = bolusDosen.sumOf { (td, u) -> u * iobFrac((m - td).toDouble()) }
                // Das Mahlzeitfenster wie im Runner ("Fenster-Trio"): Marker-Boost in den ersten 45 min oder Kinematik (r und
                // UKF-Trend ab der unteren Rampengrenze), 10 min rollierend; ein deutlicher Fall schliesst. Ohne offenes Fenster
                // gilt im Kern nur die Korrektur-Ratio, nie die Rampe.
                val boost = m < 45
                if (boost || (r >= rampeUnten && trend >= rampeUnten)) fensterBis = m + 10
                if (trend <= -rampeUnten) fensterBis = m
                val s = state(r, iob, bolusIob, mahlzeitFenster = m < fensterBis, boost = boost, isf = isf)
                // Das Low-Tor wie im Runner: gemessenes Tief, oder gemessen fallend mit Bolus-Ueberdeckung und Nutzen.
                val tief = LowThreatGate.evaluate(
                    measuredLow = q1[m] < 70.0, signalHealthy = true, bgMgdl = q1[m], fallRatePerMin = trend,
                    bolusIobU = bolusIob, isfMgdlPerU = isf, guardFloorMgdl = limits.guardFloorMgdl,
                    scheduledBasalUPerH = basalUPerH, remainingEffect = { t -> EarlySupply.orefActionFraction(t, peak, dia) },
                ).verdict
                // Beobachtete Mahlzeitwirkung seit dem Marker [U-Aeq] - die Stoerung des Kontos und der eCarbs-Zuordnung.
                val beobachtet = (q1[m] - q1[0]) / isf + dosen.sumOf { (td, u) -> u * (1.0 - iobFrac((m - td).toDouble())) }
                val hclSkala = EarlySupply.hclGuardScale(phi0, m.toDouble(), beobachtet, true, kurve)
                // Die HCL-Fortsetzung wie im Runner (ekFortsetzung): solange die Mahlzeit laeuft (r >= Mahlzeit-laeuft-
                // Schwelle; ohne eigene Einstellung die untere Rampengrenze), kommt jenseits des Kurvenendes die beobachtete
                // Fortsetzung hinzu.
                val hclFortsetzung = if (r > 0.0 && r >= rampeUnten) EarlySupply.Continuation(r / isf, 60.0) else null
                val hclHebung = { tauMin: Int ->
                    EarlySupply.expectedRiseMgdl(kurve, horizon, m.toDouble(), tauMin.toDouble(), hclSkala, isf, hclFortsetzung)
                }
                val planU = geplant.sumOf { it.second }
                val planAmH = geplant.sumOf { (k, u) -> u * iobFrac((m + horizon - k).toDouble()) }
                val iobAmH = max(
                    dosen.sumOf { (td, u) -> u * iobFrac((m + horizon - td).toDouble()) },
                    bolusDosen.sumOf { (td, u) -> u * iobFrac((m + horizon - td).toDouble()) },
                )
                val io = EarlySupply.insulinOnly(p).last()
                // DIE BILANZ G (Produkt): im Kontofenster mit angekuendigtem Rest (Sofort-KH nach der Kurve, eCarbs wie COB mit
                // Verfall mal w), danach ohne - solange Insulin der Mahlzeit wirkt (Episoden-IOB ueber einem Pumpenschritt).
                val episodenIob = dosen.sumOf { (td, u) -> if (td >= 0 && u > 0.0) u * iobFrac((m - td).toDouble()) else 0.0 }
                val bilanz = if (!produkt) null else MealBalance.cycle(
                    MealBalance.Input(
                        nowTs = nowTs, minutesSinceMarker = m.toDouble(), windowMin = kontoFensterMin.toDouble(),
                        immediateCarbsU = nowCarbsG / ic, phi0 = phi0, curve = kurve, observedU = beobachtet,
                        eCarbs = angekuendigt, icGPerU = ic, bgMgdl = q1[m], targetMgdl = 100.0, isfMgdlPerU = isf,
                        driveMgdlPerMin = r, driveTauMin = antriebTauMin, iobU = iob, transportU = 0.0, episodeIobU = episodenIob,
                    ),
                    cobZuordnung,
                )
                if (bilanz != null) cobZuordnung = bilanz.attribution
                val g = bilanz?.balanceU
                val grenzeSmb = MealBalance.smbCapU(g, planU)
                val schwanz = TailLiability.evaluate(
                    TailLiability.Input(
                        lowerBgAtH = EarlySupply.liftedEdge(p.bgAtHorizonSafetyLower, io, hclHebung(horizon)),
                        existingIobAtH = iobAmH, isfTailMgdlPerU = isf, tailFloorMgdl = tailFloor, tailRecoveryU = 0.0,
                        transport = TailLiability.Dose(planU, planAmH),
                    ),
                )
                val guard = if (p.points.any { hclHebung(it.offsetMin) > 0.0 }) {
                    EarlySupply.withExpectation(p, m.toDouble(), kurve, horizon, hclSkala, isf, hclFortsetzung)
                } else {
                    null
                }
                val entscheidung = decide(s, p, schwanz, guard, tief, grenzeSmb)
                val smb = entscheidung.smbU
                if (entscheidung.tbr == FuseController.TbrAction.ZERO_TEMP) nullBis = m + cycleMin
                if (smb > 0.0) {
                    dosen += m to smb
                    bolusDosen += m to smb
                    gabe[m] += smb
                    smbSumme += smb
                    smbVerlauf[m] = smb
                }
                // ---- der langsame Kanal: Bedarf VOR den Sperren; im Produkt mit G als Luecke --------------------------------
                val normal = FuseController.normalDemandU(
                    p.points.first { it.offsetMin == limits.releaseHorizonMin }.meanBg, 100.0, isf,
                )
                val schritt = SlowCorrectionChannel.step(
                    kanal,
                    SlowCorrectionChannel.Input(
                        nowTs = nowTs, q1Mgdl = q1[m], q1EntryAgoMgdl = null, trendMgdlPerMin = trend,
                        iobU = max(iob, bolusIob), maxIobU = maxIob, scheduledBasalUPerH = basalUPerH, basalStepUPerH = 0.05,
                        hardStop = if (entscheidung.tbr == FuseController.TbrAction.ZERO_TEMP) "zero" else null,
                        smbThisCycleU = smb, demandU = FuseController.jointDemandU(normal, null), plannedRemainderU = planU,
                        active = block != null, runningRateUPerH = block?.rateUPerH, runningRemainingMin = block?.let { it.endMin - m },
                        coverageGapU = g,
                    ),
                    gedaechtnis,
                )
                if (schritt.reason == SlowCorrectionChannel.Reason.COVERED || schritt.detail?.endsWith("gedeckt") == true) kanalGedeckt++
                gedaechtnis = schritt.memory
                zyklusZeilen?.append(
                    "$m;${bg[m]};${q1[m]};$r;${p.points.first { it.offsetMin == limits.releaseHorizonMin }.meanBg};$normal;" +
                        "$iob;$bolusIob;${g ?: ""};${grenzeSmb ?: ""};$smb;${if (schritt.want) schritt.rateUPerH else 0.0};" +
                        "${entscheidung.bindingLimit};${entscheidung.block.name};${s.effectiveSmbRatio}\n",
                )
                // ---- ZERLEGUNGS-PROTOKOLL (Toni 05.11.): was steht im Bedarf, was nicht? -----------------------------------
                if (protokoll != null && m in protokollVon..protokollBis) {
                    val rel = limits.releaseHorizonMin
                    // Wirkung des schon abgegebenen Insulins [mg/dl]: bis zum Freigabehorizont (steht in der Bahn und damit
                    // im Bedarf) und danach (steht NICHT im Bedarf, nur Guard/Schwanz und G sehen sie).
                    val bis45 = dosen.sumOf { (td, u) -> u * (iobFrac((m - td).toDouble()) - iobFrac((m + rel - td).toDouble())) } * isf
                    val nach45 = dosen.sumOf { (td, u) -> u * iobFrac((m + rel - td).toDouble()) } * isf
                    val planBis45 = geplant.sumOf { (k, u) -> u * (1.0 - iobFrac((m + rel - k).toDouble())) } * isf
                    val planNach45 = geplant.sumOf { (k, u) -> u * iobFrac((m + rel - k).toDouble()) } * isf
                    protokoll.append(
                        "\n  +$m | ${f0(q1[m])} (${f0(bg[m])}) | ${f(r)} | ${f0(p.points.first { it.offsetMin == rel }.meanBg)} | " +
                            "${f(normal)} | ${f(iob)} | ${f0(bis45)}/${f0(nach45)} | ${f0(planBis45)}/${f0(planNach45)} | " +
                            "${entscheidung.block.name.take(9)} ${entscheidung.minLowerMgdl?.let { f0(it) } ?: "-"} | " +
                            "${f(schwanz.headroomU)} | ${g?.let { f(it) } ?: "-"} | ${grenzeSmb?.let { f(it) } ?: "-"} | ${f(smb)} | " +
                            "${if (schritt.want) f(schritt.rateUPerH) else "-"} ${schritt.reason.name.take(8)}" +
                            (bilanz?.let { " | ${f(it.immediateRestU)} ${f(it.eCarbsRestU)} ${f(it.confirmation)} ${f(it.weight)}" } ?: ""),
                    )
                }
                block = when {
                    !schritt.want -> null
                    block == null || schritt.reason == SlowCorrectionChannel.Reason.START ||
                        block.rateUPerH != schritt.rateUPerH || block.endMin - m <= SlowCorrectionChannel.RENEW_REMAINING_MIN ->
                        Block(schritt.rateUPerH, m, m + SlowCorrectionChannel.BLOCK_MIN)
                    else -> block
                }
                // ---- DIE RUECKNAHME (Abschnitte 22 bis 24): der Wunsch aus dem Produktcode, die Pumpe spielt der Pruefstand ---
                // KANDIDAT, kein belegtes Mahlzeitende: G < 0 und r unter der unteren Rampengrenze. Die Ueberdeckung kommt aus G
                // (schon zurueckgehaltenes Basal steht dort negativ). Ein Befehl nimmt hoechstens die Ueberdeckung, abzueglich
                // dessen, was der geglaubte laufende Befehl bis zur Ausfuehrung noch nimmt. Erneuert wird auch bei gleicher
                // Rate, bevor der Befehl ablaeuft. Faellt der Wunsch weg, fordert FUSE einen Abbruch an - nur fuer die
                // Ruecknahme; Schutz-Null und Kanal sind unabhaengig und haben Vorrang.
                if (mitRuecknahme) {
                    val ausfuehrung = m + technik.ausfuehrungMin
                    fun anfordern(ziel: RueckBefehl?) {
                        anforderungen++
                        val scheitert = when (technik) {
                            Technik.BEFEHL_FEHLT -> anforderungen % 2 == 0
                            Technik.ABBRUCH_FEHLT -> ziel == null
                            else -> false
                        }
                        ausstehend = Anforderung(ziel, ausfuehrung, m + technik.meldungMin, scheitert)
                        if (ziel != null) {
                            rueckGrenzen[ziel.id] = ziel.grenzeU
                            if (rueckErste == null) rueckErste = m
                            if (technik == Technik.AUSFALL && ausfallBis < 0) ausfallBis = m + 30
                        }
                    }
                    val jetzt = geglaubt(m)
                    // Das letzte Ziel der Schleife: die ausstehende Anforderung (Abbruch = keins) oder der geglaubte Befehl.
                    val ziel: RueckBefehl? = when (val a = ausstehend) {
                        null -> jetzt
                        else -> a.ziel?.takeIf { it.endMin > m }
                    }
                    val wunsch = MealBalance.withdrawal(
                        MealBalance.WithdrawalInput(
                            balanceU = g, driveMgdlPerMin = r, rampLowMgdlPerMin = rampeUnten, channelActive = block != null,
                            protectiveZeroActive = m < nullBis, scheduledBasalUPerH = basalUPerH, basalStepUPerH = 0.05,
                            withdrawnBeforeExecutionU = entzug(jetzt, m, ausfuehrung),
                        ),
                    )
                    val ueber = wunsch.overcoverageU
                    val rate = wunsch.rateUPerH
                    if (rate == null) {
                        gewollt = null
                        if (ziel != null) anfordern(null)
                    } else {
                        gewollt = rate
                        // Gleiche Rate am Blockende: erneuern, solange der Befehl noch laeuft, nicht erst danach. Ohne
                        // Ausfuehrungsverzug laeuft er zu Beginn seiner Endminute ab, und die neue Anforderung wird in derselben
                        // Minute vor der Abgabe ausgefuehrt - auch das ist eine Erneuerung ohne Luecke.
                        val erneuern = ziel != null && ziel.rateUPerH == rate && ziel.endMin - m < cycleMin + technik.ausfuehrungMin
                        val nachAblauf = ziel == null && letzterBefehl?.let { it.rateUPerH == rate && it.endMin in (m - cycleMin + 1)..m } == true
                        if (ziel == null || ziel.rateUPerH != rate || erneuern || geplant(m) > ueber + 1e-9) {
                            if (erneuern || nachAblauf) erneuerungen++
                            rueckBefehle++
                            anfordern(RueckBefehl(rueckBefehle, rate, ausfuehrung, ausfuehrung + MealBalance.COMMAND_MIN, wunsch.limitU))
                        }
                    }
                    pumpeSchritt(m)
                    // Die Grenze im Zyklus: was die Schleife geplant hat, gegen die Ueberdeckung. Ohne Verzug hart; mit spaeter
                    // Ausfuehrung darf nur der Rest des alten Befehls bis zur Ausfuehrung darueber liegen; bei Fehlschlaegen
                    // ein Befund.
                    val zuViel = geplant(m) - ueber
                    ueberPlanMax = max(ueberPlanMax, zuViel)
                    val erlaubt = when (technik) {
                        Technik.NORMAL, Technik.BESTAETIGUNG_SPAET, Technik.AUSFALL -> 1e-9
                        Technik.AUSFUEHRUNG_SPAET -> basalUPerH * technik.ausfuehrungMin / 60.0 + 1e-9
                        Technik.BEFEHL_FEHLT, Technik.ABBRUCH_FEHLT -> Double.MAX_VALUE
                    }
                    if (zuViel > erlaubt) rueckFehler += "+$m: geplant ${f(geplant(m))} U ueber der Ueberdeckung ${f(ueber)} U"
                    if (rProtokoll != null && m in protokollVon..protokollBis) {
                        // Die prognostizierte Untergrenze und ihr Zeitpunkt; was eine Null von jetzt bis dahin noch verhindert.
                        val bisTief = entscheidung.timeToMinCombinedMin
                        val nutzen = bisTief?.let {
                            LowThreatGate.nutzenMgdl(it.toDouble(), basalUPerH, isf) { t -> EarlySupply.orefActionFraction(t, peak, dia) }
                        }
                        fun befehl(b: RueckBefehl?) = b?.let { "${f(it.rateUPerH)} bis +${it.endMin}" } ?: "-"
                        rProtokoll.append(
                            "\n  +$m | ${f0(bg[m])} | ${f0(q1[m])} | ${f(r)} | ${f(trend)} | ${g?.let { f(it) } ?: "-"} | ${f(ueber)} | " +
                                "${wunsch.reason?.name ?: "ja"} | ${gewollt?.let { f(it) } ?: "-"} | ${befehl(laufend)} | ${befehl(bekannt)} | " +
                                "${ausstehend?.let { a -> "${a.ziel?.let { f(it.rateUPerH) } ?: "Abbruch"} aus +${a.ausfuehrungMin} meld +${a.meldungMin}" +
                                    if (a.scheitert) " SCHEITERT" else "" } ?: "-"} | " +
                                "${f(geplant(m))} | ${f(rueckVerlauf.sum())} | " +
                                "${entscheidung.minLowerMgdl?.let { f0(it) } ?: "-"}${bisTief?.let { " (+$it)" } ?: ""} | " +
                                "${nutzen?.let { f0(it) } ?: "-"} | ${if (m < nullBis) "Schutz-Null" else "-"}",
                        )
                    }
                }
            }
            // ---- die Null nimmt in dieser Minute das Basal weg (negatives Netto-Insulin) ------------------------------
            // Die Ruecknahme teilt sich die Pumpe mit Schutz-Null und Kanal: setzen diese ihre TBR, ist der Ruecknahme-Befehl
            // ersetzt - auch eine noch ausstehende Anforderung (der neuere Befehl gilt). Beide Ruecknahmen addieren sich nicht.
            val kanalAktiv = block?.let { m >= it.startMin && m < it.endMin && it.rateUPerH > basalUPerH } == true
            if (mitRuecknahme && (m < nullBis || kanalAktiv)) {
                laufend = null
                bekannt = null
                ausstehend = null
            }
            if (m < nullBis) {
                dosen += m to -basalUPerH / 60.0
                nullMin++
            } else if (!kanalAktiv) {
                laufend?.let { b ->
                    if (m >= b.startMin && m < b.endMin && b.rateUPerH < basalUPerH) {
                        val u = (basalUPerH - b.rateUPerH) / 60.0
                        dosen += m to -u
                        rueckVerlauf[m] = u
                        rueckJeBefehl.merge(b.id, u, Double::plus)
                    }
                }
            }
            // Gewollt gegen wirklich. Weniger als gewollt ist eine Luecke (z.B. ein abgelaufener, nicht erneuerter Befehl),
            // mehr ist ungewollte Ruecknahme (z.B. ein Abbruch, der noch nicht oder nie ankam).
            if (mitRuecknahme) {
                val soll = if (m < nullBis || kanalAktiv) 0.0 else gewollt?.let { (basalUPerH - it) / 60.0 } ?: 0.0
                val ist = rueckVerlauf[m]
                if (ist > soll + 1e-12) {
                    ungewolltU += ist - soll
                    if (soll == 0.0) nachlaufMin++
                }
                if (soll > 0.0 && ist + 1e-12 < soll) luecken++
            }
            // ---- die TBR gibt in dieser Minute ihren Zusatz ab --------------------------------------------------------
            block?.let { b ->
                if (m >= b.startMin && m < b.endMin) {
                    val u = max(0.0, b.rateUPerH - basalUPerH) / 60.0
                    if (u > 0.0) {
                        dosen += m to u
                        gabe[m] += u
                        kanalSumme += u
                    }
                }
            }
        }
        val netto = DoubleArray(endMin + 1)
        for ((td, u) in dosen) netto[td] += u
        // Die Grenze je Befehl: was waehrend eines Befehls wirklich zurueckgehalten wurde, bleibt unter seiner Grenze - auch
        // ohne Folgezyklen und mit spaeter Bestaetigung.
        for ((id, u) in rueckJeBefehl) {
            val grenze = rueckGrenzen.getValue(id)
            if (u > grenze + 1e-9) rueckFehler += "Befehl $id: ${f(u)} U zurueckgehalten, Grenze ${f(grenze)} U"
        }
        // Ohne Verzug und Fehlschlag muss die Pumpe genau das tun, was die Schleife will: keine Luecke (auch nicht am
        // Blockende bei gleicher Rate) und keine ungewollte Ruecknahme (auch nicht nach einem Abbruch).
        if (mitRuecknahme && technik == Technik.NORMAL) {
            if (luecken > 0) rueckFehler += "NORMAL: $luecken Minuten weniger Ruecknahme als gewollt"
            if (ungewolltU > 1e-9) rueckFehler += "NORMAL: ${f(ungewolltU)} U ungewollte Ruecknahme"
        }
        return Lauf(
            bg = bg, gabe = gabe, smbU = smbSumme, kanalU = kanalSumme, nullMin = nullMin, kanalGedeckt = kanalGedeckt,
            netto = netto, khRate = DoubleArray(endMin + 1) { carbRate(it) },
            rueckVerlauf = rueckVerlauf, smbVerlauf = smbVerlauf, rueckBefehle = rueckBefehle, rueckFehler = rueckFehler, rueckErsteMin = rueckErste,
            rueckAnforderungen = anforderungen, rueckErneuerungen = erneuerungen, rueckLueckenMin = luecken,
            rueckUngewolltU = ungewolltU, rueckNachlaufMin = nachlaufMin, rueckUeberPlanU = ueberPlanMax,
        )
    }

    private fun predict(
        nowTs: Long, bgNow: Double, r: Double, rUnten: Double,
        dosen: List<Pair<Int, Double>>, geplant: List<Pair<Int, Double>>, m0: Int, isf: Double,
    ): PredictorResult {
        val pts = (0..horizon + 60).map { j ->
            val t = m0 + j
            IobPoint(
                nowTs + j * minMs,
                dosen.sumOf { (td, u) -> u * iobFrac((t - td).toDouble()) } +
                    geplant.sumOf { (k, u) -> if (t >= k) u * iobFrac((t - k).toDouble()) else 0.0 },
                dosen.sumOf { (td, u) -> u * act((t - td).toDouble()) } + geplant.sumOf { (k, u) -> u * act((t - k).toDouble()) },
                0.0,
            )
        }
        // Wie im Runner ohne Marker-Prior: Untergrenze aus der Spanne, die prior-freie Kante ist dieselbe.
        val o = TrajectoryCore.predict(
            PredictorInput(
                predictionAnchorTs = nowTs,
                bgAtAnchor = bgNow,
                drive = DriveEstimate(r, rUnten, 0.8, "pairSlopeBand", rUnten),
                decay = DriveDecayModel.ExponentialDecay(antriebTauMin),
                trajectory = VirtualTrajectoryFactory.of(
                    lineage = InsulinLineage.VirtualController("sim", 1L, "m"),
                    points = pts, arrayAsOfTs = nowTs,
                    model = InsulinModelProvenance("SIM", dia / 60.0, peak.toInt(), "t"),
                    iobCalculationHash = "h",
                ),
                isfSlots = listOf(IsfSlot(nowTs - 3_600_000L, nowTs + 12 * 3_600_000L, isf)),
                horizonMin = horizon,
            )
        )
        assertTrue(o is PredictorOutcome.Ok, "abgelehnt: $o")
        return (o as PredictorOutcome.Ok).result
    }

    private fun state(r: Double, iob: Double, bolusIob: Double, mahlzeitFenster: Boolean, boost: Boolean, isf: Double) = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.REARMING,
        netIobU = iob, bolusIobU = bolusIob, basalIobU = iob - bolusIob,
        iobThU = 8.0, maxIobU = maxIob, targetMgdl = 100.0, isfMgdlPerU = isf,
        smbRatioCorrection = 0.15, smbRatioRise = 0.35,
        rSignedMgdlPerMin = r, riseRampLowRPerMin = rampeUnten, riseRampHighRPerMin = rampeOben,
        pumpIncrementU = 0.05, maxSmbU = 0.3, pumpBusy = false, mealWindow = mahlzeitFenster, markerBoost = boost,
    )

    private fun decide(
        s: FuseController.State, p: PredictorResult, tail: TailLiability.Report, guard: PredictorResult?,
        tief: LowThreatGate.Verdict, grenze: Double?,
    ) = FuseController.decide(
        s, p, limits = limits, tail = tail, evidenceCreditActive = false, evidenceMayOverrideRebound = false,
        lowThreat = tief, guardPrediction = guard, balanceCapU = grenze,
    )

    private fun f(v: Double) = "%.2f".format(Locale.ROOT, v)
    private fun f0(v: Double) = "%.0f".format(Locale.ROOT, v)

    @Test
    fun `die Erwartungskurve der Sofort-KH ist gueltig und rechnet die Mahlzeit hoechstens zur Standardkurve an`() {
        assertEquals(referenceCurveEndU, kurve.endU, 1e-9)
        assertEquals(nowCarbsG / ic / referenceCurveEndU, phi0, 1e-9)
        // Vor +20 min nichts, danach hoechstens die Kurve (Skalierer der Anrechnung <= 1).
        assertEquals(0.0, EarlySupply.hclGuardScale(phi0, 15.0, 9.0, true, kurve), 0.0)
        assertEquals(1.0, EarlySupply.hclGuardScale(phi0, 60.0, 9.0, true, kurve), 0.0)
    }

    /**
     * DAS PRODUKT GEGEN HEUTE (Entwurf B, Abschnitt 24; Toni 05.11.: Grundfunktion ohne Schalter und ohne Varianten): die
     * Bilanz G mit eCarbs-Rest fuer SMB-Weg und Kanal und die Basal-Ruecknahme, in 64 Faellen - Sofort-KH schneller, wie die
     * Kurve und langsamer; eCarbs wie angekuendigt, keine, halb, spaet, mehr, Abbruch, langsam und in zwei Wellen;
     * Unterbolus, unterschaetzte Mahlzeit, beides und leicht zu viel Bolus; dazu die grosse Mahlzeit mit Spaetwirkung
     * (Pizza). Pumpe und Schleife (Abschnitt 23): spaete Bestaetigung, spaete Ausfuehrung, scheiternde Befehle und
     * Abbrueche, ausbleibende Zyklen - in allen Faellen.
     *
     * HART geprueft: kein Tief schlechter als heute (ohne Technikfehler); die Grenzen der Ruecknahme (geplant und
     * zurueckgehalten je Befehl hoechstens die Ueberdeckung) in jeder Technik; ohne Technikfehler keine Luecke und keine
     * ungewollte Ruecknahme; vor dem ersten Befehl Minute fuer Minute dasselbe wie ohne Ruecknahme. Versorgung, Hochs und
     * die Technikfehler gegen heute sind ein Befund. Mit ECARBS_EXPORT_DIR schreibt der Test jeden Fall und die Protokolle
     * als Dateien, fuer Vergleiche ausserhalb des Tests.
     */
    @Test
    fun `Rechner-Mahlzeit mit Bilanz und Basal-Ruecknahme - Produkt gegen heute`() {
        val ziel = System.getenv("ECARBS_EXPORT_DIR")?.let { File(it) }?.also { it.mkdirs() }
        class Fall(
            val name: String, val patient: Patient, val beginn: Int, val bolusU: Double, val unterversorgt: Boolean,
            val lauf: (arm: Arm, technik: Technik, ohneRuecknahme: Boolean, protokoll: StringBuilder?, rProtokoll: StringBuilder?, zyklen: StringBuilder?) -> Lauf,
        )
        val faelle = mutableListOf<Fall>()
        val gegenproben = listOf(Wirkung.WIE_ANGEKUENDIGT, Wirkung.KEINE, Wirkung.SPAET, Wirkung.HALB, Wirkung.ABBRUCH)
        val neu = listOf(Wirkung.LANGSAM, Wirkung.ZWEITE_WELLE)
        for (so in Sofort.entries) for (w in gegenproben + Wirkung.MEHR + neu) {
            faelle += Fall("+60/$so/$w", standard, eStartMin, standard.sofortG / standard.ic, w == Wirkung.MEHR) { a, t, o, p, rp, zz ->
                lauf(so, w, a, technik = t, ohneRuecknahme = o, protokoll = p, rProtokoll = rp, protokollVon = 0, protokollBis = endMin, zyklusZeilen = zz)
            }
        }
        for (so in listOf(Sofort.WIE_KURVE, Sofort.LANGSAMER)) for (w in gegenproben + neu) {
            faelle += Fall("+180/$so/$w", standard, 180, standard.sofortG / standard.ic, false) { a, t, o, p, rp, zz ->
                lauf(
                    so, w, a, eBeginnMin = 180, eDauerMin = 180, laufMin = 600, technik = t, ohneRuecknahme = o, protokoll = p,
                    rProtokoll = rp, protokollVon = 0, protokollBis = 600, zyklusZeilen = zz,
                )
            }
            faelle += Fall("Pizza+180/$so/$w", pizza, 180, pizza.sofortG / pizza.ic, false) { a, t, o, p, rp, zz ->
                lauf(
                    so, w, a, eBeginnMin = 180, eDauerMin = 180, laufMin = 600, patient = pizza, technik = t, ohneRuecknahme = o,
                    protokoll = p, rProtokoll = rp, protokollVon = 0, protokollBis = 600, zyklusZeilen = zz,
                )
            }
        }
        for (me in listOf(Meldung.UNTERBOLUS, Meldung.UNTERSCHAETZT, Meldung.BEIDES, Meldung.LEICHT_UEBER)) for (so in Sofort.entries) {
            faelle += Fall("$me/$so", standard, eStartMin, standard.sofortG / standard.ic * me.bolusFaktor, me != Meldung.LEICHT_UEBER) { a, t, o, p, rp, zz ->
                lauf(
                    so, Wirkung.WIE_ANGEKUENDIGT, a, meldung = me, technik = t, ohneRuecknahme = o, protokoll = p, rProtokoll = rp,
                    protokollVon = 0, protokollBis = endMin, zyklusZeilen = zz,
                )
            }
        }
        // Die Faelle mit Protokollen: die Pizza-Referenz, die Gegenproben, die kleine Ueberdeckung und der schnelle Anstieg.
        // Die Technik-Proben laufen in allen Faellen; ausfuehrlich berichtet werden die Pizza-Faelle.
        val mitProtokoll = setOf(
            "Pizza+180/LANGSAMER/WIE_ANGEKUENDIGT", "Pizza+180/LANGSAMER/SPAET", "Pizza+180/LANGSAMER/ABBRUCH",
            "Pizza+180/LANGSAMER/LANGSAM", "Pizza+180/LANGSAMER/ZWEITE_WELLE", "+60/WIE_KURVE/LANGSAM", "+60/WIE_KURVE/ZWEITE_WELLE",
            "LEICHT_UEBER/WIE_KURVE", "+60/SCHNELLER/WIE_ANGEKUENDIGT", "+60/SCHNELLER/MEHR",
        )
        val technikFaelle = mitProtokoll.filter { it.startsWith("Pizza") }.toSet()
        val technikProben = Technik.entries.filter { it != Technik.NORMAL }
        class TechnikZeile(val fall: String, val v: Lauf, val heute: Lauf, val normal: Lauf)
        val technikZeilen = technikProben.associateWith { mutableListOf<TechnikZeile>() }
        fun id(name: String) = name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        fun kurz(l: Lauf) = "${f0(l.minBg)}/${l.minutenUnter(70.0)}/${l.ueber(180.0)}/${f0(l.maxBg)}"
        val kopf = "+m | q1 (wahr) | r | Bahn am Freigabehorizont | Bedarf normal U | IOB U | " +
            "Insulin bis/nach dem Freigabehorizont mg/dl | Plan bis/nach dem Freigabehorizont mg/dl | " +
            "Sperre, Kante | Schwanz-Raum U | G U | Grenze SMB-Weg U | SMB U | Kanal U/h Grund | Sofort-Rest U, eCarbs-Rest U, c_e, w"
        val rKopf = "+m | BG wahr | q1 | r | UKF-Trend | G U | Ueberdeckung U | Wunsch (oder Grund dagegen) | gewollte Rate U/h | " +
            "Pumpe laeuft (Rate bis) | Schleife kennt (Rate bis) | ausstehend (Ziel, Ausfuehrung, Meldung) | geplant U | " +
            "zurueckgehalten bisher U | Untergrenze der Bahn (bei +min) | eine Null bis dahin verhindert mg/dl | Schutz-Null"
        val z = StringBuilder(
            "\nProdukt gegen heute. Fall | heute | ohne Ruecknahme | Produkt (je tiefster Wert/min <70/min >180/Gipfel) | Insulin " +
                "heute/Produkt U (davon SMB/Kanal im Produkt) | Befehle, zurueckgehalten U, erster Befehl | Tief nach dem ersten " +
                "Befehl (bei), davon Wirkung der Ruecknahme bis dahin mg/dl | Gipfel bis 120 min nach der letzten Ruecknahme " +
                "Produkt/ohne | Urteil gegen heute\n",
        )
        val fehler = mutableListOf<String>()
        val tiefFehler = mutableListOf<String>()
        val zaehler = mutableMapOf<String, Int>()
        val langeListe = mutableListOf<String>()
        var summe = DoubleArray(14)
        for (fall in faelle) {
            val pHeute = if (fall.name in mitProtokoll) StringBuilder(kopf) else null
            val pProdukt = if (fall.name in mitProtokoll) StringBuilder(kopf) else null
            val rp = if (fall.name in mitProtokoll) StringBuilder(rKopf) else null
            // Punkt 3: die Zeilen je Zyklus fuer heute und das Produkt - nur mit Export.
            val zHeute = if (ziel != null) StringBuilder(ZYKLUS_KOPF) else null
            val zProdukt = if (ziel != null) StringBuilder(ZYKLUS_KOPF) else null
            val h = fall.lauf(Arm.HEUTE, Technik.NORMAL, false, pHeute, null, zHeute)
            val ohne = fall.lauf(Arm.PRODUKT, Technik.NORMAL, true, null, null, null)
            val produkt = fall.lauf(Arm.PRODUKT, Technik.NORMAL, false, pProdukt, rp, zProdukt)
            produkt.rueckFehler.forEach { fehler += "${fall.name}: $it" }
            // Vor dem ersten Befehl ist das Produkt gleich dem Lauf ohne Ruecknahme, Minute fuer Minute.
            val erste = produkt.rueckErsteMin
            val bisErste = erste ?: produkt.bg.lastIndex
            if ((0..bisErste).any { produkt.bg[it] != ohne.bg[it] || produkt.gabe[it] != ohne.gabe[it] }) {
                fehler += "${fall.name}: vor dem ersten Befehl anders als ohne Ruecknahme"
            }
            tiefVerschlechterung(produkt, h).takeIf { it.isNotEmpty() }?.let { tiefFehler += "${fall.name}: $it" }
            // Kam die Ruecknahme rechtzeitig? Der tiefste Punkt nach dem ersten Befehl und wie viel der Ruecknahme bis dahin wirkt.
            val tief = erste?.let { e -> (e..produkt.bg.lastIndex).minBy { produkt.bg[it] } }
            val wirkungBisTief = tief?.let { t ->
                (0 until t).sumOf { k -> produkt.rueckVerlauf[k] * fall.patient.isf * EarlySupply.orefActionFraction((t - k).toDouble(), peak, dia) }
            }
            // Der Wiederanstieg: Gipfel in den 120 min nach der letzten Ruecknahme, Produkt gegen ohne Ruecknahme im selben Fenster.
            val letzte = produkt.rueckVerlauf.indices.lastOrNull { produkt.rueckVerlauf[it] > 0.0 }
            val nach = letzte?.let { (it + 1)..minOf(produkt.bg.lastIndex, it + 120) }?.takeIf { !it.isEmpty() }
            // DAS URTEIL AUF GETRENNTEN ACHSEN (Toni 05.11.: Tiefs, Versorgung und Hochs je fuer sich): weniger Insulin senkt
            // die Tiefs und kann die Hochs heben - beides steht nebeneinander, keins verdeckt das andere.
            val b = bewertung(produkt, h, fall.unterversorgt)
            val urteil = if (produkt.gabe.contentEquals(h.gabe) && produkt.netto.contentEquals(h.netto)) "gleich heute" else {
                val tiefBesser = produkt.minutenUnter(70.0) < h.minutenUnter(70.0) || h.minBg < 70.0 && produkt.minBg > h.minBg + 0.5
                val abstand = produkt.minBg >= h.minBg + 2.0
                val tiefTeil = when {
                    b.tiefSchlechter -> "Tief SCHLECHTER"
                    tiefBesser -> "Tief besser"
                    abstand -> "Tief: mehr Abstand"
                    else -> "Tief gleich"
                }
                val hochTeil = when {
                    b.mehrHoch -> "Hoch mehr"
                    produkt.ueber(180.0) < h.ueber(180.0) || produkt.ueber(250.0) < h.ueber(250.0) -> "Hoch weniger"
                    else -> "Hoch gleich"
                }
                "$tiefTeil, $hochTeil" + if (b.spaeter) ", Versorgung spaeter" else ""
            }
            zaehler.merge(urteil, 1, Int::plus)
            if (b.mehrHoch || b.spaeter || b.tiefSchlechter) langeListe += "${fall.name}: $urteil | $b"
            // Die Summen ueber alle Faelle: Minuten unter 70 und ueber 180, Faelle mit Tief, Insulin - heute und Produkt.
            val werte = doubleArrayOf(
                h.minutenUnter(70.0).toDouble(), produkt.minutenUnter(70.0).toDouble(),
                h.ueber(180.0).toDouble(), produkt.ueber(180.0).toDouble(),
                if (h.minBg < 70.0) 1.0 else 0.0, if (produkt.minBg < 70.0) 1.0 else 0.0,
                h.gabe.sum(), produkt.gabe.sum(), produkt.rueckVerlauf.sum(), produkt.kanalGedeckt.toDouble(),
                h.ueber(250.0).toDouble(), produkt.ueber(250.0).toDouble(),
                if (h.verlassenAb != null) 1.0 else 0.0, if (produkt.verlassenAb != null) 1.0 else 0.0,
            )
            summe = DoubleArray(summe.size) { summe[it] + werte[it] }
            z.append(
                "${fall.name} | ${kurz(h)} | ${kurz(ohne)} | ${kurz(produkt)} | ${f(h.gabe.sum())}/${f(produkt.gabe.sum())} " +
                    "(${f(produkt.smbU)}/${f(produkt.kanalU)}) | ${produkt.rueckBefehle}, ${f(produkt.rueckVerlauf.sum())}, " +
                    "${erste?.let { "+$it" } ?: "-"} | ${tief?.let { "${f0(produkt.bg[it])} (+$it), ${f0(wirkungBisTief ?: 0.0)}" } ?: "-"} | " +
                    "${nach?.let { w -> "${f0(w.maxOf { produkt.bg[it] })}/${f0(w.maxOf { ohne.bg[it] })}" } ?: "-"} | $urteil\n",
            )
            // Pumpe und Schleife (Abschnitt 23) in ALLEN Faellen. Die Grenze je Befehl muss immer halten; der Rest ist ein Befund.
            for (t in technikProben) {
                val v = fall.lauf(Arm.PRODUKT, t, false, null, null, null)
                v.rueckFehler.forEach { fehler += "${fall.name}/$t: $it" }
                technikZeilen.getValue(t) += TechnikZeile(fall.name, v, h, produkt)
                if (fall.name in technikFaelle) z.append(
                    "  ${fall.name} mit $t | ${kurz(v)} | Anforderungen ${v.rueckAnforderungen}, zurueckgehalten " +
                        "${f(v.rueckVerlauf.sum())} U, Luecke ${v.rueckLueckenMin} min, ungewollt ${f(v.rueckUngewolltU)} U " +
                        "(${v.rueckNachlaufMin} min ohne Wunsch), Plan ueber der Ueberdeckung hoechstens ${f(v.rueckUeberPlanU)} U\n",
                )
            }
            if (fall.name in technikFaelle) z.append(
                "  ${fall.name} NORMAL: Anforderungen ${produkt.rueckAnforderungen}, davon Erneuerungen bei gleicher Rate " +
                    "${produkt.rueckErneuerungen}, Luecke ${produkt.rueckLueckenMin} min, ungewollt ${f(produkt.rueckUngewolltU)} U\n",
            )
            if (ziel != null) {
                val datei = id(fall.name)
                val teile = ECarbs.parts(fall.patient.eCarbsG.toDouble(), t0 + fall.beginn * minMs, eDurationMin)
                    .map { ((it.ts - t0) / minMs).toInt() to it.grams }
                File(ziel, "fall_$datei.txt").writeText(
                    "name=${fall.name}\nisf=${fall.patient.isf}\nic=${fall.patient.ic}\nbasal=$basalUPerH\nstartBg=$startBg\n" +
                        "bolus=${fall.bolusU}\nlaufMin=${h.bg.lastIndex}\n" +
                        "eintraege=" + (listOf(0 to fall.patient.sofortG) + teile).joinToString(";") { "${it.first}:${it.second}" } + "\n" +
                        "kh=" + h.khRate.joinToString(",") + "\n",
                )
                for ((a, l) in listOf(Arm.HEUTE to h, Arm.PRODUKT to produkt)) {
                    File(ziel, "fuse_${datei}_$a.csv").writeText(
                        "m;bg;netto;gabe;rueck;smb\n" + l.bg.indices.joinToString("\n") { m ->
                            "$m;${l.bg[m]};${l.netto[m]};${l.gabe[m]};${l.rueckVerlauf[m]};${l.smbVerlauf[m]}"
                        } + "\n",
                    )
                }
                rp?.let { File(ziel, "r_$datei.txt").writeText(it.toString() + "\n") }
                zHeute?.let { File(ziel, "zyklen_${datei}_HEUTE.csv").writeText(it.toString()) }
                zProdukt?.let { File(ziel, "zyklen_${datei}_PRODUKT.csv").writeText(it.toString()) }
                pHeute?.let { File(ziel, "p_${datei}_HEUTE.txt").writeText(it.toString() + "\n") }
                pProdukt?.let { File(ziel, "p_${datei}_PRODUKT.txt").writeText(it.toString() + "\n") }
            }
        }
        z.append(
            "\nZusammenfassung gegen heute: " + zaehler.entries.sortedBy { it.key }.joinToString("; ") { "${it.key} ${it.value}" } +
                " von ${faelle.size}\n",
        )
        z.append(
            "Ueber alle ${faelle.size} Faelle, heute -> Produkt: Minuten unter 70 ${f0(summe[0])} -> ${f0(summe[1])}, Minuten ueber " +
                "180 ${f0(summe[2])} -> ${f0(summe[3])}, ueber 250 ${f0(summe[10])} -> ${f0(summe[11])}, Faelle mit Tief unter 70 " +
                "${f0(summe[4])} -> ${f0(summe[5])}, Faelle ausserhalb des Modellbereichs ${f0(summe[12])} -> ${f0(summe[13])}, " +
                "Insulin ${f(summe[6])} -> ${f(summe[7])} U, davon Basal zurueckgenommen ${f(summe[8])} U; Zyklen mit Kanal durch " +
                "G begrenzt ${f0(summe[9])}\n",
        )
        z.append("\nMehr Hoch oder spaetere Versorgung, je Fall:\n  " + langeListe.joinToString("\n  ") + "\n")
        // Gleiche Rate am Blockende (Abschnitt 23): wurde erneuert, und wo? Ohne Treffer waere die harte Pruefung "keine
        // Luecke" nicht ausgereizt.
        val normalLaeufe = technikZeilen.values.first().map { it.fall to it.normal }
        z.append(
            "\nErneuerungen bei gleicher Rate im Normalfall: ${normalLaeufe.sumOf { it.second.rueckErneuerungen }} in " +
                "${normalLaeufe.count { it.second.rueckErneuerungen > 0 }} Faellen (" +
                normalLaeufe.filter { it.second.rueckErneuerungen > 0 }.joinToString(", ") { "${it.first} ${it.second.rueckErneuerungen}" } + ")\n",
        )
        // Die Technik-Proben ueber alle Faelle: Tiefs gegen heute und gegen das Produkt ohne Technikfehler, Hochs, und was die
        // Pumpe anders tat als gewollt.
        z.append(
            "\nTechnik-Proben ueber alle ${faelle.size} Faelle | Tief schlechter als heute | Tief schlechter als Produkt normal | " +
                "mehr Hoch als Produkt normal | zurueckgehalten im Mittel U (normal) | Luecke hoechstens min | ungewollt hoechstens U " +
                "(Fall) | Nachlauf ohne Wunsch hoechstens min | Plan ueber der Ueberdeckung hoechstens U | Erneuerungen bei gleicher " +
                "Rate gesamt\n",
        )
        for ((t, zeilen) in technikZeilen) {
            val schlechterHeute = zeilen.count { tiefVerschlechterung(it.v, it.heute).isNotEmpty() }
            val schlechterNormal = zeilen.count { tiefVerschlechterung(it.v, it.normal).isNotEmpty() }
            val mehrHoch = zeilen.count { it.v.ueber(180.0) > it.normal.ueber(180.0) || it.v.ueber(250.0) > it.normal.ueber(250.0) }
            val ungewollt = zeilen.maxBy { it.v.rueckUngewolltU }
            z.append(
                "$t | $schlechterHeute | $schlechterNormal | $mehrHoch | ${f(zeilen.map { it.v.rueckVerlauf.sum() }.average())} " +
                    "(${f(zeilen.map { it.normal.rueckVerlauf.sum() }.average())}) | ${zeilen.maxOf { it.v.rueckLueckenMin }} | " +
                    "${f(ungewollt.v.rueckUngewolltU)} (${ungewollt.fall}) | ${zeilen.maxOf { it.v.rueckNachlaufMin }} | " +
                    "${f(zeilen.maxOf { it.v.rueckUeberPlanU })} | ${zeilen.sumOf { it.v.rueckErneuerungen }}\n",
            )
            zeilen.filter { tiefVerschlechterung(it.v, it.heute).isNotEmpty() }.forEach {
                z.append("  $t, ${it.fall}: gegen heute ${tiefVerschlechterung(it.v, it.heute)}\n")
            }
            zeilen.filter { tiefVerschlechterung(it.v, it.normal).isNotEmpty() }.forEach {
                z.append("  $t, ${it.fall}: gegen Produkt normal ${tiefVerschlechterung(it.v, it.normal)}\n")
            }
        }
        z.append("\nTief gegen heute: ${if (tiefFehler.isEmpty()) "nie schlechter" else tiefFehler.joinToString("\n  ")}\n")
        z.append("Grenzen und Gleichheit vor dem ersten Befehl: ${if (fehler.isEmpty()) "alle eingehalten" else fehler.joinToString("\n  ")}\n")
        ziel?.let { File(it, "produkt_fuse.txt").writeText(z.toString()) }
        println(z)
        assertTrue(fehler.isEmpty()) { fehler.joinToString("\n") }
        assertTrue(tiefFehler.isEmpty()) { "Tief schlechter als heute:\n" + tiefFehler.joinToString("\n") }
    }
}
