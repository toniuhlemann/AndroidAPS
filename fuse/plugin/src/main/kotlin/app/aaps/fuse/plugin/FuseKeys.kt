package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey

/**
 * FUSEs EIGENE Einstellungen — einstellbar, nicht verdrahtet.
 *
 * Jeder Wert unten ist ein DEFAULT, kein Literal im Regelpfad: der Zyklus liest
 * ausschliesslich ueber `preferences.get(...)`. Die Klassen werden beim Start
 * ueber `PluginBaseWithPreferences.ownPreferences` registriert — ohne diese
 * Registrierung waeren sie zwar lesbar, aber nicht exportierbar
 * (`PreferencesImpl.isExportableKey` laeuft ueber die registrierte Liste), und
 * der Einstellungsbildschirm koennte den Key nicht per String aufloesen.
 *
 * Warum nicht die autoISF-Einstellungen mitlesen: `openapsama_smb_delivery_ratio`
 * und `iob_threshold_percent` sind autoISF-spezifisch. Sie mitzubenutzen haette
 * drei Folgen, alle schlecht:
 *
 *  1. FUSE haenge an der Bedeutung eines fremden Algorithmus — wer autoISF
 *     spaeter nachjustiert, verstellt unbemerkt FUSE.
 *  2. Solange FUSE das aktive APS ist, ist autoISF deaktiviert und sein
 *     Einstellungsbildschirm nicht erreichbar. Der Wert waere eingefroren auf
 *     dem, was zufaellig zuletzt darin stand.
 *  3. Beide Keys sind `defaultedBySM` — ist nie etwas gesetzt worden, liefert
 *     `get()` stillschweigend autoISFs Default. FUSE liefe dann auf einer Zahl,
 *     die niemand fuer FUSE gewaehlt hat.
 *
 * Die Keys stehen im FUSE-Modul und nicht in `core/keys`: die
 * Key-Schnittstellen sind offen, also braucht FUSE dafuer KEINE Bestandsdatei.
 * Die Additiv-Disziplin bleibt unangetastet.
 *
 * `defaultedBySM = false` durchgehend: im Simple Mode wuerde AAPS sonst den
 * Default erzwingen und die eingestellte Zahl ignorieren. Fuer einen
 * Alpha-Regler, der auf dem Testgeraet nachjustiert wird, waere das die
 * unangenehmste Sorte Ueberraschung — der Bildschirm zeigt einen Wert, gerechnet
 * wird ein anderer.
 */
