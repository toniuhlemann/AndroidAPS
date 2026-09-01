package app.aaps.fuse.plugin.replay

/**
 * DER GENERISCHE ENTSCHEIDUNGS-ANALYZER DER NULLPHASEN-VARIANTEN.
 *
 * WAS ER IST: eine reine Rechnung auf einer Zyklusfolge. Er sagt, WANN
 * eine Variante anders entschieden haette und WIEVIEL Menge bzw. Zeit
 * davon betroffen ist.
 *
 * WAS ER AUSDRUECKLICH NICHT IST - und das ist keine Floskel, sondern
 * die Grenze, an der eine fruehere Auswertung schon einmal
 * ueberschritten wurde: er sagt NICHTS ueber den Glukoseverlauf. Das
 * Signal steht fest; jede Aussage der Form "der Zucker waere dann ..."
 * oder "es war sicher" ist aus diesen Zahlen NICHT ableitbar. Sobald
 * eine Variante anders entscheidet, ist der weitere Verlauf unbekannt -
 * insbesondere ein q1-Minimum oder ein Bodenabstand NACH dem
 * hypothetischen Eingriff gehoert zur aufgezeichneten Basislinie, nicht
 * zur Variante.
 *
 * Deshalb heissen die Felder, was sie sind: `erneuterGrundNachMin` ist
 * "im unveraenderten Signal erscheint nach so vielen Minuten wieder ein
 * Schutzgrund" - Flatterpotenzial, kein Nachweis einer Selbstkorrektur.
 *
 * KEINE DATEN IM REPO: dieser Analyzer traegt keine Trails, keine
 * Zeitstempel und keine Messwerte. Die Eingabe kommt zur Laufzeit aus
 * einer lokalen Datei, das Ergebnis bleibt lokal.
 */
object NullphasenReplay {

    /**
     * Ein Zyklus, auf das reduziert, was die Varianten brauchen.
     * Bewusst KEIN Glukosewert: was der Analyzer nicht kennt, kann er
     * auch nicht versehentlich behaupten.
     */
    data class Zyklus(
        /**
         * ZWEI UHREN, GETRENNT GEFUEHRT - und die Trennung ist nicht
         * kosmetisch: `computeTs` ist der Entscheidungszeitpunkt (daran
         * haengen Phasendauer und Basalbilanz), `sourceTs` der Zeitstempel
         * des SIGNALS (daran haengen Streak-Anschluss und Serienfenster,
         * genau wie in der Produktion). Ein wiederholter oder
         * zurueckspringender Messpunkt darf einen Streak nicht wachsen
         * lassen, auch wenn der Zyklus selbst weiterlaeuft.
         */
        val computeTs: Long,
        val sourceTs: Long,
        /** Lief in diesem Zyklus eine Null-TBR? */
        val zeroActive: Boolean,
        /** Liegt ein LowThreat-Schutzgrund an (Verdikt != NONE)? */
        val schutzgrund: Boolean,
        /** Gemessene Rate; null = keine Aussage (zaehlt nie als Erholung). */
        val ukfRatePerMin: Double?,
        val signalHealthy: Boolean,
        /**
         * DIE DREI SCHUTZBEDINGUNGEN, die der Produktionsausgang
         * zusaetzlich verlangt. Sie fehlten in der ersten Fassung, und
         * damit konnten die gemeldeten Ausgaenge ZU FRUEH sein.
         *
         * FAIL-CLOSED: fehlt die Information im Trail, gilt sie als
         * ungueltig - `measuredLow`/`descentRiskActive` als `z.sourceTs - letzterSourceTs <= ANSCHLUSS_MAX_MS`
         * (Gefahr angenommen) und `q1NotFalling` als `false` (keine
         * Erholung angenommen). Eine fehlende Angabe darf nie zu einem
         * Ausgang fuehren, den die Produktion nicht gehabt haette.
         */
        val measuredLow: Boolean,
        val descentRiskActive: Boolean,
        /**
         * q1 dieses Zyklus >= q1 des vorigen - 0,01 (Produktionsregel
         * `q1NichtFallend`). Der lokale Leser leitet das aus zwei
         * aufeinanderfolgenden Werten ab und uebergibt NUR das Boolean -
         * im Analyzer liegt kein Glukosewert.
         */
        val q1NotFalling: Boolean,
        /** Laufendes Profilbasal [U/h]; null = unbekannt, dann waechst nur
         *  die Zeit, nicht die Menge. */
        val scheduledBasalUph: Double?,
        /**
         * PUBLIZIERTE Menge dieses Zyklus [U] - was FUSE nach allen Toren
         * an AAPS uebergab.
         *
         * NICHT pumpenbestaetigt. Der Trail traegt je Zyklus keine
         * Bestaetigung (die entsteht erst spaeter ueber die
         * IOB-Reconciliation und ist keinem Zyklus zugeordnet). Die ganze
         * V2-Auswertung laeuft deshalb ausdruecklich auf PUBLIZIERTEN
         * Mengen; wo "geflossen" steht, ist "publiziert" gemeint.
         */
        val publishedU: Double,
        /** Lief der Zyklus unter MEAL-Vollmacht? Dann gehoert er nicht in
         *  den markerlosen Korrekturpfad. */
        val mealAuthorized: Boolean,
    )

