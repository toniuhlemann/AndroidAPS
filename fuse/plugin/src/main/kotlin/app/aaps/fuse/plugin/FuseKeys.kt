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
    PrimeEnvelopeU("fuse_prime_envelope_u", 1.2, 0.0, 4.0),

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
