package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey

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
    /**
     * Kleinste UKF-Rate, die noch als "ruhig" gilt [mg/dl/min].
     *
     * MINIMUM 0,0, nicht negativ: `FLOOR_BEYOND_HORIZON` kann das AKTUELLE
     * Risiko aufheben, waehrend die UKF-Rate noch deutlich negativ ist.
     * Eine negative Schwelle liesse den Sofortbatch dann auf einer weiter
     * fallenden Kurve zuenden, sofern q1 gerade nicht faellt.
     */
    CalmRecoveryMinUkf("fuse_calm_recovery_min_ukf", 0.0, 0.0, 1.0),

    /**
     * Mindestabstand von q1 zum Guard-Boden fuer die Ruhefreigabe [mg/dl].
     *
     * MINIMUM 5, nicht 0: bei 0 waere eine Freigabe unmittelbar AM
     * Guard-Boden einstellbar. 5 ist der bislang untersuchte Kandidat.
     */
    CalmRecoveryGuardDistanceMgdl("fuse_calm_recovery_guard_distance", 5.0, 5.0, 100.0),

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
    // Liveness-Deadlock-Messung: eine Mahlzeit braucht 5-7 U, waehrend
    // die Huelle bei 3,75/60 min deckelte.
    PrimeEnvelopeU("fuse_prime_envelope_u", 1.2, 0.0, 6.0),

    /**
     * ANTEIL VON PHASE A AM GEMEINSAMEN MAHLZEITENBUDGET.
     *
     * 1.0 ist der HEUTIGE Stand: alles sofort, kein Fundament. Tonis
     * Replay-Kandidat vom 18.08. ist 0.75; zu pruefen sind ausserdem 0.80 und
     * 0.67. Der Default bleibt 1.0, damit ein Flash das Verhalten NICHT
     * aendert - das Fundament ist eine eigene, spaeter zu treffende
     * Therapieentscheidung.
     *
     * NUR DER ANTEIL IST EINSTELLBAR, nicht die absoluten Mengen: die ergeben
     * sich aus [PrimeEnvelopeU]. Zwei Knoepfe fuer dieselbe Menge waeren zwei
     * Wahrheiten, und eine davon veraltet (Spezifikation 13.7).
     */
    MealFoundationPhaseAShare("fuse_meal_foundation_phase_a_share", 1.0, 0.5, 1.0),

    /**
     * PHASE-A-SOFORTANTEIL nach iLet-Prinzip (Bauauftrag Toni 24.08.).
     *
     * `upfrontU = phaseABudgetU x UpfrontShare` wird im ersten berechtigten
     * Zyklus nach dem Markerdruck SOFORT angefordert; der Rest laeuft
     * weiter linear ueber das Prime-Fenster. Anlass: wiederkehrende fruehe
     * Mahlzeitenpeaks - die Phase-A-Menge ist nicht zwingend zu klein, sie
     * kommt zu spaet (iLet liefert ~75 % des gelernten Mahlzeitenbedarfs
     * als unmittelbare Dosis).
     *
     * Default 0,00 = BITGLEICH heutiges Verhalten. GEPINNT beim Armen wie
     * die Geschwister: eine Aenderung wirkt erst beim naechsten frischen
     * Marker. Die Sofortdosis wird als typisierte Quelle MEAL_UPFRONT
     * gefuehrt und NICHT von maxSMB zerteilt; PrimeWindowMin bleibt
     * unveraendert die Phasengrenze zu Phase B (Lieferkurve und
     * Phasengrenze sind zwei verschiedene Groessen). NUR DER ANTEIL ist
     * einstellbar, die Menge folgt aus [PrimeEnvelopeU] x
     * [MealFoundationPhaseAShare] - eine Wahrheit, kein zweiter Knopf.
     */
    MealFoundationPhaseAUpfrontShare("fuse_meal_foundation_phase_a_upfront_share", 0.0, 0.0, 1.0),

    /**
     * V-REVERSAL-SCHUTZ (Bauauftrag Toni 25.08., Pflichtfall 06:27): die
     * Fall-Schwelle [mg/dl/min] - so tief muss das UKF-Minimum im
     * Rueckblick gelegen haben, damit "steiler Fall" gilt (Pflichtfall:
     * -2,81). Nur Korrekturkontext; s. CorrectionReversalGuard.
     */
    ReversalFallUkf("fuse_reversal_fall_ukf", 2.0, 0.5, 5.0),

    /** Gegenbewegungs-Schwelle [mg/dl/min] - ab dieser schnellen
     *  Erholungsrate greift der Riegel (Pflichtfall: +4,0). */
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
     * nicht "Rate mal Zeit". An Tonis Profil (0,60 U/h, ISF 63, Lyumjev
     * peak 45 / DIA 9 h):
     *
     *     Vorlauf   20 min   30 min   60 min   90 min   120 min
     *     Wirkung    0,4      1,1      6,2     15,8      28,7  mg/dl
     *
     * Der Default 5 liegt an der Messbarkeitsgrenze: darunter ist der Effekt
     * kleiner als das Sensorrauschen, und eine Massnahme, deren Erfolg man
     * nicht sehen kann, ist keine. Praktisch heisst das rund 55 Minuten
     * Vorlauf - Tonis Schaetzung "mindestens 1 Stunde" lag richtig.
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
     * VARIANTE 2 des Nullphasen-Vergleichs: DECKEL DER MARKERLOSEN
     * KORREKTURSERIE [U] im rollierenden Fenster
     * [FuseIntKey.CorrectionSeriesWindowMin]. 0 = AUS.
     *
     * Gemessener Anlass: eine markerlose Korrekturserie schuettete in
     * einer halben Stunde ein Mehrfaches des statischen Korrekturbedarfs
     * aus, und die lange Schutz-Null danach war die Gegenreaktion darauf.
     * Der Deckel begrenzt die SERIE, nicht den einzelnen SMB.
     *
     * ER FASST DIE BASALACHSE NICHT AN: er wirkt ausschliesslich im
     * Exposure-Gate, das nur SMB-Mengen begrenzt. Null-TBR, Zero-Latch und
     * Schutzgruende laufen unveraendert.
     *
     * GEZAEHLT WIRD, WAS DEN TRANSPORT UEBERSTANDEN HAT: die Buchung wird
     * bei Verwurf und bei bewiesenem Nicht-Senden zurueckgedreht.
     */
    CorrectionSeriesCapU("fuse_correction_series_cap_u", 0.0, 0.0, 5.0),

    /**
     * Nahhorizont des harten Endriegels fuer NEUES positives Insulin [min].
     * Getrennt vom 120-minuetigen TBR-Nutzenfenster: Basal rechtzeitig
     * zurueckhalten und einen Mahlzeiten-SMB hart verbieten sind zwei
     * verschiedene Entscheidungen. Der Live-Replay vom 21.08. laesst bei
     * 30 min noch vier fruehe Schritte zu und sperrt vor der akuten Kante;
     * 120 min sperrte die komplette Phase A bereits bei BG 88.
     */
    PositiveDescentHorizonMin("fuse_positive_descent_horizon_min", 30.0, 15.0, 60.0),

    /**
     * Nahhorizont des Abwaertsriegels NUR fuer markerautorisiertes Insulin
     * [min] (Punkt 6, Toni 22.08.). Reine Korrekturen behalten
     * [PositiveDescentHorizonMin]; dieser Wert wird BEIM MARKER GEPINNT und
     * gilt fuer dessen ganze Laufzeit. Der Replay der drei Markerfaelle:
     * 60 schob am 21.08. 18:19 alle 1,20 U auf (Boden 36-61 min) und liess
     * die Gutfaelle 14:21/08:59 unangetastet (Boden >= 63 min) - Marge nur
     * 3-5 min bei n=3, darum konfigurierbar statt festgenagelt.
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
     * Startsatz-Begruendung (29.08.): CORR 3,0 U haette den 27.08.-Burst
     * auf ~1,15 U begrenzt (2,5 -> 0,45 war der scharfe Kandidat);
     * MEAL 7,0 U laesst nach ~1 U Alt-IOB + 5 U Direktdosis noch
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
     * Nacht-Totband liegt bei ~143, eine eigene Nachtschwelle (Kandidat
     * 160) greift auch dann, wenn das Totband ueberschritten oder
     * entwaffnet ist. Rebound und gemessene Riegel bleiben unberuehrt.
     *
     * MIGRATION: solange dieser Schluessel NIE gesetzt wurde, folgt die
     * Nachtschwelle zur Laufzeit der Tagesschwelle (getIfExists-Fallback im
     * Config-Bau) - ein Update veraendert nichts still. Der Default hier
     * greift nur fuer die Bildschirm-Anzeige vor dem ersten Setzen.
     */
    LivenessBgMinNightMgdl("fuse_liveness_bg_min_night_mgdl", 160.0, 100.0, 250.0),

    /**
     * ERWEITERUNG M1 (Bauauftrag 7.5.1, Toni 29.08.): eigene Druckschwelle
     * des Liveness-Kanals unter GUELTIGER MEAL-Vollmacht [mg/dl, ABSOLUT].
     *
     * Beleg: 55 min Verzug am Abend 28.08. und 35 min am Fruehstueck
     * 29.08. - die Korrektur-Schwelle (140/160) verhinderte unter stehender
     * Marker-Autorisierung jede Druckzaehlung, waehrend r laengst >= 1 lief
     * und der Normalpfad GUARD-gedeckelt war.
     *
     * ECHTER Startsatz-Default 110 (Toni 29.08. nachts, aus den beiden
     * Mahlzeitenfaellen: ~45/35 min frueherer Druck als 140, ohne direkt
     * ueber Ziel zu zuenden) - der fruehere Tag-/Nacht-Fallback ist mit
     * dem CENTRAL-only-Cleanup beendet. CORRECTION behaelt Tag/Nacht
     * IMMER. Untergrenze 80: unter der Vollmacht darf die Schwelle nahe
     * an den Zielbereich, aber nie unter den Guard-Boden-Bereich - die
     * gemessenen Riegel (Tief, Fallen, Rebound) bleiben davon unberuehrt
     * absolut.
     */
    LivenessBgMinMealMgdl("fuse_liveness_bg_min_meal_mgdl", 110.0, 80.0, 250.0),

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
     * ENDE DES PHASE-B-FENSTERS [min ab Markerdruck] (Toni 18.08.).
     *
     * Bis hierhin verteilt das Mahlzeitenfundament sein Teilbudget. Danach
     * ist es fertig - der Rest verfaellt, statt spaeter in einer Lage zu
     * landen, fuer die er nie gedacht war.
     *
     * Die Untergrenze liegt ueber dem Prime-Fenster (15 min): ein Ende davor
     * ergaebe gar kein Phase-B-Fenster.
     */
    MealFoundationEndMin("fuse_meal_foundation_end_min", 60, 20, 180),

    /**
     * ABLAUFFRIST DES MARKER-PRIME-AUFSCHUBS [min ab Markerdruck]
     * (Punkt 6, Toni 22.08.). Wird BEIM MARKER GEPINNT; nach Ablauf
     * verfaellt der offene Rest sichtbar mit typisiertem Grund -
     * "ueberlebt das Fundamentfenster" heisst ausdruecklich NICHT
     * "bleibt unbegrenzt offen".
     *
     * STARTWERT AUS DEM REPLAY: die Erholungen der drei Abfall-Marker
     * (20.08. 20:22, 21.08. 09:46, 21.08. 18:19) lagen 34-71 min nach dem
     * Druck, die spaeteste Mahlzeitenankunft bei 67 min - 120 deckt alle mit
     * Reserve und bleibt weit unter der 360-min-Evidenzepisode.
     */
    DeferredPrimeEndMin("fuse_deferred_prime_end_min", 120, 45, 240),

    /**
     * RESTARTFESTE Wiederbewaffnungs-Sperre des Liveness-Kanals [min]:
     * nach jedem Exit (bestaetigte Abwaertswende, harter Riegel, manuelle
     * Intervention) darf der Kanal so lange nicht neu bewaffnen. Der
     * wirksamste Einzelhebel gegen Oszillations-Re-Arming im Replay
     * (Risiko 1,85 -> 1,35 U im 21.08.-Gegenfenster).
     */
    LivenessReArmMin("fuse_liveness_rearm_min", 10, 0, 60),

    /**
     * ERWEITERUNG M3 (Bauauftrag 7.5.5, Toni 29.08.): Bewaffnungszyklen
     * des Liveness-Kanals unter GUELTIGER MEAL-Vollmacht.
     *
     * CORRECTION bleibt IMMER bei den drei Druckzyklen (ARM_STREAK) -
     * autoISF-artige Sofortreaktion gehoert nur der autorisierten
     * Mahlzeit. Startsatz-Default 1 (Toni 29.08. nachts): unter der
     * ausdruecklichen Vollmacht reagiert der Kanal im ersten vollstaendig
     * passenden Zyklus.
     */
    MealArmCycles("fuse_meal_arm_cycles", 1, 1, 10),

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

    /** Rollierendes Fenster [min] des Serien-Deckels
     *  [FuseDoubleKey.CorrectionSeriesCapU]. Ohne Deckel wirkungslos. */
    CorrectionSeriesWindowMin("fuse_correction_series_window_min", 30, 10, 120),

    /** V-Reversal-Schutz: Rueckblickfenster [min], in dem das
     *  Fall-Minimum den Riegel traegt (Pflichtfall: Minimum 11 min vor
     *  der ersten fraglichen Dosis). */
    ReversalLookbackMin("fuse_reversal_lookback_min", 20, 5, 45),

    /** V-Reversal-Schutz: so viele ZUSAMMENHAENGENDE Zyklen muss das
     *  robuste r positiv sein, bevor die Erholung als echter Anstieg
     *  gilt (90-s-Anschluss; Pflichtfall: r -0,82 im Dosierzyklus). */
    ReversalConfirmCycles("fuse_reversal_confirm_cycles", 2, 1, 6),

    /** Freigabe-Nachlauf: Mindestdauer [min] nach Zero-Latch-Loesung
     *  bzw. Nachtende, in der positive Korrektur-SMBs zu bleiben
     *  (Pflichtfall: 0,35 U in den ersten 4 Minuten nach der Kante). */
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
    /**
     * Ueber wieviele Minuten die Freigabe-Huelle verteilt wird.
     *
     * ZWEITER REGLER NEBEN DER MENGE (Toni 16.08.): "also zusaetzlich zur
     * Huellengroesse, das Fenster ueber welches das Insulin abgegeben werden
     * soll - so waere man flexibel". Anlass war das Haferflocken-Fruehstueck:
     * die vollen 3,0 U flossen in ZEHN Minuten ab, danach sperrte der Guard
     * bei IOB 3,70 zwei Stunden - genau als die Resorption lief. Dieselbe
     * Menge ueber 25 Minuten haette denselben Vorlauf bei kleinerer
     * IOB-Spitze zum Resorptionszeitpunkt.
     *
     * Obergrenze ist die Wanduhr-Kappe des Markers (45 min); darueber hinaus
     * gaebe es nichts mehr zu verteilen.
     */
    PrimeWindowMin("fuse_prime_window_min", 15, 5, 45),


    /** Dauer der Marker-SONDERRECHTE ab Druck (erklaerter Kredit + Entwaffnung
     *  der Rebound-Bremsen + Marker-Zweig des Mahlzeit-Fensters). Endet
     *  frueher, sobald nach einem Anstieg eine Wende gelatcht wurde. 0 = keine
     *  Sonderrechte (Marker bleibt Kontext/Anzeige). */
    MarkerBoostMaxMin("fuse_marker_boost_max_min", 45, 0, 90),

    /**
     * WIE LANGE DIE EVIDENZ EIN AKTIVES REBOUND-TOTBAND ENTWAFFNEN DARF [min],
     * gerechnet ab dem MARKERDRUCK (Toni 19.08.).
     *
     * DER GEMESSENE ANLASS: 19.08., 13:41 - Marker 287 min alt, Rebound noch
     * 32 min offen, Evidenz wieder ACTIVE mit +0,42 mg/dl/min, BG 109,8 gegen
     * eine Schwelle von 138. Fuenf Zyklen, 0,35 U, die das Totband ohne die
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
     * echten Runner (22.08. Problemtag / 21.08. Kontrolltag, TZ-korrigiert)
     * zeigt: W10 weicht am Normaltag praktisch nie ab, entriegelt am
     * Problemtag aber Onset und Abendessen-Deadlock ueber den NORMALPFAD
     * (Erstabweichungen 09:50 und 17:49). 18 = bisheriges Verhalten,
     * bitgleich. Struktureller Informations-Lag ~Fenster/2; kuerzer heisst
     * aktueller UND rauschanfaelliger (+55% Ruhe-Flips bei W12, Phase 1).
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
     * DER GEMESSENE ANLASS (Toni, 26.08.). Tief um 12:07, Nadir q1 66,1,
     * Rescue-KH, Anstieg. Das Totband liess unter Ziel+Band exakt 0,00 U
     * durch - es funktionierte. Um 13:10:37 endete das 45-Minuten-Fenster,
     * und im SELBEN Zyklus sprang die Ratio von 0,15 auf 0,325 und der SMB
     * von 0,10 auf 0,50 U. Bis zum naechsten Tief flossen danach 3,45 U,
     * davon 1,85 U aus dem Liveness-Kanal, der im Fenster gesperrt gewesen
     * waere. Um 15:15 fiel der BG erneut, Nadir q1 54,6.
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

    /**
     * Lueckenlose Ruhezyklen bis zur Freigabe - s.
     * [FuseBooleanKey.CalmRecoveryEnabled].
     *
     * MINIMUM 2, nicht 1: der Codevertrag lautet ausdruecklich "ein
     * einzelner ruhiger Zyklus genuegt nicht". Eine Einstellung, die das
     * unterlaeuft, waere ein Widerspruch zur Klasse selbst.
     */
    CalmRecoveryCycles("fuse_calm_recovery_cycles", 3, 2, 20),

    /**
     * DIE BEHANDLUNG: 0 = bedarfsbegrenzt, 1 = in den schrittweisen
     * Aufschub verschoben, 2 = Sofortbatch (DOSIERWIRKSAM).
     * Abbildung in [app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.ofSetting] -
     * ein unbekannter Wert ergibt den harmlosesten Modus.
     */
    CalmTreatmentMode("fuse_calm_treatment_mode", 0, 0, 2),
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
     * bei DIA 9 im FCL regelmaessig darueber - der Guard wuerde den schnellen
     * Kanal also nicht gelegentlich bremsen, sondern weitgehend schliessen. Ob
     * das so ist, ist NICHT gemessen; ein Default, der die Dosierung
     * flaechendeckend stilllegt, waere eine unbeschlossene Norm.
     *
     * Der Schalter macht aus dieser Unsicherheit eine Einstellung statt eines
     * Flashs: einschalten kostet fuenf Sekunden am Geraet. Solange er aus ist,
     * wird der Schwanz gar nicht erst bewertet - im Grund steht dann auch kein
     * tail=-Abschnitt. Wer messen will, schaltet ihn ein.
     */
    /**
     * BEDINGTE BAHN im Schwanz-Guard (11.08.).
     *
     * Der Schwanz rechnet sein Budget aus der PRIOR-FREIEN Bahn - also aus
     * einem Verlauf OHNE Kohlenhydrate. Auf flacher Kurve mit Insulin an Bord
     * ist sie niedrig, das Budget klein, der Spielraum negativ: er sperrt.
     * Gemessen am 10.08.: 25 Minuten am Stueck, waehrend der BG stieg.
     *
     * Das ist ein Zirkelschluss - er widerlegt die Ankuendigung mit einem
     * Modell, das die Ankuendigung nicht kennt. Mit dieser Einstellung darf
     * der ERKLAERTE Antrieb auch auf die Sicherheitskante wirken, solange ein
     * Markerkredit laeuft.
     *
     * SCHRANKE UND WIDERRUF GIBT ES BEREITS und sie sind nicht neu erfunden:
     * der Kredit ist `(Huelle - geliefert) * ISF / Absorptionsfenster`, er
     * schrumpft mit jeder Lieferung, endet mit den Sonderrechten und frueher
     * bei erkannter Wende. Zusaetzlich deckelt die Invariante
     * `priorFree <= lower` die Hebung auf die ANZEIGEBAHN - hoeher kommt die
     * Sicherheitskante nie.
     *
     * AUS heisst: exakt das Verhalten von vorher. Der Schalter ist da, damit
     * man ihn umlegen kann, nicht damit die Bahn spaeter wirkt.
     */
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
     * MASTER-Schalter der Prognose-Forschungssammler (Toni/Codex 23.08.):
     * Trend-/Tau-Matrix und ADAPTIVE-DOWN-Lanes. REIN diagnostisch, wird
     * nie von Dosierlogik gelesen (die Wende-KLASSIFIKATION selbst ist
     * Produktionseingang des Liveness-Exits und laeuft immer). Default AN,
     * solange die Messphase laeuft; im Normalbetrieb abschaltbar, damit
     * Forschungscode nicht unbegrenzt still mitrechnet. AUS exportiert
     * `enabled:false` statt fehlender Felder; jedes Umschalten eroeffnet
     * eine neue Sammel-Epoche. BEWUSST NICHT im Policy-Hash: keine
     * Dosierregel. Keine Einzelschalter je Variante - eine im Code
     * versionierte Matrix (methodId) ist reproduzierbarer als
     * Schalter-Kombinatorik.
     */
    ForecastShadowCollectionEnabled("fuse_forecast_shadow_collection_enabled", true),

    /**
     * ZERO-TBR-LATCH (Bauauftrag Toni 24.08. abends). Der Befund vom
     * selben Tag: zwischen 16:41 und 18:15 eroeffnete das Low-Tor
     * FUENFMAL eine berechtigte Zero-TBR, und der punktuelle Nutzenwert
     * (benefit < 5) warf sie jeweils binnen Minuten wieder weg - ~79 min
     * Profilbasal (~0,79 U) liefen in einen vorhersehbaren, langsamen
     * Fall bis zum Nadir 62. Der Latch verriegelt eine EINMAL berechtigt
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
     * Pflichtfall 06:27-06:33: 1,75 U auf die Erholung eines Sensor-V bei
     * robustem r -0,82). Nach steilem Fall loest eine schnelle
     * Gegenbewegung keine Korrektur-SMBs aus, solange das robuste r
     * negativ oder unbestaetigt ist. Kein Carry; Mahlzeitenpfade
     * (Marker/Prime/Fundament/Liveness-MEAL) bleiben unberuehrt.
     * Default AUS.
     */
    CorrectionReversalGuardEnabled("fuse_correction_reversal_guard_enabled", false),

    /**
     * FREIGABE-NACHLAUF nach Zero-Latch-Loesung/Nachtende, nur im
     * Korrekturkontext (Pflichtfall 08:00-08:03: 0,35 U in der ersten
     * Minute nach der Nachtband-Kante, BG fiel danach auf 106). Die Kante
     * oeffnet positive Korrektur-SMBs erst nach Mindestdauer UND
     * zusammenhaengend bestaetigter Aufwaertslage. Der Zero-Latch bleibt
     * als zweite Schutzlinie unveraendert. Default AUS.
     */
    PositiveCorrectionRearmEnabled("fuse_positive_correction_rearm_enabled", false),

    /**
     * DAS MAHLZEITENFUNDAMENT - DEFAULT AUS.
     *
     * Anders als der Erwartungs-Ledger ist dies DOSIERWIRKSAM: eingeschaltet
     * verschiebt es einen Teil des autorisierten Budgets aus der fruehen
     * Spitze in ein nachlaufendes Fenster. Tonis Reihenfolge (Spezifikation
     * 12) sieht davor Offline-Replay und eine Abnahme auf der VirtualPump vor,
     * und danach genau EINE produktive Testvariable ohne gleichzeitige
     * lambda-Scharfschaltung.
     *
     * Bei [MealFoundationPhaseAShare] = 1.0 ist der Schalter ohnehin
     * wirkungslos - dann gibt es keine Phase B. Beides zusammen macht das
     * Einschalten zu einer bewussten Entscheidung in zwei Schritten.
     */
    MealFoundationEnabled("fuse_meal_foundation_enabled", false),

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
     * DOSIERWIRKUNG: gemessen ueber 9 echte Luecken der Woche 20.-25.08.
     * rund zwei gesparte Minuten je Luecke bei +0,050 U ueber einen
     * ganzen Tag. Der Wiedereinstieg erlaubt wieder eine ENTSCHEIDUNG -
     * er umgeht kein Sicherheitsgate: Guards, Low-/Descent-Riegel, Tail
     * und die technischen Tore gelten unveraendert. In drei von fuenf
     * Replay-Laeufen war die einzige Wirkung, dass ein Zyklus von
     * "blind abgebrochen" zu "aus benanntem Grund geblockt" wechselte.
     */
    SignalRejoinEnabled("fuse_signal_rejoin_enabled", false),

    /**
     * DER RUHE-AUSGANG AUS PHASE A (Bauauftrag Toni 25.08. spaet).
     *
     * DOSIERWIRKSAM, wenn der Modus auf CALM_BATCH steht: dann darf der
     * noch offene Sofortanteil nach bestaetigter Ruhe als Batch heraus.
     * Default AUS - die Kalibrierung der Schwellen steht aus.
     */
    CalmRecoveryEnabled("fuse_calm_recovery_enabled", false),

    MarkerAuthorisesRelease("fuse_marker_authorises_low", false),

    ConditionalTailEnabled("fuse_conditional_tail_enabled", true),

    TailGuardEnabled("fuse_tail_guard_enabled", false),

    /** NACHT-TOTBAND aktiv (Toni 09.08.): im Nachtfenster kein SMB unterhalb
     *  Ziel + Nacht-Totband. Schalter getrennt vom Wert, damit ein Abschalten
     *  die eingestellte Schwelle nicht verliert. */
    NightDeadbandEnabled("fuse_night_deadband_enabled", true),

    /** REBOUND-TOTBAND aktiv: nach einem Tief kein SMB unterhalb
     *  Ziel + Rebound-Totband. War bis 09.08. fest eingebaut. */
    ReboundDeadbandEnabled("fuse_rebound_deadband_enabled", true),

    /**
     * Zweite Bahn aus der SCHNELLEN Rate, die ausschliesslich BREMSEN darf.
     *
     * DEFAULT AN, und anders als beim Schwanz-Guard ist das unkritisch: der
     * Eingriff ist beweisbar einseitig. Er nimmt das MINIMUM beider Bahnen und
     * kann damit keine Dosis erhoehen und keinen bestehenden Block entfernen —
     * nur zusaetzlich zurueckhalten.
     *
     * Gemessen am 06.08.: FUSE gab nach dem Wendepunkt noch 2,20 U in 14 SMBs,
     * bei bis zu -3,7 mg/dl/min FALLENDER Glukose, weil `rSigned` dort noch
     * +5,8 sagte. Genau diese Zyklen faengt die Bremse.
     */
    FastRestraintEnabled("fuse_fast_restraint_enabled", true),

    /**
     * Der oeffnende schnelle Kanal (OnsetChannel): Bruecke zwischen "UKF sieht
     * den Anstieg" und "Theil-Sen bestaetigt". NICHT einseitig - er hebt die
     * Mittelbahn. Gates: 3-min-Persistenz auf der ROHEN UKF-Rate, Ausreisser,
     * Haftungshuelle, Uebergabe an die Rampe bei r >= riseRampLowR.
     */
    OnsetChannelEnabled("fuse_onset_channel_enabled", true),

    /**
     * Sofort-Freigabe am Mahlzeiten-Marker (PrimeRelease): verteilte Abgabe
     * ab Knopfdruck, OHNE auf CGM-Evidenz zu warten. Nur mit Marker, nur im
     * 15-min-Fenster, Clearance-Gate gegen die Guardbahn, alle Sperren und
     * Deckel gewinnen. Huelle: PrimeEnvelopeU.
     */
    PrimeReleaseEnabled("fuse_prime_release_enabled", true),

    /**
     * Eine laufende Schutz-Null endet, sobald ihr Grund im aktuellen Zyklus
     * nachweislich weg ist - Rueckfall auf Profilbasal per Abbruch, NIE eine
     * positive Rate.
     *
     * DEFAULT AN (Toni 15.08.), und der Anlass ist gemessen: der einzige
     * aktive Ausgang war bis dahin KEEP_CANCEL_STALE_ZERO, und der verlangt
     * einen Zyklus, der bis BELOW_PUMP_INCREMENT durchlaeuft - hinter Guard,
     * Schwanz oder Totband entsteht der nie. Im 4-Tage-Trail standen 497
     * gesetzten Nullen 43 Abbrueche gegenueber (nachts 147 zu 6); die Null
     * ueberdauerte ihren Grund rund 100 Minuten je Nacht. Das zurueckgehaltene
     * Basal finanziert ueber die Bedarfsseite die Morgen-SMBs.
     *
     * AUS = Verhalten wie vor dem 15.08., bitgleich. Der Schalter bleibt, weil
     * er der Rueckweg ist: die Wirkung ist am Geraet noch ungemessen.
     */
    TbrEndZeroWhenReasonGone("fuse_tbr_end_zero_when_reason_gone", true),
}