enum class FuseDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
) : DoublePreferenceKey {

    /**
     * Anteil im KORREKTURBETRIEB — kein bestaetigter Anstieg.
     *
     * Der Schluessel heisst weiter `fuse_smb_ratio`, damit ein bereits
     * eingestellter Wert nicht verlorengeht. Seine BEDEUTUNG ist jetzt enger:
     * er gilt nur noch, wenn der Observer keinen Anstieg bestaetigt hat.
     * Default 0,15 statt 0,2 — die Korrektur darf ruhiger sein, seit der
     * Anstieg seinen eigenen Wert hat.
     */
    SmbRatio("fuse_smb_ratio", 0.15, 0.0, 1.0),

    /**
     * Anteil im ANSTIEGSBETRIEB — Observer-Phase CANDIDATE/RISE_ACTIVE/CARRY.
     *
     * Das ist der FCL-Wert: frueh den Grossteil aufbauen. Ein einzelner Anteil
     * fuer beide Lagen kann das nicht — er ist entweder fuer die Korrektur zu
     * scharf oder fuer die Mahlzeit zu zaghaft.
     *
     * Anders als bei autoISF muss dafuer KEINE TT gesetzt werden: der Observer
     * erkennt den Anstieg selbst (r >= 0,50 mg/dl/min ueber zwei Punkte).
     */
    SmbRatioRise("fuse_smb_ratio_rise", 0.35, 0.0, 1.0),

    /**
     * Untere Kante der Rampe [mg/dl/min] — bis hierher gilt der Korrekturanteil.
     *
     * NICHT die Observer-Schwelle (0,50). Die erkennt "irgendetwas steigt";
     * hier geht es um "wieviel Evidenz rechtfertigt wieviel Verstaerkung".
     */
    RiseRampLowR("fuse_rise_ramp_low_r", 0.5, 0.0, 5.0),

    /**
     * Obere Kante [mg/dl/min] — ab hier gilt der volle Anstiegsanteil.
     *
     * 2,0 als Startwert, weil ein echter Mahlzeiten-Onset bei 3-5 mg/dl/min
     * liegt und ein flacher Drift bei 0,5-0,8. Der Wert ist NICHT gemessen —
     * genau dafuer steht `r` in jeder Zeile des Trails.
     */
    RiseRampHighR("fuse_rise_ramp_high_r", 2.0, 0.1, 10.0),

    /**
     * Bolus-Deckungs-Abschlag der UNTEREN Bahn (lambda). 1.0 = der von
     * Bolus-Aktivitaet gedeckte Anteil der Stoerung wird in der Guardbahn
     * nicht als anhaltend unterstellt. 0.0 = aus (bit-identisch zum Stand
     * davor). Wirkt NUR auf `lower` - die Dosis haengt an der Mittelbahn
     * (s. DriveDiscount).
     */
    BolusShareLambda("fuse_bolus_share_lambda", 1.0, 0.0, 2.0),


    /**
     * Haftungshuelle des Onset-Kanals [U]: hoechstens so viel darf der
     * unbestaetigte schnelle Kanal je Episode freigeben, bevor Theil-Sen
     * uebernimmt. Zum Massstab: die Rueckholkapazitaet ueber 30 min ist
     * 0,225-0,35 U (KC2-53) - die Huelle ist eine bezifferte Wette, kein
     * rueckholbarer Betrag.
     */
    OnsetEnvelopeU("fuse_onset_envelope_u", 1.5, 0.0, 5.0),

    /**
     * Die Marker-Huelle [U] - und sie ist ZWEI Dinge, was man wissen muss:
     *
     *  1. Obergrenze der Sofort-Freigabe je Episode.
     *  2. ZAEHLER des erklaerten Absorptionsantriebs
     *     (`(Huelle - geliefert) * ISF / Absorptionsfenster`). Sie bestimmt
     *     also nicht nur, wieviel hoechstens kommt, sondern auch, wie
     *     STARK gedrueckt wird.
     *
     * Default 1,2 = das gemessene Reversibilitaetsmass KC2-53 (per
     * Basal-Null in 120 min rueckholbar: 0,9-1,4 U).
     *
     * OBERGRENZE 3,0 (11.08., Tonis Entscheidung). Sie liegt WEIT jenseits
     * der Rueckholkapazitaet von ~0,5 U/h - eine Menge in dieser Groesse ist
     * in der Zeit, in der sie entsteht, nicht mehr einzufangen.
     *
     * Das ist bewusst so und kein Versehen: die Menge ist eine ERKLAERUNG
     * des Nutzers ueber das, was er gerade isst, und diese Information hat
     * das Geraet nicht. Was hier NICHT unsicher ist, ist die Menge; unsicher
     * bleibt allein der ZEITPUNKT der Absorption - dieselbe Unsicherheit,
     * die auch einen korrekt gerechneten HCL-Bolus bei einer fetten
     * Mahlzeit trifft.
     *
     * Daraus folgt die Aufgabenteilung, die in Scheibe 1 gebaut wird: die
     * Waechter duerfen die ERSTE Freigabe nicht verhindern (dort wissen sie
     * nichts, was der Nutzer nicht weiss), aber sie halten das NACHLEGEN an,
     * wenn die angekuendigte Absorption ausbleibt.
     */
    // OBERGRENZE 6,0 (Toni 25.08. abends, zuvor 4,0): der Stellbereich,
    // nicht der Wert. Der Default bleibt 1,2; die groesseren Huellen
    // entstehen erst durch bewusstes Stellen. Hintergrund ist die
    // Liveness-Deadlock-Messung: eine Mahlzeit brauchte deutlich mehr Insulin, als
    // die damalige Huelle je Stunde freigab.
    PrimeEnvelopeU("fuse_prime_envelope_u", 1.2, 0.0, 6.0),

    /**
     * V-REVERSAL-SCHUTZ (Bauauftrag Toni 25.08.): die
     * Fall-Schwelle [mg/dl/min] - so tief muss das UKF-Minimum im
     * Rueckblick gelegen haben, damit "steiler Fall" gilt (der Pflichtfall
     * lag deutlich tiefer). Nur Korrekturkontext; s. CorrectionReversalGuard.
     */
    ReversalFallUkf("fuse_reversal_fall_ukf", 2.0, 0.5, 5.0),

    /** Gegenbewegungs-Schwelle [mg/dl/min] - ab dieser schnellen
     *  Erholungsrate greift der Riegel (der Pflichtfall lag weit darueber). */
    ReversalReboundUkf("fuse_reversal_rebound_ukf", 1.0, 0.2, 5.0),

    /** Nachlauf-Bestaetigung: UKF-Schwelle der Aufwaertslage nach
     *  Zero-Latch-/Nachtende [mg/dl/min]. */
    RearmUpUkf("fuse_rearm_up_ukf", 0.3, 0.0, 2.0),


    /**
     * Obergrenze eines einzelnen SMB, in Einheiten.
     *
     * autoISF hat dafuer KEINE Einstellung, sondern leitet sie ab
     * (`smb_max_range * baseBasalRate * smbmaxminutes / 60`). Diese Ableitung
     * haengt am Profilbasal und aendert sich damit nachts von selbst — fuer
     * einen Alpha-Regler ist eine feste, benannte Obergrenze ehrlicher.
     */
    MaxSmbU("fuse_max_smb_u", 0.3, 0.0, 5.0),

    /**
     * Untergrenze, die die pessimistische Bahn nicht unterschreiten darf (mg/dl).
     * Wird sie verletzt, gibt es keinen SMB und eine Zero-TBR.
     *
     * mg/dl statt einer einheitenabhaengigen Preference: das Geraet laeuft in
     * mg/dl, und ein stiller Einheitenwechsel an einer SICHERHEITSgrenze waere
     * die falsche Stelle fuer Bequemlichkeit.
     */
    GuardFloorMgdl("fuse_guard_floor_mgdl", 70.0, 40.0, 120.0),

    /**
     * MINDESTNUTZEN einer Zero-TBR [mg/dl] - die Zulassungsschwelle des
     * Low-Tors (Toni 17.08.: "0 tbr muss auch einen messbaren nutzen haben
     * und eine sich anbahnende hypo tatsaechlich rechnerisch ausbremsen
     * koennen").
     *
     * Gerechnet wird, was eine ab jetzt laufende Null bis zum erwarteten
     * Bodenkontakt an Absenkung VERHINDERT - integriert ueber die Wirkkurve,
     * nicht "Rate mal Zeit". Die Wirkung waechst stark ueberproportional mit
     * dem Vorlauf (Groessenordnungen an einem Beispielprofil in
     * LowThreatGateTest).
     *
     * Der Default 5 liegt an der Messbarkeitsgrenze: darunter ist der Effekt
     * kleiner als das Sensorrauschen, und eine Massnahme, deren Erfolg man
     * nicht sehen kann, ist keine. Praktisch heisst das einen Vorlauf in der
     * Groessenordnung einer Stunde.
     *
     * GROESSER = SELTENER. Bei 0 ist die Nutzenprobe faktisch aus und das
     * Tor haengt nur noch an Fall und Ueberdeckung; die Obergrenze 40
     * verlangt schon rund zweieinhalb Stunden Vorlauf.
     */
    LowGateMinBenefitMgdl("fuse_low_gate_min_benefit_mgdl", 5.0, 0.0, 40.0),

    /**
     * KURZFRISTFENSTER der Richtungsprobe [min]: liegt der Bodenkontakt bei
     * linearer Fortschreibung der GEMESSENEN Rate weiter weg, ist es keine
     * nahe Gefahr und das Tor bleibt zu.
     *
     * Bewusst eine lineare Fortschreibung des Messwerts und NICHT das Minimum
     * der 120-min-Bahn: jene rechnet kohlenhydratfrei, mit Antriebszerfall
     * und rund 51-facher Horizontverstaerkung - genau die Groesse, die den
     * 60-%-Nullzustand erzeugt hat.
     */
    LowGateHorizonMin("fuse_low_gate_horizon_min", 120.0, 30.0, 240.0),

    /**
     * ZERO-LATCH, RUHE-AUSGANG (Bauauftrag Toni 24.08. abends): der zweite,
     * langsame Freigabepfad gegen die "Zero-Falle" - ein dauerhaft flacher
     * BG ohne Anstieg soll die verriegelte Null nicht unbegrenzt halten.
     * Geloest wird, wenn ueber [ZeroLatchCalmExitMin] Zyklen STABIL gilt:
     * nicht fallend, q1 mindestens diesen Abstand ueber dem Guard-Boden
     * und KEINE Bolus-Ueberdeckung mehr. Werte replay-kalibriert, Toni
     * stellt.
     */
    ZeroLatchCalmDistanceMgdl("fuse_zero_latch_calm_distance_mgdl", 30.0, 10.0, 60.0),

    /**
     * Nahhorizont des harten Endriegels fuer NEUES positives Insulin [min].
     * Getrennt vom 120-minuetigen TBR-Nutzenfenster: Basal rechtzeitig
     * zurueckhalten und einen Mahlzeiten-SMB hart verbieten sind zwei
     * verschiedene Entscheidungen. Der Live-Replay des Anlassfalls laesst bei
     * 30 min noch vier fruehe Schritte zu und sperrt vor der akuten Kante;
     * 120 min sperrte die komplette Phase A bereits bei normalem BG.
     */
    PositiveDescentHorizonMin("fuse_positive_descent_horizon_min", 30.0, 15.0, 60.0),

    /**
     * Nahhorizont des Abwaertsriegels NUR fuer markerautorisiertes Insulin
     * [min] (Punkt 6, Toni 22.08.). Reine Korrekturen behalten
     * [PositiveDescentHorizonMin]; dieser Wert wird BEIM MARKER GEPINNT und
     * gilt fuer dessen ganze Laufzeit. Im Replay der Markerfaelle schob der
     * Standardwert die Menge des Problemfalls auf und liess die Gutfaelle
     * unangetastet - bei sehr kleiner Fallzahl und knapper Marge, darum
     * konfigurierbar statt festgenagelt.
     */
    MarkerPrimeDescentHorizonMin("fuse_marker_prime_descent_horizon_min", 60.0, 30.0, 120.0),

    /**
     * DIE VIER ZENTRALEN PROFILWERTE (Bauauftrag 7.1; CENTRAL-only seit
     * dem Legacy-Cleanup 29.08. nachts - es gibt keinen Modusschalter und
     * keine Legacy-Kanaldeckel mehr).
     *
     * ECHTE Runtime- UND Migrationsdefaults (Tonis begruendeter Startsatz
     * aus den Messfaellen, keine physiologische Garantie): ein altes
     * Backup ohne diese Schluessel erhaelt sie; ausdruecklich gesetzte
     * Werte ueberschreibt kein Update. Exposure intern in kanonischen U;
     * relational fail-closed CORRECTION nie offener als MEAL.
     *
     * Startsatz-Begruendung (29.08.): CORR 3,0 U haette einen gemessenen Korrektur-Burst
     * deutlich begrenzt (2,5 U war der schaerfere Kandidat);
     * MEAL 7,0 U laesst nach etwas Alt-IOB und einer typischen Direktdosis noch
     * Nachsteuerraum; CORR-Ratio 0,20 bewahrt die bisherige
     * Korrektur-Drossel auch fuer den RISE-Pfad; MEAL-Ratio 0,35 gibt
     * unter Vollmacht die konfigurierte Anstiegsratio frei (wirksam
     * bleibt min(Basisrampe, Cap) - nichts wird kuenstlich erhoeht).
     */
    CorrectionExposureLimitU("fuse_correction_exposure_limit_u", 3.0, 0.5, 20.0),
    MealExposureLimitU("fuse_meal_exposure_limit_u", 7.0, 0.5, 20.0),
    CorrectionDemandRatioCap("fuse_correction_demand_ratio_cap", 0.20, 0.05, 1.0),
    MealDemandRatioCap("fuse_meal_demand_ratio_cap", 0.35, 0.05, 1.0),

    /**
     * BG-Schwelle der Druckbedingung des Liveness-Kanals am TAG [mg/dl].
     *
     * Toni 22.08.: nicht hart codieren. Untergrenze 100: darunter waere die
     * Schwelle keine Hochdruck-Bedingung mehr, sondern hebelte den
     * Zielbereich selbst. Seit v20 die TAGES-Haelfte des Paars; der
     * SCHLUESSEL bleibt der alte Einzelwert-Schluessel, damit ein bereits
     * eingestellter Wert das Update ueberlebt.
     */
    LivenessBgMinDayMgdl("fuse_liveness_bg_min_mgdl", 140.0, 100.0, 250.0),

    /**
     * BG-Schwelle der Druckbedingung in der NACHT [mg/dl] (v20, Toni/Codex
     * 22.08. spaet): die Nacht darf konservativer beginnen - Profilziel +
     * Nacht-Totband liegt darunter, eine eigene Nachtschwelle (Kandidat
     * 160) greift auch dann, wenn das Totband ueberschritten oder
     * entwaffnet ist. Rebound und gemessene Riegel bleiben unberuehrt.
     *
     * MIGRATION: solange dieser Schluessel NIE gesetzt wurde, folgt die
     * Nachtschwelle zur Laufzeit der Tagesschwelle (getIfExists-Fallback im
     * Config-Bau) - ein Update veraendert nichts still. Der Default hier
     * greift nur fuer die Bildschirm-Anzeige vor dem ersten Setzen.
     */
    LivenessBgMinNightMgdl("fuse_liveness_bg_min_night_mgdl", 160.0, 100.0, 250.0),

    /** Totband der NACHT [mg/dl ueber Ziel]: darunter kein SMB im Nachtfenster.
     *  0 = aus. Ein erklaerter Marker hebt es auf (Toni 09.08.). */
    NightDeadbandMgdl("fuse_night_deadband_mgdl", 45.0, 0.0, 100.0),

    /** Totband nach einem Tief [mg/dl ueber Ziel] - war bis 09.08. die feste
     *  Konstante 25. */
    ReboundDeadbandMgdl("fuse_rebound_deadband_mgdl", 25.0, 0.0, 100.0),

    /**
     * Untergrenze fuer das SCHWANZFENSTER hinter dem Haftungshorizont [mg/dl].
     * Getrennt von [GuardFloorMgdl], weil die beiden verschiedene Zeitraeume
     * absichern und man sie unabhaengig verstellen koennen muss.
     */
    TailFloorMgdl("fuse_tail_floor_mgdl", 70.0, 40.0, 120.0),

    /**
     * Erholung, die im Schwanzfenster eingeplant werden darf [U].
     *
     * DEFAULT 0,0 — Guard v0.4 setzt ihn ausdruecklich auf 0,0 und schreibt
     * dazu: AENDERUNG NUR MIT MESSUNG. Wer hier eine Zahl eintraegt, behauptet
     * zu wissen, wieviel Erholung der Schwanz traegt; ohne Messung ist das
     * geraten.
     */
    TailRecoveryU("fuse_tail_recovery_u", 0.0, 0.0, 5.0),

    // ---- ERWARTUNGSKONTO (Testkandidat, Vorregistrierung E-034) ------------
    //
    // Alle Werte werden je Mahlzeit beim Markerdruck festgehalten; eine
    // spaetere Aenderung verschiebt eine laufende Episode nicht. Die Grenzen
    // kommen aus dem Kern (ExpectationAccount), damit Dialog und Rechnung nie
    // auseinanderlaufen. Ein ungueltiger Wert schaltet die ERWARTUNG ab (nur
    // Ersetzung), er bricht keinen Zyklus ab und hat keinen Ersatzwert.

    /**
     * Stabilitaet nach Tief: BG ueber dieser Grenze, kein Fallen, keine
     * gemessene Sperre - erst dann darf Erwartung fliessen.
     */
    ExpectationStabilityBgMgdl(
        "fuse_expectation_stability_bg_mgdl", 90.0,
        app.aaps.fuse.core.controller.ExpectationAccount.MIN_STABILITY_BG_MGDL,
        app.aaps.fuse.core.controller.ExpectationAccount.MAX_STABILITY_BG_MGDL,
    ),

    /**
     * Ruecknahme: bleibt die beobachtete Stoerung bis zur Pruefung unter
     * dieser Menge, wird der Budgetrest eingefroren; erreicht sie sie
     * spaeter im Fenster, wird er wieder frei.
     */
    ExpectationReleaseThresholdU(
        "fuse_expectation_release_threshold_u", 0.3, 0.0,
        app.aaps.fuse.core.controller.ExpectationAccount.MAX_RELEASE_THRESHOLD_U,
    ),

    /**
     * Fallgrenze der Stabilitaetsstrecke als RUECKGANG [mg/dl/min, positiv]:
     * ein UKF-Trend unter -Grenze unterbricht sie. Standard = die bisherige
     * Kanalkonstante (schon ein leichter Rueckgang in einem Zyklus zaehlt als
     * Fallen). Gilt fuer die Startlage am Marker, die Wiederfreigabe nach
     * einem Tief, die Pause der Anfangsportion und - solange die Mahlzeit
     * laeuft ([MealRunningMinR]) - die Pause der weiteren Portionen.
     */
    ExpectationStabilityFallMgdlPerMin(
        "fuse_expectation_stability_fall_mgdl_per_min",
        app.aaps.fuse.core.controller.ExpectationAccount.DEFAULT_STABILITY_FALL_MGDL_PER_MIN,
        app.aaps.fuse.core.controller.ExpectationAccount.MIN_STABILITY_FALL_MGDL_PER_MIN,
        app.aaps.fuse.core.controller.ExpectationAccount.MAX_STABILITY_FALL_MGDL_PER_MIN,
    ),

    /**
     * Schritt 2 (Toni): ab welcher Stoerungsrate r [mg/dl/min] eine angekuendigte Mahlzeit als LAUFEND gilt - nur im
     * Mahlzeitfenster und nur fuer die weiteren Portionen des Erwartungskontos. Eigene Schwelle, damit die Rampe
     * (SMB-Anteil, Korrekturweg, Onset) davon unberuehrt bleibt. 0 (Standard) = wie [RiseRampLowR]. Gilt sofort.
     */
    MealRunningMinR("fuse_meal_running_min_r", 0.0, 0.0, 10.0),
    /**
     * DIE ERWARTUNGSKURVE IN VIER ECKWERTEN (Toni): erwartete
     * Mahlzeitwirkung [U] nach 30, 60, 90 und 120 min ab Marker, dazwischen
     * linear. Vorrang vor der Expertenzeile [FuseStringKey.ExpectationCurve];
     * alle vier 0 (Standard) = nicht gesetzt. Persoenliche Werte stehen nur
     * auf dem Geraet.
     */
    ExpectationCurveAt30U("fuse_expectation_curve_30_u", 0.0, 0.0, app.aaps.fuse.core.controller.ExpectationAccount.MAX_CURVE_U),
    ExpectationCurveAt60U("fuse_expectation_curve_60_u", 0.0, 0.0, app.aaps.fuse.core.controller.ExpectationAccount.MAX_CURVE_U),
    ExpectationCurveAt90U("fuse_expectation_curve_90_u", 0.0, 0.0, app.aaps.fuse.core.controller.ExpectationAccount.MAX_CURVE_U),
    ExpectationCurveAt120U("fuse_expectation_curve_120_u", 0.0, 0.0, app.aaps.fuse.core.controller.ExpectationAccount.MAX_CURVE_U),

    // ---- FRUEHE VERSORGUNG (E-034 v2), nur mit Erwartungskonto und Hauptschalter ----
    //
    // Gilt nur fuer Mahlzeiten mit "Nur Erwartung" am Marker. Alle Mengen stehen
    // ab Werk auf 0 (= keine fruehe Versorgung): persoenliche Werte stehen nur auf
    // dem Geraet. Ein ungueltiger Wert schaltet die fruehe Versorgung ab.

    /** Skalierer des Budgets bei stabilem Start (0..2): 1 = die Kurve, wie eingestellt. */
    EarlySupplyScaleStable("fuse_early_supply_scale_stable", 1.0, 0.0, app.aaps.fuse.core.controller.EarlySupply.MAX_SCALE),

    /** Derselbe Skalierer bei Start nach Tief oder im Fallen. Standard 0: dann keine fruehe Versorgung. */
    EarlySupplyScaleAfterLow("fuse_early_supply_scale_after_low", 0.0, 0.0, app.aaps.fuse.core.controller.EarlySupply.MAX_SCALE),

    /** Obergrenze des Gesamtbudgets je Mahlzeit [U]. Standard 0 = keine fruehe Versorgung. */
    EarlySupplyBudgetMaxU("fuse_early_supply_budget_max_u", 0.0, 0.0, app.aaps.fuse.core.controller.EarlySupply.MAX_BUDGET_U),

    /** Anfangsanteil (0..1) des Budgets, der am Druck ohne Guard fliessen darf (nur mit G1/G2). Standard 0. */
    EarlySupplyInitialShare("fuse_early_supply_initial_share", 0.0, 0.0, 1.0),

    /** Maximale weitere Portion je Zyklus [U]. Standard 0 = keine weiteren Portionen. */
    EarlySupplyMaxPortionU("fuse_early_supply_max_portion_u", 0.0, 0.0, app.aaps.fuse.core.controller.EarlySupply.MAX_PORTION_U),

    /** Hoechstmenge der weiteren Portionen je Zeitraum [U]. Standard 0 = keine weiteren Portionen. */
    EarlySupplyMaxPerPeriodU("fuse_early_supply_max_per_period_u", 0.0, 0.0, app.aaps.fuse.core.controller.EarlySupply.MAX_PER_PERIOD_U),
}

