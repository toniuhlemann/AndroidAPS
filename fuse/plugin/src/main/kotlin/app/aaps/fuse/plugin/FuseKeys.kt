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
    TailGuardEnabled("fuse_tail_guard_enabled", false),

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
}
