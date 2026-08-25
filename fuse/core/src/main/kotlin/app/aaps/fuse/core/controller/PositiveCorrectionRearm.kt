package app.aaps.fuse.core.controller

/**
 * FREIGABE-NACHLAUF NACH ZERO-LATCH-LOESUNG UND NACHTENDE (Bauauftrag
 * Toni 25.08.), NUR IM KORREKTURKONTEXT.
 *
 * DER GEMESSENE ANLASS (Pflicht-Replay, 25.08. frueh): die Zero-TBR lief
 * bis 07:58; um 07:59 (BG 115, noch Nacht) war derselbe Bedarf 0,51 U vom
 * Nachtband gesperrt - um 08:00, EINE Minute spaeter, oeffnete das
 * Nachtende und bis 08:03 flossen 0,35 U. Danach fiel der BG 117 -> 106.
 * Eine harte Kante darf nicht in der naechsten Minute positive
 * Korrektur-SMBs oeffnen.
 *
 * DIE REGEL (Tonis Spezifikation): nach dem Ende einer verriegelten Null
 * (Zero-Latch loest) oder dem Ende des Nachtfensters gilt ein kurzer
 * Nachlauf [holdMin]; positive Korrektur-SMBs bleiben zu, bis der
 * Nachlauf um ist UND eine kurze, ZUSAMMENHAENGEND bestaetigte
 * Aufwaertslage steht ([confirmCycles] Zyklen UKF >= [upThresholdUkf],
 * gezaehlt AB dem Uebergang, 90-s-Anschluss). Kein Carry, kein
 * Nachholbolus; Mahlzeitenpfade unberuehrt (Aufrufer-Kontext). Der
 * Zero-Latch selbst bleibt als zweite Schutzlinie unveraendert.
 *
 * ZUSTAND prozesslokal - ein Neustart im Nachlauf verliert ihn
 * (Fehlrichtung "Riegel fehlt", nie "klemmt").
 */
object PositiveCorrectionRearm {

    enum class Source { NONE, ZERO_LATCH_RELEASED, NIGHT_END }

    data class Track(
        val ankerTs: Long = 0L,
        val quelle: Source = Source.NONE,
        val upStreak: Int = 0,
        val upLastTs: Long = 0L,
    )

    data class Result(
        val blocks: Boolean,
        val reason: String? = null,
        val source: Source = Source.NONE,
        /** Rest des Nachlaufs [min], 0 wenn nur noch die Bestaetigung fehlt. */
        val holdRestMin: Double? = null,
        val upConfirmStreak: Int = 0,
    )

    const val REASON_HOLD = "REARM_HOLD"
    const val REASON_UNCONFIRMED = "REARM_UP_UNCONFIRMED"

    /** Ein Uebergang ankert den Nachlauf NEU (Zaehler nullt) - die
     *  juengste Kante zaehlt, ihre Quelle wird exportiert. */
    fun anker(track: Track, nowTs: Long, quelle: Source): Track =
        Track(ankerTs = nowTs, quelle = quelle, upStreak = 0, upLastTs = 0L)

    /** RESTAURIERTE Identitaet nach Neustart (Tonis Review 25.08., P0.1):
     *  der Anker bleibt AKTIV, nur der Bestaetigungszaehler beginnt neu -
     *  die konservative Richtung, wie beim Zero-Latch. */
    fun restored(ankerTs: Long, quelle: Source): Track =
        if (ankerTs <= 0L) Track() else Track(ankerTs = ankerTs, quelle = quelle)

    @Suppress("LongParameterList")
    fun advance(
        track: Track,
        enabled: Boolean,
        nowTs: Long,
        ukfNow: Double,
        korrekturKontext: Boolean,
        holdMin: Int,
        confirmCycles: Int,
        upThresholdUkf: Double,
        /** Lage-Gesundheit (Tonis Review 25.08., P0.3): der Aufwaerts-
         *  Zaehler zaehlt NUR, wenn der Aufrufer die Lage als gesund und
         *  widerspruchsfrei meldet (Signal READY, q1 nicht fallend, kein
         *  Low/Descent/Rebound/Hold). Andernfalls nullt der Zyklus den
         *  Zaehler - eine ungesunde Lage darf kein fruehes Oeffnen
         *  vorbereiten. Der Nachlauf selbst blockt unveraendert. */
        lageGesund: Boolean = true,
    ): Pair<Track, Result> {
        if (!enabled) return Track() to Result(false)
        if (track.ankerTs <= 0L) return track to Result(false)

        val anschluss = track.upLastTs > 0L && nowTs > track.upLastTs &&
            nowTs - track.upLastTs <= 90_000L
        val aufwaerts = lageGesund && ukfNow.isFinite() && ukfNow >= upThresholdUkf
        val streak = if (aufwaerts) (if (anschluss) track.upStreak + 1 else 1) else 0
        val fortgeschrieben = track.copy(upStreak = streak, upLastTs = nowTs)

        val holdBisTs = track.ankerTs + holdMin * 60_000L
        val imNachlauf = nowTs < holdBisTs
        val bestaetigt = streak >= confirmCycles

        // FREIGABE beendet den Anker ganz - der naechste Uebergang beginnt
        // frisch. Der Riegel wirkt nur im Korrekturkontext, der Zustand
        // laeuft aber immer mit (ein Kontextwechsel findet keine blinde
        // Zaehlung vor).
        if (!imNachlauf && bestaetigt) return Track() to Result(
            false, source = track.quelle, upConfirmStreak = streak,
        )
        if (!korrekturKontext) {
            // NACH ABLAUF DER FRIST verfaellt der Anker auch ohne
            // Bestaetigung, sobald die Lage keine reine Korrekturlage mehr
            // ist. Ohne diese Kante haengt ein nie bestaetigter Anker
            // unbegrenzt nach und riegelt Minuten spaeter in einer voellig
            // anderen Lage (gemessen am 25.08.: Kante 08:00, Block erst
            // 08:23-08:26). WAEHREND der Frist verfaellt nichts - sie ist
            // der eigentliche Schutz.
            if (!imNachlauf) return Track() to Result(
                false, source = track.quelle, upConfirmStreak = streak,
            )
            return fortgeschrieben to Result(
                false, source = track.quelle, upConfirmStreak = streak,
            )
        }
        return fortgeschrieben to Result(
            blocks = true,
            reason = if (imNachlauf) REASON_HOLD else REASON_UNCONFIRMED,
            source = track.quelle,
            holdRestMin = ((holdBisTs - nowTs).coerceAtLeast(0L)) / 60_000.0,
            upConfirmStreak = streak,
        )
    }
}