enum class FuseIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val defaultedBySM: Boolean = false,
    override val calculatedDefaultValue: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val exportable: Boolean = true,
) : IntPreferenceKey {

    /**
     * ABLAUFFRIST DES MARKER-PRIME-AUFSCHUBS [min ab Markerdruck]
     * (Punkt 6, Toni 22.08.). Wird BEIM MARKER GEPINNT; nach Ablauf
     * verfaellt der offene Rest sichtbar mit typisiertem Grund -
     * "ueberlebt das Fundamentfenster" heisst ausdruecklich NICHT
     * "bleibt unbegrenzt offen".
     *
     * STARTWERT AUS DEM REPLAY: die Erholungen der Abfall-Marker und die
     * spaeteste Mahlzeitenankunft lagen alle deutlich innerhalb von 120 min
     * nach dem Druck - 120 deckt sie mit Reserve und bleibt weit unter der
     * 360-min-Evidenzepisode.
     */
    DeferredPrimeEndMin("fuse_deferred_prime_end_min", 120, 45, 240),

    /**
     * RESTARTFESTE Wiederbewaffnungs-Sperre des Liveness-Kanals [min]:
     * nach jedem Exit (bestaetigte Abwaertswende, harter Riegel, manuelle
     * Intervention) darf der Kanal so lange nicht neu bewaffnen. Der
     * wirksamste Einzelhebel gegen Oszillations-Re-Arming im Replay
     * (deutlich weniger Risiko-Insulin im Gegenfenster).
     */
    LivenessReArmMin("fuse_liveness_rearm_min", 10, 0, 60),

    /**
     * MARKER-LEISTUNGSFRIST [min] (Bauauftrag Toni 23.08. nachts): so lange
     * nach dem letzten IM PROZESS beobachteten Marker faehrt der
     * Liveness-Kanal die offenen MEAL-Caps; ab der (halb offenen) Deadline
     * gelten die gedaempften CORRECTION-Caps. Die Dauer wird beim Druck
     * GEPINNT - eine spaetere Aenderung wirkt erst auf den naechsten
     * Marker und oeffnet nie eine abgelaufene Frist. Evidenz darf laenger
     * leben, verlaengert dieses Mengenprivileg aber nicht.
     */
    LivenessMealPowerMin("fuse_liveness_meal_power_min", 120, 15, 360),

    /** Ruhe-ZYKLEN bis der Zero-Latch ohne Anstieg loest - s.
     *  [FuseDoubleKey.ZeroLatchCalmDistanceMgdl]. ACHTUNG NAME (Tonis
     *  Review 24.08.): das "Min" im Schluessel ist irrefuehrend - gezaehlt
     *  werden ZUSAMMENHAENGENDE Zyklen (Luecke > 90 s nullt), keine
     *  Wanduhrminuten. Bei 1-min-Takt ist beides gleich; bei gestreckten
     *  Medtrum-Zyklen zaehlt der Zaehler LANGSAMER als die Uhr - die
     *  konservative Richtung. Die UI sagt ehrlich "Ruhe-Zyklen". */
    ZeroLatchCalmExitMin("fuse_zero_latch_calm_exit_min", 20, 5, 120),

    /**
     * VARIANTE 1 des Nullphasen-Vergleichs: der GRUND-WEG-AUSGANG.
     * Zusammenhaengende Zyklen, in denen (a) das LowThreat-Verdikt
     * ausdruecklich NONE ist - der Schutzgrund, der die Null ausgeloest
     * hat, liegt also nicht mehr an - und (b) die Erholung bestaetigt ist
     * (nicht fallend, gesundes Signal, kein Tief/Descent). 0 = AUS, dann
     * gilt unveraendert nur der bisherige Ruhe-Ausgang.
     *
     * ER IST NICHT DER RUHE-AUSGANG. Der loest gegen die "Zero-Falle" bei
     * dauerhaft flachem BG und verlangt zusaetzlich Abstand zum Boden UND
     * das vollstaendige Verschwinden der Bolus-Ueberdeckung. Gemessen hat
     * genau diese Ueberdeckungs-Bedingung die Null nach dem Wegfall des
     * Grundes weitergehalten - der neue Ausgang prueft stattdessen, ob der
     * AUSLOESER selbst noch besteht.
     *
     * WAS ER BEWIRKT UND WAS NICHT: er beendet die verriegelte Null,
     * mehr nicht. Danach gilt wieder das Profilbasal - es gibt keinen Weg,
     * darueber hinauszugehen, weil FUSE keine positive TBR kennt. Ein
     * Nachholen ausgelassener Basalmenge findet ausdruecklich NICHT statt.
     */
    ZeroLatchReasonGoneExitCycles("fuse_zero_latch_reason_gone_exit_cycles", 0, 0, 60),

    /** V-Reversal-Schutz: Rueckblickfenster [min], in dem das
     *  Fall-Minimum den Riegel traegt (Pflichtfall: Minimum 11 min vor
     *  der ersten fraglichen Dosis). */
    ReversalLookbackMin("fuse_reversal_lookback_min", 20, 5, 45),

    /** V-Reversal-Schutz: so viele ZUSAMMENHAENGENDE Zyklen muss das
     *  robuste r positiv sein, bevor die Erholung als echter Anstieg
     *  gilt (90-s-Anschluss; Pflichtfall: r im Dosierzyklus noch negativ). */
    ReversalConfirmCycles("fuse_reversal_confirm_cycles", 2, 1, 6),

    /** Freigabe-Nachlauf: Mindestdauer [min] nach Zero-Latch-Loesung
     *  bzw. Nachtende, in der positive Korrektur-SMBs zu bleiben
     *  (Pflichtfall: ein SMB in den ersten 4 Minuten nach der Kante). */
    RearmHoldMin("fuse_rearm_hold_min", 5, 1, 30),

    /** Freigabe-Nachlauf: so viele zusammenhaengende Aufwaertszyklen
     *  (UKF >= RearmUpUkf) braucht die Freigabe ZUSAETZLICH zur
     *  Mindestdauer - gezaehlt ab der Kante. */
    RearmConfirmCycles("fuse_rearm_confirm_cycles", 2, 1, 6),

    /**
     * iobTH als PROZENT von maxIOB (Variante B, K2-C v0.2 §13).
     *
     * Die autoISF-Legacyformel `percent * 130% * maxIOB * Reduktion` wird
     * ausdruecklich NICHT geerbt — sie rechnet den Prozentsatz durch mehrere
     * Faktoren, von denen einer ein Profilartefakt ist.
     *
     * Prozent und nicht Anteil, weil [app.aaps.fuse.core.controller.IobThreshold]
     * in Prozent rechnet: eine Umrechnung im Adapter waere genau die Sorte
     * doppelter Skalierung, die Variante B beseitigen sollte.
     */
    IobThPercent("fuse_iob_th_percent", 100, 0, 300),

    /** Horizont, auf dem der Bedarf abgelesen wird. */
    ReleaseHorizonMin("fuse_release_horizon_min", 30, 5, 120),

    /** Horizont, ueber den die Guardbahn geprueft wird. */
    LiabilityHorizonMin("fuse_liability_horizon_min", 120, 30, 360),

    /**
     * Zeitkonstante des Antriebszerfalls (Tau) in Minuten.
     *
     * Sie praegt die Bahn staerker als jede andere Zahl hier: sie sagt, wie
     * lange ein gemessener Anstieg in die Zukunft fortgeschrieben wird. 60 ist
     * ein PLATZHALTER, nicht ein Messergebnis — deshalb einstellbar, statt im
     * Code zu stehen. Ein Versuch am Testgeraet soll keine Neuinstallation
     * kosten.
     *
     * Der Bereich 10..240 ist NICHT frei gewaehlt, sondern der Definitionsbereich
     * von [app.aaps.fuse.core.predictor.DriveDecayModel.ExponentialDecay]. Beide
     * muessen gleich bleiben: sonst laesst der Dialog Werte zu, an denen der Kern
     * wirft.
     */
    DriveTauMin("fuse_drive_tau_min", 60, 10, 240),

    /** Absorptions-Fenster des ERKLAERTEN Kredits (Toni 09.08.): ueber diese
     *  Zeit wird die Freigabe-Huelle als erwarteter Anstieg auf die Mittelbahn
     *  gelegt. KUERZER = mehr erwarteter Anstieg je Minute = frueher scharf.
     *  Der eigentliche Aggressivitaets-Regler des Markers; bewusst als
     *  Einstellung, weil das Feintuning im laufenden Betrieb passiert. */
    AbsorptionCreditWindowMin("fuse_absorption_credit_window_min", 60, 20, 180),

    /** Dauer der Marker-SONDERRECHTE ab Druck (erklaerter Kredit + Entwaffnung
     *  der Rebound-Bremsen + Marker-Zweig des Mahlzeit-Fensters). Endet
     *  frueher, sobald nach einem Anstieg eine Wende gelatcht wurde. 0 = keine
     *  Sonderrechte (Marker bleibt Kontext/Anzeige). */
    MarkerBoostMaxMin("fuse_marker_boost_max_min", 45, 0, 90),

    /**
     * WIE LANGE DIE EVIDENZ EIN AKTIVES REBOUND-TOTBAND ENTWAFFNEN DARF [min],
     * gerechnet ab dem MARKERDRUCK (Toni 19.08.).
     *
     * DER GEMESSENE ANLASS: Marker 287 min alt, Rebound noch
     * 32 min offen, Evidenz wieder ACTIVE mit leichtem Anstieg, BG unter
     * der Totband-Schwelle. Eine Handvoll kleiner SMBs, die das Totband ohne die
     * damals UNBEFRISTETE Kredit-Ausnahme geblockt haette.
     *
     * NICHT DIE EVIDENZ WIRD BESCHNITTEN, nur ihr Sonderrecht: die Episode
     * lebt weiter bis zum 360-Minuten-Deckel und darf weiter Bedarf erzeugen.
     *
     * 0 = die Evidenz darf ein Rebound-Totband NIE entwaffnen.
     */
    EvidenceReboundOverrideMaxMin("fuse_evidence_rebound_override_max_min", 120, 0, 180),

    /** Beginn des Nachtfensters [min ab Mitternacht]. 23:00 = 1380. */
    NightStartMin("fuse_night_start_min", 1380, 0, 1439),

    /** Ende des Nachtfensters [min ab Mitternacht]. 07:00 = 420. Gleich dem
     *  Start heisst: Nachtfenster AUS. */
    NightEndMin("fuse_night_end_min", 420, 0, 1439),

    /**
     * Rangstelle in der Verteilung der paarweisen Steigungen, aus der die
     * GUARDBAHN ihren Antrieb nimmt (Prozent).
     *
     * DEFAULT 50 = AUS, und das ist Absicht. Bei 50 ist die Untergrenze der
     * Median selbst, also bitgleich zum Verhalten ohne Band. Wie breit die
     * Verteilung real ist, ist NICHT gemessen — und die Rechnung zeigt, dass
     * eine Spreizung von 1 mg/dl/min die Guardbahn ueber 120 min um bis zu
     * ~51 mg/dl absenkt. Bei einer Guard-Untergrenze von 70 waere das der
     * Unterschied zwischen Sicherheitsabstand und Dauer-Null-TBR.
     *
     * Also: erster Lauf misst Spreizung und Paaranzahl, DANN wird das Quantil
     * aus Daten gesetzt statt aus Plausibilitaet. Eine Spur, live, mit
     * Ein/Aus-Schalter.
     *
     * Zum Minimum ehrlich: bei wenigen Paaren (Untergrenze sind 8) ist ein
     * kleines Quantil wirkungsgleich mit dem kleinsten Einzelpaar —
     * `floor(0.05 * 7) = 0`. Deshalb steht die Paaranzahl im Export.
     */
    DriveLowerQuantilePct("fuse_drive_lower_quantile_pct", 50, 5, 50),

    /**
     * FENSTER DES THEIL-SEN-HAUPTSCHAETZERS [min] (Toni-Vertrag 23.08.).
     *
     * Bisher fest 18 (Candidate-Lock R58). Der Zwei-Tage-Replay durch den
     * echten Runner (ein Problemtag und ein Kontrolltag, TZ-korrigiert)
     * zeigt: W10 weicht am Normaltag praktisch nie ab, entriegelt am
     * Problemtag aber Onset und den Deadlock am Abend ueber den NORMALPFAD
     * (Erstabweichungen vormittags und abends). 18 = bisheriges Verhalten,
     * bitgleich. Struktureller Informations-Lag ~Fenster/2; kuerzer heisst
     * aktueller UND rauschanfaelliger (deutlich mehr Ruhe-Flips bei W12, Phase 1).
     *
     * DOSIERWIRKSAM: steht im Politik-Hash (v22), in policyValues, Backup,
     * Report und in der Methoden-Kennung TS-PS-...-W<min>-.... Ein
     * Fensterwechsel ist ein MODELLWECHSEL: der Evidenz-Bestand wird
     * geschnitten, offene Erwartungen entwertet der neue Hash
     * (Denial.CONFIG_CHANGED) - W18-Erwartungen duerfen nie als
     * W10-Evidenz verbucht werden.
     */
    TheilSenWindowMin("fuse_theil_sen_window_min", 18, 8, 18),

    /**
     * DIE DAUER DES REBOUND-FENSTERS [min] nach dem juengsten Tief
     * (signal.q1 unter [FuseController.REBOUND_LOW_MGDL]). War bis 26.08.
     * fest; [FuseController.REBOUND_WINDOW_MIN] ist nur noch der Default.
     *
     * DER GEMESSENE ANLASS: ein Tief, Rescue-KH, danach Anstieg. Das Totband
     * liess unter Ziel+Band nichts durch - es funktionierte. Mit dem Ende
     * des 45-Minuten-Fensters sprang im SELBEN Zyklus die Ratio vom
     * Korrektur- auf den Anstiegswert und der SMB auf ein Vielfaches.
     * Bis zum naechsten Tief flossen danach mehrere Einheiten, gut die
     * Haelfte aus dem Liveness-Kanal, der im Fenster gesperrt gewesen
     * waere. Dann fiel der BG erneut, tiefer als beim ersten Tief.
     *
     * WAS EINE LAENGERE DAUER TUT UND WAS NICHT: sie verlaengert nicht das
     * Totband allein, sondern ALLES, was am Rebound-Fenster haengt - den
     * SMB-Ratio-Deckel auf smbRatioCorrection, die Liveness-Sperre und die
     * tau-Kuerzung auf [FuseController.REBOUND_TAU_MIN]. Sie verhindert
     * ausdruecklich NICHT die kleinen Korrekturen OBERHALB von Ziel+Band;
     * dafuer ist das Band zustaendig, nicht die Dauer.
     *
     * MINIMUM 45, NICHT KLEINER: eine kuerzere Dauer waere die Abschwaechung
     * eines Schutzes, und dafuer gibt es keinen gemessenen Anlass. Der
     * Bereich oeffnet nur nach oben.
     *
     * DOSIERWIRKSAM: steht im Politik-Hash (v33), in policyValues, Backup und
     * Report. Zwei Laeufe mit 45 und 120 Minuten sind verschiedene Regler.
     *
     * DRITTE WIRKSTELLE, leicht zu uebersehen: [FuseLowMemory] rekonstruiert
     * lastLowTs nach einem Neustart aus dem Trail und verwirft dabei alles
     * aeltere als diese Dauer. Bliebe sie dort fest, kappte jeder Neustart
     * ein laengeres Fenster still auf 45 Minuten - der Schutz waere
     * ausgerechnet nach einem Flash am kuerzesten.
     */
    ReboundWindowMin("fuse_rebound_window_min", 45, 45, 240),

    /** Erwartungskonto: Dauer der Stabilitaetsbedingung [min] (s. FuseDoubleKey). */
    ExpectationStabilityMin(
        "fuse_expectation_stability_min", 15,
        app.aaps.fuse.core.controller.ExpectationAccount.MIN_STABILITY_MIN,
        app.aaps.fuse.core.controller.ExpectationAccount.MAX_STABILITY_MIN,
    ),

    /**
     * Erwartungskonto: Vorschau H [min] - Z(a) = F(min(a + H, Ende)) - F(a).
     * Das Fenster selbst folgt der Markerfrist und hat keinen eigenen
     * Schluessel.
     */
    ExpectationHorizonMin(
        "fuse_expectation_horizon_min", 120,
        app.aaps.fuse.core.controller.ExpectationAccount.MIN_HORIZON_MIN,
        app.aaps.fuse.core.controller.ExpectationAccount.MAX_HORIZON_MIN,
    ),

    /** Fruehe Versorgung: Laenge des Zeitraums fuer die Hoechstmenge der weiteren Portionen [min]. */
    EarlySupplyPeriodMin("fuse_early_supply_period_min", 10, app.aaps.fuse.core.controller.EarlySupply.MIN_PERIOD_MIN, app.aaps.fuse.core.controller.EarlySupply.MAX_PERIOD_MIN),
}