    /** Dieselbe Schwelle wie im Produktionscode. */
    const val FLAT_RATE = -0.03

    /** Der Anschluss-Abstand des Produktionscodes: eine Luecke > 90 s
     *  nullt den Streak (dort zaehlt er ZYKLEN, keine Wanduhrminuten). */
    const val ANSCHLUSS_MAX_MS = 90_000L

    /**
     * ALLE FUENF Freigabebedingungen des Produktionsausgangs - nicht nur
     * Signalgesundheit und Rate. Die ersten drei fehlten, wodurch
     * gemeldete Ausgaenge zu frueh sein konnten.
     */
    private fun erholung(z: Zyklus) =
        z.signalHealthy &&
            !z.measuredLow &&
            !z.descentRiskActive &&
            z.ukfRatePerMin != null && z.ukfRatePerMin >= FLAT_RATE &&
            z.q1NotFalling

    // ---- PHASEN ---------------------------------------------------------

    data class Phase(val zyklen: List<Zyklus>) {
        // Dauer und Bilanz an der ENTSCHEIDUNGS-Uhr.
        val vonMs get() = zyklen.first().computeTs
        val bisMs get() = zyklen.last().computeTs
        val dauerMin get() = (bisMs - vonMs) / 60_000.0
    }

    /** Zusammenhaengende Null-Strecken; kuerzere als [minZyklen] sind
     *  Rauschen und werden verworfen. */
    fun phasen(zyklen: List<Zyklus>, minZyklen: Int = 3): List<Phase> {
        val out = mutableListOf<Phase>()
        var akt = mutableListOf<Zyklus>()
        for (z in zyklen) {
            if (z.zeroActive) akt.add(z)
            else if (akt.isNotEmpty()) { out.add(Phase(akt.toList())); akt = mutableListOf() }
        }
        if (akt.isNotEmpty()) out.add(Phase(akt.toList()))
        return out.filter { it.zyklen.size >= minZyklen }
    }

    // ---- VARIANTE 1 ------------------------------------------------------

    data class V1Phase(
        val vonMs: Long,
        val bisMs: Long,
        val dauerMin: Double,
        /** Wann der Grund-Weg-Ausgang gefeuert haette; null = gar nicht. */
        val ausgangMs: Long?,
        /** Nullminuten, die danach im IST noch liefen. */
        val weggefalleneMin: Double,
        /** Das dabei ausgelassene Profilbasal [U]; 0 wenn Profil unbekannt. */
        val weggefallenesBasalU: Double,
        /**
         * IM UNVERAENDERTEN SIGNAL: nach so vielen Minuten erscheint nach
         * dem Ausgang wieder ein Schutzgrund. null = in dieser Phase gar
         * nicht mehr. FLATTERPOTENZIAL, keine Aussage ueber den Verlauf
         * unter der Variante.
         */
        val erneuterGrundNachMin: Double?,
    )

