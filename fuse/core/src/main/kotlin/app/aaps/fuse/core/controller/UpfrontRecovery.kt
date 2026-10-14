package app.aaps.fuse.core.controller

/**
 * DER RUHE-AUSGANG DES PHASE-A-SOFORTBATCHES - was davon bleibt.
 *
 * Konsolidierung 2.0: Sofortanteil und Ruhe-Ausgang sind entfallen, mit ihnen
 * die Entscheidung selbst (Gefahrenliste, Ruhezaehlung, Freigabetypen). Hier
 * stehen nur noch die Typen, die weiter gelesen werden: [Track] und
 * [TrackMode] fuer den Ledger (Feld und Codec bleiben bis zum Ledger-Abbau),
 * [Params] und [CalmTreatment] fuer Einstellungen und Politik-Export samt
 * Fingerprint. Kein Dosierpfad liest sie.
 */
object UpfrontRecovery {

    /**
     * Die Behandlungswahl des entfallenen Ruhe-Ausgangs - eingestellt und
     * exportiert, ohne Wirkung auf die Dosierung.
     */
    enum class CalmTreatment {

        /** Bedarfsbegrenzt. */
        DEMAND_LIMITED,

        /** In den schrittweisen Aufschub verschoben. */
        SHIFT_TO_DEFERRED,

        /** Als Batch freigegeben. */
        CALM_BATCH,
        ;

        companion object {

            /**
             * AUS DER EINSTELLUNG, fail-closed. Es gibt keinen
             * `FuseStringKey`, die Wahl liegt also als Zahl vor. Ein
             * unbekannter Wert ergibt den harmlosesten Modus - nicht den
             * dosierwirksamen. Wer die Reihenfolge hier aendert, aendert
             * die Bedeutung gespeicherter Einstellungen; deshalb sind die
             * Zahlen ausgeschrieben statt `values()[i]`.
             */
            fun ofSetting(v: Int): CalmTreatment = when (v) {
                1 -> SHIFT_TO_DEFERRED
                2 -> CALM_BATCH
                else -> DEMAND_LIMITED
            }
        }
    }

    /** Welcher Weg den letzten Trackeintrag erzeugt hat. */
    enum class TrackMode { NONE, RISING, CALM }

    /**
     * SCHEMA DES TRACKS SELBST - getrennt von der RuleSet-Version.
     *
     * Die RuleSet-Version wandert mit jedem Export-Schema; sie sagt nichts
     * darueber, ob sich die BEDEUTUNG eines Ruhezyklus geaendert hat. Wer
     * hier etwas umbaut - andere Anschlussregel, anderer Modusbegriff,
     * anderer Streak-Vertrag -, erhoeht diese Zahl und entwertet damit
     * jeden gespeicherten Zaehler. Das ist der ausdrueckliche Hebel dafuer.
     */
    // v2 (28.08.): BEDEUTUNG UND HERKUNFT DES RUHEZAEHLERS HABEN SICH
    // GEAENDERT. Er zaehlte frueher ausschliesslich frische Zyklen unter zwei
    // Nulltoleranzen (UKF < 0, q1 gefallen); jetzt steht dahinter der
    // Stabilitaetsnachweis auf der gemessenen Reihe, und der Zaehler kann aus
    // belegter Vorgeschichte gesaet sein. Ein gespeicherter Stand aus der
    // alten Welt darf unter der neuen Regel NICHT still weiterlaufen - er
    // wuerde eine Bestaetigung behaupten, die nie unter diesen Bedingungen
    // entstanden ist.
    // v3 (28.08.): `confirmedCycles` zaehlt jetzt STABILISIERUNG IM
    // JUENGSTEN ABSCHNITT statt Stabilitaet des Gesamtfensters. Wieder ein
    // Bedeutungswechsel, wieder eine Generation - ein gespeicherter Stand
    // behauptet sonst eine Bestaetigung, die unter der neuen Regel nie
    // entstanden ist.
    // v4 (28.08.): der juengste Abschnitt verlangt jetzt eine TATSAECHLICH
    // beobachtete Mindestspanne statt 60 Prozent des Fensters, und das Urteil
    // heisst ehrlich "innerhalb der Toleranz" statt "stabilisiert". Der
    // akzeptierte Dauerabfall aendert sich damit von 0,667 auf 0,444
    // mg/dl/min - ein gespeicherter Zaehler stammt aus der lockereren Regel.
    const val TRACK_SCHEMA = 4