/**
 * Schalter. Bewusst eine eigene Enum-Klasse statt eines Int-Flags: ein
 * Ein/Aus-Zustand gehoert als Schalter in den Bildschirm, nicht als Zahl.
 */
/**
 * Zeitstempel ausserhalb des Einstellungsdialogs. Der Marker ist ZUSTAND,
 * keine Politik - er steht deshalb bewusst NICHT im Politik-Hash, aber in
 * jedem Trail-Datensatz (`onset.mealMarker`).
 */
enum class FuseLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val min: Long = Long.MIN_VALUE,
    override val max: Long = Long.MAX_VALUE,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: app.aaps.core.keys.interfaces.BooleanPreferenceKey? = null,
    override val negativeDependency: app.aaps.core.keys.interfaces.BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
) : app.aaps.core.keys.interfaces.LongPreferenceKey {

    /** 0 = kein Marker. Sonst: Zeitpunkt des Knopfdrucks "Mahlzeit". */
    /** exportable = false: der Marker ist ZUSTAND - ein Settings-Import darf
     *  keinen alten Mahlzeiten-Marker wiederbeleben. */
    MealMarkerArmedTs("fuse_meal_marker_armed_ts", 0, 0, exportable = false),
    /** EPISODENZUSTAND, kein Setting: die im Marker-Dialog getroffene Wahl
     *  "ohne Vorschuss" (Huelle 0 fuer DIESE Episode). Lebt und stirbt mit
     *  [MealMarkerArmedTs] - beim Armen gesetzt, bei der Ruecknahme geloescht.
     *  Als Long statt Boolean, damit er im selben Enum wohnt wie der Anker
     *  und nicht im Einstellungs-Vertrag (fuseEinstellbareKeys) auftaucht. */
    MealMarkerNoPrime("fuse_meal_marker_no_prime", 0, 0, exportable = false),
    /** EPISODENZUSTAND, kein Setting: die im Marker-Dialog getroffene Wahl
     *  "nur Erwartung" (Toni) - keine Direktdosis in DIESER Mahlzeit,
     *  die Huelle bleibt fuer das Erwartungskonto. Lebt und stirbt mit
     *  [MealMarkerArmedTs] wie [MealMarkerNoPrime]. */
    MealMarkerExpectationOnly("fuse_meal_marker_expectation_only", 0, 0, exportable = false),
    /** EPISODENZUSTAND, kein Setting (E-034 v2, Toni): die ausdrueckliche
     *  Bestaetigung "Mahlzeit nach Tief" [ms]. Hebt die Tief-Sperre der
     *  fruehen Versorgung auf, wenn sie nach Sperrbeginn kam und das Tief
     *  vorbei ist; zaehlt nur fuer die Autorisierung, die nach ihr begann
     *  oder sie umfasst (Zeitpunkt ab Marker). */
    EarlySupplyLowConfirmTs("fuse_early_supply_low_confirm_ts", 0, 0, exportable = false),

    /**
     * ALTBESTAND, nur noch LESEND: `armedTs*10 + Stufe`.
     *
     * Der Stempel gab es, damit Zeitpunkt und Stufe nie auseinanderlaufen
     * (Fix-Pass 4 Nr. 16). Mit dem Wegfall der Stufen gibt es nur noch EINEN
     * Wert, und damit ist die Atomaritaet trivial erfuellt - der Stempel hat
     * keinen Zweck mehr.
     *
     * Er wird nicht mehr geschrieben. Gelesen wird er noch als Ruecktausch
     * fuer einen Marker, der beim Update gerade aktiv war: `stamp / 10`
     * liefert den Zeitpunkt fuer jede der drei alten Stufen. Nach einem
     * Markerfenster (45 min) ist der Schluessel bedeutungslos und kann weg.
     */
    MealMarkerStamp("fuse_meal_marker_stamp", 0, 0, exportable = false),

    /**
     * EPISODENZUSTAND, kein Setting: eine VORGEMERKTE Ruecknahme
     * ([app.aaps.fuse.core.controller.MarkerRuecknahme]). Faelligkeit 0 =
     * keine. Persistiert, damit ein Prozessende in der Frist die gewollte
     * Ruecknahme nicht verschluckt - der naechste Zyklus fuehrt sie aus.
     */
    MealMarkerWithdrawDueTs("fuse_meal_marker_withdraw_due_ts", 0, 0, exportable = false),
    /** Der Marker, dem die Vormerkung gilt - ein anderer verwirft sie. */
    MealMarkerWithdrawForTs("fuse_meal_marker_withdraw_for_ts", 0, 0, exportable = false),
    /** Ereignisordnung des Tipps (0 = keine Kennung). */
    MealMarkerWithdrawOrdnung("fuse_meal_marker_withdraw_ordnung", 0, 0, exportable = false),
    /** Quelle des Tipps als Code ([app.aaps.fuse.core.controller.MarkerRuecknahme.Quelle]). */
    MealMarkerWithdrawQuelle("fuse_meal_marker_withdraw_quelle", 0, 0, exportable = false),
}