    data class V1Ergebnis(
        val n: Int,
        val phasen: List<V1Phase>,
    ) {
        val betroffenePhasen get() = phasen.count { it.ausgangMs != null }
        val weggefalleneMin get() = phasen.sumOf { it.weggefalleneMin }
        val weggefallenesBasalU get() = phasen.sumOf { it.weggefallenesBasalU }

        /**
         * POTENZIELLE Aktuationskanten - ausdruecklich KEINE Pumpenkommandos.
         *
         * Jeder Ausgang waere eine Abbruchkante, jedes erneute Zuenden im
         * unveraenderten Signal eine Null-Kante. OB daraus ein Kommando
         * entsteht, haengt am TBR-Zustand, am Pumpengate und an der
         * tatsaechlich publizierten TBR-Aktion - nichts davon bildet
         * dieser Analyzer nach. Wer die Zahl als "Pumpenkommandos" liest,
         * behauptet mehr, als hier steht.
         */
        val potenzielleAktuationskanten
            get() = phasen.count { it.ausgangMs != null } +
                phasen.count { it.erneuterGrundNachMin != null }

        /** Die laengste Ruhe zwischen Ausgang und erneutem Schutzgrund. */
        val laengsteRuheMin get() = phasen.mapNotNull { it.erneuterGrundNachMin }.maxOrNull()
    }

    fun variante1(zyklen: List<Zyklus>, n: Int): V1Ergebnis {
        require(n > 0)
        val out = phasen(zyklen).map { p ->
            var streak = 0
            var idx: Int? = null
            var letzterSourceTs = 0L
            for ((i, z) in p.zyklen.withIndex()) {
                // DER ANSCHLUSS EXAKT WIE IN DER PRODUKTION: der
                // SIGNAL-Zeitstempel muss STRENG STEIGEN und hoechstens
                // 90 s Abstand haben. Ein gleicher, ein zurueckspringender
                // oder ein fehlender sourceTs erhoeht den Streak NIE - er
                // beginnt dann bei 1 (der Zyklus selbst zaehlt) bzw. bleibt
                // 0, wenn die Bedingungen nicht erfuellt sind.
                val anschluss = letzterSourceTs > 0L &&
                    z.sourceTs > letzterSourceTs &&
                    true
                streak = if (!z.schutzgrund && erholung(z)) (if (anschluss) streak + 1 else 1) else 0
                if (z.sourceTs > 0L) letzterSourceTs = z.sourceTs
                if (streak >= n) { idx = i; break }
            }
            if (idx == null) {
                V1Phase(p.vonMs, p.bisMs, p.dauerMin, null, 0.0, 0.0, null)
            } else {
                val rest = p.zyklen.subList(idx, p.zyklen.size)
                val weg = (p.bisMs - rest.first().computeTs) / 60_000.0
                val basal = rest.zipWithNext().sumOf { (a, b) ->
                    val dt = ((b.computeTs - a.computeTs) / 60_000.0).coerceIn(0.0, 3.0)
                    (a.scheduledBasalUph ?: 0.0) * dt / 60.0
                }
                val wieder = rest.drop(1).firstOrNull { it.schutzgrund }
                V1Phase(
                    p.vonMs, p.bisMs, p.dauerMin, rest.first().computeTs, weg, basal,
                    wieder?.let { (it.computeTs - rest.first().computeTs) / 60_000.0 },
                )
            }
        }
        return V1Ergebnis(n, out)
    }

    // ---- VARIANTE 2 ------------------------------------------------------

    /** Die markerlosen Publikationen - nur sie belasten den Serien-Deckel. */
    fun korrekturDosen(zyklen: List<Zyklus>): List<Pair<Long, Double>> =
        zyklen.filter { !it.mealAuthorized && it.publishedU > 0.0 }
            // Serienfenster an der SIGNAL-Uhr, wie die Produktionsbuchung.
            .map { it.sourceTs to it.publishedU }

    data class Verteilung(
        val fensterMin: Int,
        val maxU: Double,
        val p50U: Double,
        val p90U: Double,
        /** Der groesste Wert, den das rollierende Fenster je erreicht -
         *  ein Deckel DARUEBER kann nie binden. */
        val maxTsMs: Long?,
    )

