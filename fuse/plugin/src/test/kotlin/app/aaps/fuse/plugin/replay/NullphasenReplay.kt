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
        val tsMs: Long,
        /** Lief in diesem Zyklus eine Null-TBR? */
        val zeroActive: Boolean,
        /** Liegt ein LowThreat-Schutzgrund an (Verdikt != NONE)? */
        val schutzgrund: Boolean,
        /** Gemessene Rate; null = keine Aussage (zaehlt nie als Erholung). */
        val ukfRatePerMin: Double?,
        val signalHealthy: Boolean,
        /** Laufendes Profilbasal [U/h]; null = unbekannt, dann waechst nur
         *  die Zeit, nicht die Menge. */
        val scheduledBasalUph: Double?,
        /** Publizierte Menge dieses Zyklus [U]. */
        val publishedU: Double,
        /** Lief der Zyklus unter MEAL-Vollmacht? Dann gehoert er nicht in
         *  den markerlosen Korrekturpfad. */
        val mealAuthorized: Boolean,
    )

    /** Dieselbe Schwelle wie im Produktionscode. */
    const val FLAT_RATE = -0.03

    private fun erholung(z: Zyklus) =
        z.signalHealthy && z.ukfRatePerMin != null && z.ukfRatePerMin >= FLAT_RATE

    // ---- PHASEN ---------------------------------------------------------

    data class Phase(val zyklen: List<Zyklus>) {
        val vonMs get() = zyklen.first().tsMs
        val bisMs get() = zyklen.last().tsMs
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
         * Jeder Ausgang ist ein zusaetzliches Abbruch-Kommando an die
         * Pumpe, jedes erneute Zuenden im unveraenderten Signal ein
         * zusaetzliches Null-Kommando. Die Summe ist die Zahl der
         * ZUSAETZLICHEN Aktuationen gegenueber der Basislinie.
         */
        val zusaetzlicheKommandos
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
            for ((i, z) in p.zyklen.withIndex()) {
                streak = if (!z.schutzgrund && erholung(z)) streak + 1 else 0
                if (streak >= n) { idx = i; break }
            }
            if (idx == null) {
                V1Phase(p.vonMs, p.bisMs, p.dauerMin, null, 0.0, 0.0, null)
            } else {
                val rest = p.zyklen.subList(idx, p.zyklen.size)
                val weg = (p.bisMs - rest.first().tsMs) / 60_000.0
                val basal = rest.zipWithNext().sumOf { (a, b) ->
                    val dt = ((b.tsMs - a.tsMs) / 60_000.0).coerceIn(0.0, 3.0)
                    (a.scheduledBasalUph ?: 0.0) * dt / 60.0
                }
                val wieder = rest.drop(1).firstOrNull { it.schutzgrund }
                V1Phase(
                    p.vonMs, p.bisMs, p.dauerMin, rest.first().tsMs, weg, basal,
                    wieder?.let { (it.tsMs - rest.first().tsMs) / 60_000.0 },
                )
            }
        }
        return V1Ergebnis(n, out)
    }

    // ---- VARIANTE 2 ------------------------------------------------------

    /** Die markerlosen Publikationen - nur sie belasten den Serien-Deckel. */
    fun korrekturDosen(zyklen: List<Zyklus>): List<Pair<Long, Double>> =
        zyklen.filter { !it.mealAuthorized && it.publishedU > 0.0 }
            .map { it.tsMs to it.publishedU }

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
        /** Anteil aller Fenstersummen, die kleiner sind [0..1]. */
        val perzentil: Double,
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
    fun variante2(zyklen: List<Zyklus>, deckelU: Double, fensterMin: Int): V2Ergebnis {
        val fenster = fensterMin * 60_000L
        val geflossen = mutableListOf<Pair<Long, Double>>()
        var gekappt = 0.0
        var betroffen = 0
        var erste: Long? = null
        for ((ts, u) in korrekturDosen(zyklen)) {
            val imFenster = geflossen.filter { it.first in (ts - fenster + 1)..ts }.sumOf { it.second }
            val erlaubt = minOf(u, (deckelU - imFenster).coerceAtLeast(0.0))
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