enum class FuseBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val defaultedBySM: Boolean = false,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
) : BooleanPreferenceKey {

    /**
     * DER ERWARTUNGS-LEDGER - reine Beobachtung, DEFAULT AUS (Toni 18.08.).
     *
     * Er kann keine Dosis veraendern; der Schalter schuetzt nicht davor,
     * sondern vor SCHREIBLAST. Der Recorder schreibt in jedem Zyklus eine
     * Generation mit fsync, Rotation und Rueckleseprobe - bei Ein-Minuten-Takt
     * 1440 mal am Tag, und zwar synchron im Loop-Aufruf. Er kann die bereits
     * bestimmte Dosis nicht mehr aendern, wohl aber den Zyklusabschluss
     * verzoegern.
     *
     * Da nur noch das PRODUKTIVE Geraet zum Messen zur Verfuegung steht
     * (Toni 18.08.: "wir arbeiten nur noch auf dem produktiv geraet"), muss
     * die neue Version ohne diese Last installierbar sein. Erst wenn sie
     * laeuft und nichts stoert, wird der Schalter unter Beobachtung
     * umgelegt - und wieder aus, falls die Zykluszeit leidet.
     */
    ExpectationLedgerEnabled("fuse_expectation_ledger_enabled", false),

    /**
     * ZERO-TBR-LATCH (Bauauftrag Toni 24.08. abends). Der Befund dazu:
     * binnen rund anderthalb Stunden eroeffnete das Low-Tor
     * MEHRFACH eine berechtigte Zero-TBR, und der punktuelle Nutzenwert
     * (benefit < 5) warf sie jeweils binnen Minuten wieder weg - ~79 min
     * Profilbasal liefen in einen vorhersehbaren, langsamen
     * Fall bis in ein Tief. Der Latch verriegelt eine EINMAL berechtigt
     * eroeffnete Null (Verdikt FALLING_WITH_BOLUS_OVERCOVERAGE oder
     * MEASURED_LOW) fuer die Dauer der Fall-Episode:
     * BENEFIT_BELOW_THRESHOLD / FLOOR_BEYOND_HORIZON / NOT_FALLING
     * einzelner Zyklen loesen ihn NICHT. Geloest wird er nur durch
     * belegte gemessene Erholung (dieselbe geteilte Semantik wie der
     * Descent-Latch: UKF >= +0,20 UND roher q1 faellt nicht weiter UND
     * kein Descent-/Low-Risiko, drei lueckenlose Zyklen; jeder negative
     * Zyklus nullt den Zaehler) ODER den Ruhe-Ausgang (s.
     * [FuseIntKey.ZeroLatchCalmExitMin]). NUR die Basalachse: der
     * positive Insulinpfad bleibt strukturell unberuehrt
     * (latchZeroOnly-Weg im Translator). Restartfest; Default AUS.
     */
    ZeroLatchEnabled("fuse_zero_latch_enabled", false),

    /**
     * V-REVERSAL-SCHUTZ, nur im Korrekturkontext (Bauauftrag Toni 25.08.,
     * Pflichtfall: Korrektur-SMBs auf die Erholung eines Sensor-V bei
     * negativem robustem r). Nach steilem Fall loest eine schnelle
     * Gegenbewegung keine Korrektur-SMBs aus, solange das robuste r
     * negativ oder unbestaetigt ist. Kein Carry; Mahlzeitenpfade
     * (Marker/Prime/Fundament/Liveness-MEAL) bleiben unberuehrt.
     * Default AUS.
     */
    CorrectionReversalGuardEnabled("fuse_correction_reversal_guard_enabled", false),

    /**
     * FREIGABE-NACHLAUF nach Zero-Latch-Loesung/Nachtende, nur im
     * Korrekturkontext (Pflichtfall: ein SMB in der ersten
     * Minute nach der Nachtband-Kante, danach fiel der BG). Die Kante
     * oeffnet positive Korrektur-SMBs erst nach Mindestdauer UND
     * zusammenhaengend bestaetigter Aufwaertslage. Der Zero-Latch bleibt
     * als zweite Schutzlinie unveraendert. Default AUS.
     */
    PositiveCorrectionRearmEnabled("fuse_positive_correction_rearm_enabled", false),

    /**
     * STUFENWEISE BASALRUECKKEHR aus einer Schutz-Null (AUS = bisheriges
     * Verhalten, bitgleich).
     *
     * Statt binaer zwischen voller Null und vollem Profilbasal zu
     * springen, laeuft nach drei zusammenhaengenden ruhigen Zyklen ein
     * ANTEIL des Profilbasals weiter
     * (aus BasalRecoverySearch: die groesste Rate, die die Schutzbahn
     * noch traegt), waehrend die Verriegelung
     * sicherheitswirksam bestehen bleibt. Kehrt ein Schutzgrund zurueck,
     * gilt im selben Zyklus wieder die volle Null.
     *
     * WAS ES NICHT IST: keine positive TBR, kein Ausgleich der
     * ausgelassenen Menge, kein Uebertrag - und waehrend der Teilstufe
     * fliesst KEIN SMB. Die volle Rueckkehr bleibt am strengeren
     * Ruhe-Ausgang.
     */
    PartialRecoveryEnabled("fuse_partial_recovery_enabled", false),

    /**
     * DER MARKER-PRIME-AUFSCHUB - DEFAULT AUS (Punkt 6, Bau-GO Toni 22.08.,
     * KEIN Aktivierungs-GO).
     *
     * DOSIERWIRKSAM in beide Richtungen: eingeschaltet haelt er
     * markerautorisiertes Insulin bei gemessenem, ueberdecktem Fall mit
     * Boden im gepinnten [FuseDoubleKey.MarkerPrimeDescentHorizonMin]
     * zurueck (statt es zu liefern) und gibt den offenen Rest nach
     * bestaetigter Erholung mit hoechstens EINEM Pumpenschritt je Zyklus
     * wieder frei - bis zur gepinnten Frist
     * [FuseIntKey.DeferredPrimeEndMin], danach verfaellt er sichtbar.
     * Er schuetzt NUR gegen Liefern in den gemessenen Fall; eine zu grosse
     * Huelle bei flachem oder steigendem BG bleibt moeglich.
     */
    DeferredPrimeEnabled("fuse_deferred_prime_enabled", false),

    /**
     * DER LIVENESS-KANAL - DEFAULT AUS (Bauvertrag Toni + Codex 22.08.
     * nachts, kein Aktivierungs-GO). DOSIERWIRKSAM: eingeschaltet macht er
     * bei bestaetigtem, gemessen NICHT fallendem Hochdruck (BG > 160,
     * r >= 1,0 ueber drei Zyklen, Guard/Tail sperren den Normalpfad) den
     * bereits erkannten Mittelbahn-Bedarf dosierbar - final = max(normal,
     * liveness), nie Addition; Tail ist im Kanal weder Veto noch Kappe;
     * kumulativ begrenzt durch min(globales iobTH, Kontext-Exposure-Limit
     * des Dosierprofils, maxIOB). Gemessene
     * Riegel (Fallen, Tief, Rebound, Hold) bleiben absolut; Exit bei
     * bestaetigter Wende oder manueller Intervention mit restartfester
     * Sperre [FuseIntKey.LivenessReArmMin].
     */
    LivenessChannelEnabled("fuse_liveness_channel_enabled", false),

    /**
     * WIEDEREINSTIEG NACH CGM-FUNKLUECKE (Toni 25.08. abends).
     *
     * Eingeschaltet reift der Antriebsschaetzer NUR nach einer eindeutig
     * identifizierten echten Funkluecke frueher (4 Punkte / 3 Paare statt
     * 5 / 8) - und nur innerhalb von 10 min nach Segmentbeginn und nur
     * bei einer Luecke bis 10 min. NIEMALS bei Kaltstart, Sensorwechsel,
     * Kalibrierung, Eingangssprung oder wenn die Reihe gar nicht
     * unterbrochen war (Schleifenpause).
     *
     * DOSIERWIRKUNG: gemessen ueber 9 echte Luecken einer Woche
     * rund zwei gesparte Minuten je Luecke bei vernachlaessigbarer Mehrmenge ueber einen
     * ganzen Tag. Der Wiedereinstieg erlaubt wieder eine ENTSCHEIDUNG -
     * er umgeht kein Sicherheitsgate: Guards, Low-/Descent-Riegel, Tail
     * und die technischen Tore gelten unveraendert. In drei von fuenf
     * Replay-Laeufen war die einzige Wirkung, dass ein Zyklus von
     * "blind abgebrochen" zu "aus benanntem Grund geblockt" wechselte.
     */
    SignalRejoinEnabled("fuse_signal_rejoin_enabled", false),

    /**
     * DER MARKER AUTORISIERT INSULIN BEI GEMESSENEM TIEF (Tonis Entscheidung,
     * 11.08.).
     *
     * Damit hoert der Mahlzeiten-Knopf auf, ein blosser Kontextmarker zu sein,
     * und wird zu einer INSULIN-AUTORISIERENDEN Handlung. Das ist die
     * folgenreichste Einstellung in FUSE.
     *
     * WAS SIE FREIGIBT: ausschliesslich den markerfinanzierten Anteil - also
     * die Sofort-Freigabe aus der Marker-Huelle. Das ist keine zusaetzliche
     * Regel, sondern strukturell: bei LOW ist die Basisentscheidung IMMER 0,
     * also ist alles, was danach herauskommt, der Lift und nichts sonst. Eine
     * normale Korrekturdosis kann diesen Weg nicht nehmen.
     *
     * WAS SIE NICHT ANFASST: Signalfehler, unbekanntes IOB, Ledger-Hold,
     * Pumpen-Gates, Schwanz-Haftung. Und das schuetzende Zero-Temp laeuft
     * unveraendert weiter - es wird nicht "LOW abgeschaltet", sondern eine
     * bewusste manuelle Entscheidung praezise umgesetzt.
     *
     * DEFAULT AUS, und das gegen die sonstige Praxis in diesem Projekt
     * (Schalter stehen hier auf AN, damit nichts still spaeter wirkt). Der
     * Grund ist der einzige Unterschied, der zaehlt: ein versehentliches
     * Mitwandern auf ein Geraet mit ECHTER Pumpe ist hier qualitativ etwas
     * anderes als bei jedem anderen Schalter. Einmal umlegen ist genau die
     * bewusste Handlung, um die es bei diesem Knopf ohnehin geht.
     */
    MarkerAuthorisesRelease("fuse_marker_authorises_low", false),

    /**
     * Schwanz-Guard: bewertet die unvermeidbare Restwirkung HINTER dem
     * Haftungshorizont.
     *
     * Er rechnet heute nur EINEN der drei Terme aus R79-F4 — die beiden
     * anderen brauchen den Commitment-Ledger und den verdrahteten
     * Einheitskern. Deshalb traegt jede seiner Zahlen einen
     * Unvollstaendigkeitsvermerk.
     *
     * DEFAULT FALSE, und das ist eine bewusste Entscheidung gegen den ersten
     * Reflex. Rechnung mit den Defaults: lowerBgAtH 120, Schwanzuntergrenze 70,
     * ISF 50 ergeben ein Budget von 1,0 U. Das IOB am 120-min-Horizont liegt
     * bei langer DIA im FCL regelmaessig darueber - der Guard wuerde den schnellen
     * Kanal also nicht gelegentlich bremsen, sondern weitgehend schliessen. Ob
     * das so ist, ist NICHT gemessen; ein Default, der die Dosierung
     * flaechendeckend stilllegt, waere eine unbeschlossene Norm.
     *
     * Der Schalter macht aus dieser Unsicherheit eine Einstellung statt eines
     * Flashs: einschalten kostet fuenf Sekunden am Geraet. Solange er aus ist,
     * wird der Schwanz gar nicht erst bewertet - im Grund steht dann auch kein
     * tail=-Abschnitt. Wer messen will, schaltet ihn ein.
     */
    TailGuardEnabled("fuse_tail_guard_enabled", false),

    /**
     * Der oeffnende schnelle Kanal (OnsetChannel): Bruecke zwischen "UKF sieht
     * den Anstieg" und "Theil-Sen bestaetigt". NICHT einseitig - er hebt die
     * Mittelbahn. Gates: 3-min-Persistenz auf der ROHEN UKF-Rate, Ausreisser,
     * Haftungshuelle, Uebergabe an die Rampe bei r >= riseRampLowR.
     */
    OnsetChannelEnabled("fuse_onset_channel_enabled", true),

    /**
     * Eine laufende Schutz-Null endet, sobald ihr Grund im aktuellen Zyklus
     * nachweislich weg ist - Rueckfall auf Profilbasal per Abbruch, NIE eine
     * positive Rate.
     *
     * DEFAULT AN (Toni 15.08.), und der Anlass ist gemessen: der einzige
     * aktive Ausgang war bis dahin KEEP_CANCEL_STALE_ZERO, und der verlangt
     * einen Zyklus, der bis BELOW_PUMP_INCREMENT durchlaeuft - hinter Guard,
     * Schwanz oder Totband entsteht der nie. Im mehrtaegigen Trail wurde nur
     * ein kleiner Bruchteil der gesetzten Nullen abgebrochen (nachts fast keine); die Null
     * ueberdauerte ihren Grund je Nacht deutlich mehr als eine Stunde. Das zurueckgehaltene
     * Basal finanziert ueber die Bedarfsseite die Morgen-SMBs.
     *
     * AUS = Verhalten wie vor dem 15.08., bitgleich. Der Schalter bleibt, weil
     * er der Rueckweg ist: die Wirkung ist am Geraet noch ungemessen.
     */
    TbrEndZeroWhenReasonGone("fuse_tbr_end_zero_when_reason_gone", true),

    /**
     * FRUEHE VERSORGUNG (E-034 v2), Default AUS. Nur mit eingeschaltetem
     * Erwartungskonto und nur fuer Mahlzeiten mit "Nur Erwartung" am Marker:
     * statt der Huelle ein Gesamtbudget aus Kurve, Insulinwirkung und
     * Skalierer, geliefert als Anfangsportion und weitere Portionen. Aus:
     * bitgleich zum Konto ohne fruehe Versorgung.
     */
    EarlySupplyEnabled("fuse_early_supply_enabled", false),

    /** G1: die Anfangsportion hebt das Guard-Urteil durch die Markerautorisierung. Default AUS. */
    EarlySupplyInitialLiftsGuard("fuse_early_supply_initial_lifts_guard", false),

    /** G2: die Anfangsportion hebt die Schwanzpruefung durch die Markerautorisierung. Default AUS. */
    EarlySupplyInitialLiftsTail("fuse_early_supply_initial_lifts_tail", false),

    /** G3: weitere Portionen pruefen den Guard mit der angekuendigten Mahlzeit. Default AUS. */
    EarlySupplyGuardWithExpectation("fuse_early_supply_guard_with_expectation", false),

    /** G4: weitere Portionen pruefen den Schwanz mit der angekuendigten Mahlzeit. Default AUS. */
    EarlySupplyTailWithExpectation("fuse_early_supply_tail_with_expectation", false),
}