    /**
     * Die rollierende Summe OHNE Deckel: was liefe je Fenster zusammen?
     * Das ist die Groesse, an der ein Deckelkandidat gemessen werden muss -
     * ein Deckel oberhalb des Maximums laesst alles passieren.
     */
    fun verteilung(zyklen: List<Zyklus>, fensterMin: Int): Verteilung {
        val dosen = korrekturDosen(zyklen)
        val fenster = fensterMin * 60_000L
        val summen = dosen.map { (ts, _) ->
            ts to dosen.filter { it.first in (ts - fenster + 1)..ts }.sumOf { it.second }
        }
        if (summen.isEmpty()) return Verteilung(fensterMin, 0.0, 0.0, 0.0, null)
        val sortiert = summen.map { it.second }.sorted()
        fun q(p: Double) = sortiert[((sortiert.size - 1) * p).toInt()]
        val max = summen.maxByOrNull { it.second }!!
        return Verteilung(fensterMin, max.second, q(0.5), q(0.9), max.first)
    }

    /**
     * WO EINE BESTIMMTE SERIE IN DER VERTEILUNG LIEGT.
     *
     * Die Frage, an der ein Deckelkandidat scheitern kann: liegt die
     * Serie, die man treffen will, ueberhaupt am oberen Ende? Liegt sie
     * im Mittelfeld, trifft jeder Deckel, der sie erwischt, zwangslaeufig
     * sehr viel anderes mit - und ein Deckel knapp darueber laesst sie
     * vollstaendig passieren.
     */
    data class Einordnung(
        val fensterMin: Int,
        /** Groesste rollierende Summe INNERHALB des markierten Bereichs. */
        val serieMaxU: Double,
        /**
         * RANG UNTER DOSISBEENDETEN, UEBERLAPPENDEN ROLLFENSTERN [0..1] -
         * ausdruecklich KEIN Perzentil einer Verteilung unabhaengiger
         * Serien.
         *
         * Gebildet wird je DOSIS ein Fenster, das an ihr endet; benachbarte
         * Fenster ueberlappen also stark und eine dichte Dosenfolge ist
         * mehrfach vertreten. Aus einem Rang von 70 % folgt deshalb NICHT,
         * dass "70 % der Serien kleiner waren" - nur, dass 70 % der
         * dosisbeendeten Fenster kleiner waren. Fuer die Serienfrage ist
         * [serien] die richtige Groesse.
         */
        val rangUnterRollfenstern: Double,
        val gesamtMaxU: Double,
    )

    fun einordnung(
        zyklen: List<Zyklus>,
        fensterMin: Int,
        bereichVonMs: Long,
        bereichBisMs: Long,
    ): Einordnung {
        val dosen = korrekturDosen(zyklen)
        val f = fensterMin * 60_000L
        val summen = dosen.map { (ts, _) ->
            ts to dosen.filter { it.first in (ts - f + 1)..ts }.sumOf { it.second }
        }
        val imBereich = summen.filter { it.first in bereichVonMs..bereichBisMs }
        val serieMax = imBereich.maxOfOrNull { it.second } ?: 0.0
        val kleiner = summen.count { it.second < serieMax - 1e-9 }
        return Einordnung(
            fensterMin, serieMax,
            if (summen.isEmpty()) 0.0 else kleiner.toDouble() / summen.size,
            summen.maxOfOrNull { it.second } ?: 0.0,
        )
    }

    /**
     * EINE SERIE - zusammenhaengende markerlose Dosen, getrennt durch
     * eine RUHEPAUSE.
     *
     * Die Trennung ist eine dokumentierte Wahl, keine Messung:
     * [RUHE_TRENNUNG_MIN] Minuten ohne markerlose Dosis beenden eine
     * Serie. Der Wert ist bewusst gleich dem kuerzesten betrachteten
     * Deckelfenster - eine laengere Pause bedeutet, dass ein rollierender
     * Deckel dieser Laenge zwischendurch vollstaendig frei geworden waere.
     * Wer eine andere Trennung waehlt, bekommt andere Serien; deshalb
     * steht sie hier und nicht implizit im Auswertungsskript.
     */
    const val RUHE_TRENNUNG_MIN = 15

