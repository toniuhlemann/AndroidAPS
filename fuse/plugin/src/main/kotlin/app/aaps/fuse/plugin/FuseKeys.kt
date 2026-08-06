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
     * Anteil des Insulinbedarfs, der ueber den schnellen Kanal freigegeben wird.
     * Eigene Groesse, eigener Default — bewusst identisch zum heutigen
     * Fork-Wert, damit der erste Vergleich nicht schon an der Einstellung
     * scheitert, ab jetzt aber getrennt verstellbar.
     */
    SmbRatio("fuse_smb_ratio", 0.2, 0.0, 1.0),

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
}