/**
 * Texteinstellungen. Bisher nur die Erwartungskurve des Erwartungskontos -
 * 24 Stuetzwerte als EIN Feld statt 24 Zahlenzeilen im Menue.
 */
enum class FuseStringKey(
    override val key: String,
    override val defaultValue: String,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = false,
    override val showInPumpControlMode: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true,
) : StringPreferenceKey {

    /**
     * Die Erwartungskurve F(5) bis F(120) [U kumulierte Stoerung ab Marker],
     * getrennt durch Semikolon oder Leerraum. Leer (Standard) oder ungueltig
     * (Anzahl, Zahlwert, nicht monoton, Ende nicht positiv): Erwartung aus,
     * nur Ersetzung. Persoenliche Werte stehen nur auf dem Geraet.
     */
    ExpectationCurve("fuse_expectation_curve", ""),
}

/**
 * ENTFALLENE EINSTELLUNGEN (Konsolidierung 2.0) - ohne Wirkung, in keinem Bildschirm, und FUSE liest sie nie.
 *
 * Sie bleiben REGISTRIERT, damit eine AAPS-Sicherung ihre gespeicherten Werte weiter mitnimmt: die Sicherung
 * schreibt nur registrierte Schluessel, und der Import loescht vor dem Schreiben alles (`sp.clear()`). Ohne diese
 * Liste verloere jede Sicherung aus 2.0 diese Werte still - und der Rueckweg ueber die 1.x-APK, die sie wieder
 * liest, fiele auf deren Vorgaben zurueck. 2.0 entfernt Code, keine gespeicherten Einstellungen. Eine alte
 * Sicherung mit diesen Schluesseln laedt unveraendert: sie werden zurueckgeschrieben und bleiben ungelesen.
 *
 * Kein Einstellungstyp: die Sicherung behandelt jeden gespeicherten Wert gleich, Grenzen und Vorgaben braucht
 * nur ein Schluessel, der gelesen wird.
 */