    data class Serie(
        val vonMs: Long,
        val bisMs: Long,
        val dosen: Int,
        /** Summe der PUBLIZIERTEN Mengen dieser Serie [U]. */
        val summeU: Double,
    )

    /** Gruppiert die markerlosen Dosen zu Serien - die Groesse, nach der
     *  ein Serien-Deckel eigentlich fragt. */
    fun serien(zyklen: List<Zyklus>, trennungMin: Int = RUHE_TRENNUNG_MIN): List<Serie> {
        val dosen = korrekturDosen(zyklen)
        if (dosen.isEmpty()) return emptyList()
        val luecke = trennungMin * 60_000L
        val out = mutableListOf<Serie>()
        var von = dosen.first().first
        var letzte = von
        var n = 0
        var summe = 0.0
        for ((ts, u) in dosen) {
            if (ts - letzte > luecke) {
                out.add(Serie(von, letzte, n, summe))
                von = ts; n = 0; summe = 0.0
            }
            letzte = ts; n++; summe += u
        }
        out.add(Serie(von, letzte, n, summe))
        return out
    }

    data class Fensterkante(
        val tsMs: Long,
        /** Um wieviel der Headroom in diesem Moment springt [U]. */
        val sprungU: Double,
    )

    data class V2Ergebnis(
        val deckelU: Double,
        val fensterMin: Int,
        val gekapptU: Double,
        val geflossenU: Double,
        val betroffeneDosen: Int,
        val ersteBindungMs: Long?,
        /**
         * Wo der rollierende Deckel Headroom freigibt - der Kandidat fuer
         * einen Saegezahn. `sprungU` ist die Menge, die in genau diesem
         * Moment wieder moeglich wird.
         */
        val kanten: List<Fensterkante>,
    ) {
        val groessterSprungU get() = kanten.maxOfOrNull { it.sprungU } ?: 0.0
    }

    /**
     * Der Deckel angewandt auf die Dosenfolge. ACHTUNG, GRENZE: ab der
     * ersten Kappung ist die Folge rueckkopplungsblind - der Regler haette
     * mit weniger IOB andere Mengen angefordert. Alles hier sind
     * MENGENOBERGRENZEN.
     */
    fun variante2(
        zyklen: List<Zyklus>,
        deckelU: Double,
        fensterMin: Int,
        /** Die Produktionskappung rastert auf den Pumpenschritt - eine
         *  beliebige Teilmenge kann die Pumpe gar nicht abgeben. */
        pumpenschrittU: Double = 0.05,
    ): V2Ergebnis {
        val fenster = fensterMin * 60_000L
        val geflossen = mutableListOf<Pair<Long, Double>>()
        var gekappt = 0.0
        var betroffen = 0
        var erste: Long? = null
        for ((ts, u) in korrekturDosen(zyklen)) {
            val imFenster = geflossen.filter { it.first in (ts - fenster + 1)..ts }.sumOf { it.second }
            val roh = minOf(u, (deckelU - imFenster).coerceAtLeast(0.0))
            // Auf den Pumpenschritt abrunden (wie ExposureGate): was nicht
            // auf das Raster passt, kann nicht abgegeben werden.
            val erlaubt =
                if (pumpenschrittU > 0.0) Math.floor(roh / pumpenschrittU + 1e-9) * pumpenschrittU
                else roh
            if (erlaubt < u - 1e-9) {
                gekappt += u - erlaubt
                betroffen++
                if (erste == null) erste = ts
            }
            if (erlaubt > 0.0) geflossen.add(ts to erlaubt)
        }
        // FENSTERKANTEN: jede geflossene Menge gibt genau eine Fensterlaenge
        // spaeter wieder Headroom frei. Ein grosser Sprung heisst: dort
        // koennte der Kanal schlagartig wieder oeffnen.
        val kanten = geflossen
            .map { Fensterkante(it.first + fenster, it.second) }
            .sortedBy { it.tsMs }
        return V2Ergebnis(deckelU, fensterMin, gekappt, geflossen.sumOf { it.second },
                          betroffen, erste, kanten)
    }
}