    /**
     * Die Ruheparameter aus den Einstellungen (`Config.calmParams` im
     * Runner). Seit der Konsolidierung nur noch fuer den Politik-Export und
     * dessen Fingerprint.
     */
    class Params private constructor(
        val enabled: Boolean,
        /** Wieviele lueckenlose Ruhezyklen. */
        val calmCycles: Int,
        /** Kleinste zulaessige UKF-Rate [mg/dl/min]. */
        val minUkf: Double,
        /** Mindestabstand von q1 zum Guard-Boden [mg/dl]. */
        val minGuardDistanceMgdl: Double,
        /** Wie ein bestaetigt ruhiger Batch behandelt wird. */
        val calmTreatment: CalmTreatment?,
        /** Die Regel-/Konfigurationsgeneration, unter der beobachtet wird. */
        val ruleSetVersion: Int,
    ) {

        /**
         * DIE SECHSTE IDENTITAET (Toni 25.08. spaet). Ein Streak darf nach
         * einem Settingswechsel oder Flash nicht unter ANDEREN Schwellen
         * fortgesetzt werden - sonst loesen zwei Beobachtungen unter alten
         * und eine dritte unter gelockerten Parametern gemeinsam eine
         * Freigabe aus. Der Fingerprint deckt alles ab, was die Bedeutung
         * eines Ruhezyklus bestimmt.
         */
        val fingerprint: String
            get() = if (!enabled) "off"
            else "s$TRACK_SCHEMA|rs$ruleSetVersion|c$calmCycles" +
                // BITMUSTER STATT TEXT: `toString` einer Double haengt zwar
                // nicht an der Locale, aber an der kuerzesten
                // Rundtrip-Darstellung - 0.05 und ein um ein Ulp anderer
                // Wert koennten denselben Text ergeben, und ein anderer
                // JDK-Stand koennte denselben Wert anders schreiben. Das
                // Bitmuster ist exakt, kanonisch und sprachunabhaengig.
                "|u${java.lang.Double.doubleToLongBits(minUkf)}" +
                "|g${java.lang.Double.doubleToLongBits(minGuardDistanceMgdl)}" +
                "|t${calmTreatment?.name}"

        companion object {

            val OFF = Params(false, 0, 0.0, 0.0, null, 0)

            /** s. [FuseIntKey.CalmRecoveryCycles] - der Vertrag der Klasse. */
            const val MIN_CALM_CYCLES = 2
            const val MAX_CALM_CYCLES = 20

            /** Kein Batch auf einer noch fallenden Kurve. */
            const val MIN_CALM_UKF = 0.0

            /** Keine Freigabe unmittelbar am Guard-Boden. */
            const val MIN_GUARD_DISTANCE_MGDL = 5.0

            /**
             * Unbrauchbare Werte ergeben [OFF] - kein stiller Rueckfall auf
             * eine erfundene Kalibrierung. Die Behandlungswahl ist Pflicht:
             * ohne sie gibt es keinen ruhigen Pfad, nur den alten Vertrag.
             */
            fun of(
                calmCycles: Int,
                minUkf: Double,
                minGuardDistanceMgdl: Double,
                calmTreatment: CalmTreatment,
                ruleSetVersion: Int,
            ): Params =
                // DIESELBEN GRENZEN WIE DIE EINSTELLUNG - und hier ist es
                // die letzte Verteidigung: ein Wert aus einem Backup, einer
                // Altdatei oder einem Replay-Override kommt hier ebenso an
                // wie einer aus dem Bildschirm.
                //   calmCycles >= 2  - ein einzelner ruhiger Zyklus genuegt
                //                      ausdruecklich nicht
                //   minUkf >= 0.0    - FLOOR_BEYOND_HORIZON hebt das aktuelle
                //                      Risiko auf, waehrend die Rate noch
                //                      negativ ist
                //   Abstand >= 5.0   - keine Freigabe am Guard-Boden
                if (calmCycles in MIN_CALM_CYCLES..MAX_CALM_CYCLES &&
                    minUkf.isFinite() && minUkf >= MIN_CALM_UKF && minUkf <= 1.0 &&
                    minGuardDistanceMgdl.isFinite() &&
                    minGuardDistanceMgdl >= MIN_GUARD_DISTANCE_MGDL &&
                    minGuardDistanceMgdl <= 100.0 && ruleSetVersion > 0
                ) Params(true, calmCycles, minUkf, minGuardDistanceMgdl, calmTreatment,
                         ruleSetVersion)
                else OFF
        }
    }

    /**
     * DER PERSISTIERTE RUHEZAEHLER - nur noch fuer den Ledger: der Codec
     * liest und schreibt ihn, kein Dosierpfad wertet ihn aus (Konsolidierung
     * 2.0). [ofPersisted] prueft geladene Staende weiter fail-closed.
     */
    class Track(
        /** `armedTs` des Markers, zu dem dieser Zaehler gehoert. */
        val markerIdentity: Long = 0L,
        val streak: Int = 0,
        /** Der Signalpunkt, auf dem der letzte Ruhezyklus beruhte. */
        val lastAcceptedSourceTs: Long = 0L,
        /** Wann zuletzt ausgewertet wurde. */
        val lastEvaluationTs: Long = 0L,
        val mode: TrackMode = TrackMode.NONE,
        /**
         * Die Regel-/Konfigurationsgeneration, unter der dieser Zaehler
         * entstanden ist. Ein Wechsel verwirft ihn - er wird niemals
         * umgedeutet.
         */
        val fingerprint: String = "",
    ) {

        /**
         * Ein Zaehler ohne vollstaendige Identitaet ist kein Zaehler.
         * Entweder alles leer, oder alles gesetzt.
         */
        val consistent: Boolean
            get() = if (streak <= 0) markerIdentity == 0L && lastAcceptedSourceTs == 0L &&
                lastEvaluationTs == 0L && mode == TrackMode.NONE && fingerprint.isEmpty()
            else markerIdentity > 0L && lastAcceptedSourceTs > 0L &&
                lastEvaluationTs > 0L && mode != TrackMode.NONE && streak <= MAX_STREAK &&
                fingerprint.isNotEmpty()

        companion object {

            val EMPTY = Track()

            /** Obergrenze gegen einen davongelaufenen Zaehler. */
            const val MAX_STREAK = 1000

            /**
             * FAIL-CLOSED: was der Codec liefert, wird geprueft, nicht
             * geglaubt. Jede unvollstaendige oder widerspruechliche
             * Kombination ergibt [EMPTY] - der konservative Zustand, weil
             * ein fortgesetzter Zaehler mehr Insulin freigeben kann als ein
             * neu begonnener.
             */
            fun ofPersisted(
                markerIdentity: Long,
                streak: Int,
                lastAcceptedSourceTs: Long,
                lastEvaluationTs: Long,
                mode: TrackMode,
                fingerprint: String,
            ): Track {
                val t = Track(markerIdentity, streak, lastAcceptedSourceTs, lastEvaluationTs,
                              mode, fingerprint)
                return if (t.consistent) t else EMPTY
            }
        }
    }
}