enum class FuseRetiredKey(
    override val key: String,
    override val exportable: Boolean = true,
) : NonPreferenceKey {

    // Sofortanteil und Ruhe-Ausgang
    MealFoundationPhaseAUpfrontShare("fuse_meal_foundation_phase_a_upfront_share"),
    CalmRecoveryEnabled("fuse_calm_recovery_enabled"),
    CalmRecoveryCycles("fuse_calm_recovery_cycles"),
    CalmRecoveryMinUkf("fuse_calm_recovery_min_ukf"),
    CalmRecoveryGuardDistanceMgdl("fuse_calm_recovery_guard_distance"),
    CalmTreatmentMode("fuse_calm_treatment_mode"),

    // Sofort-Freigabe am Marker (Prime)
    PrimeReleaseEnabled("fuse_prime_release_enabled"),
    PrimeWindowMin("fuse_prime_window_min"),

    // Mahlzeitenfundament
    MealFoundationEnabled("fuse_meal_foundation_enabled"),
    MealFoundationPhaseAShare("fuse_meal_foundation_phase_a_share"),
    MealFoundationEndMin("fuse_meal_foundation_end_min"),

    // Erwartungskonto v1 (Hauptschalter, Vorgriff phi_0, Guard-Ausnahme)
    ExpectationAccountEnabled("fuse_expectation_account_enabled"),
    ExpectationPhi0Stable("fuse_expectation_phi0_stable"),
    ExpectationPhi0AfterLow("fuse_expectation_phi0_after_low"),
    ExpectationGuardExceptionEnabled("fuse_expectation_guard_exception_enabled"),

    // Mahlzeitenteile und Experimente des Liveness-Kanals (MEAL-Schwelle und -Bewaffnung, Rebound-Evidenz-Ausnahme,
    // Buchungsausgang, Halte-Anhebung, H8)
    LivenessBgMinMealMgdl("fuse_liveness_bg_min_meal_mgdl"),
    MealArmCycles("fuse_meal_arm_cycles"),
    LivenessReboundEvidenceExceptionEnabled("fuse_liveness_rebound_evidence_exception_enabled"),
    LivenessBookingExitWithoutReArmEnabled("fuse_liveness_booking_exit_without_rearm_enabled"),
    LivenessDriveHoldEnabled("fuse_liveness_drive_hold_enabled"),
    EarlyAdaptiveMealHorizonMin("fuse_early_adaptive_meal_horizon_min"),

    // Schon in Stufe 1 (K1-K3) nicht mehr gelesen: Prognose-Sammler, Serien-Deckel, fest eingeschaltete Mechanismen
    ForecastShadowCollectionEnabled("fuse_forecast_shadow_collection_enabled"),
    CorrectionSeriesCapU("fuse_correction_series_cap_u"),
    CorrectionSeriesWindowMin("fuse_correction_series_window_min"),
    ConditionalTailEnabled("fuse_conditional_tail_enabled"),
    FastRestraintEnabled("fuse_fast_restraint_enabled"),
    NightDeadbandEnabled("fuse_night_deadband_enabled"),
    ReboundDeadbandEnabled("fuse_rebound_deadband_enabled"),
}

/**
 * Alle FUSE-Schluesselklassen, die das Plugin beim Start registriert (`ownPreferences`) - EINE Liste fuer Plugin
 * und Waechter: was hier fehlt, nimmt keine Sicherung mit.
 */
internal val fuseSchluesselKlassen: List<Class<out NonPreferenceKey>> = listOf(
    FuseDoubleKey::class.java, FuseIntKey::class.java, FuseBooleanKey::class.java, FuseLongKey::class.java,
    FuseStringKey::class.java, FuseRetiredKey::class.java,
)
