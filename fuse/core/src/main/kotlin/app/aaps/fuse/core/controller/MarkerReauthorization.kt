package app.aaps.fuse.core.controller

/**
 * ABBRUCH UND NEUAUTORISIERUNG DER MAHLZEIT.
 *
 * ===================================================================
 * WAS SICH HIER BEWUSST AENDERT
 * ===================================================================
 * Bis hierher galt: ein zweiter Markerdruck innerhalb des Markerfensters
 * ist DIESELBE Episode und bewaffnet das Fundament nicht neu. Das ist
 * richtig gegen doppeltes Druecken - aber es traf auch die Folge
 * "abgebrochen, dann bewusst neu markiert": danach fehlte die Phase A
 * ganz, und statt der Direktdosis liefen nur noch einzelne Prime-SMBs.
 *
 * Der Vertrag lautet jetzt: **die abgeschlossene Folge Abbruch ->
 * ausdruecklich neuer Marker autorisiert eine neue volle Huelle.**
 *
 * DAS IST EINE MENGENAENDERUNG, und zwar eine gewollte: sind von einer
 * Huelle von 4 U bereits 1 U abgegeben, erlaubt der neue Druck danach
 * weitere 4 U - zusammen also bis zu 5 U. IOB- und Sicherheitsgrenzen
 * koennen das begrenzen, sie verhindern die zusaetzliche AUTORISIERUNG
 * aber nicht. Wer diese Datei aendert, aendert eine Dosisgrenze.
 *
 * ===================================================================
 * WARUM KENNUNGEN UND NICHT ZEITSTEMPEL
 * ===================================================================
 * Ein neuerer Zeitstempel beweist keinen bewussten Neustart. Doppelte
 * Bedienereignisse tragen verschiedene Zeitstempel, verspaetete
 * Dialog-Rueckrufe kommen aus der Vergangenheit, und die Uhr kann
 * springen. Deshalb bekommt jede Autorisierung eine fortlaufende
 * KENNUNG, und die Widerrufsmarke traegt, welche Autorisierung sie
 * verbraucht hat.
 *
 * Aus derselben Ueberlegung wird die Marke nicht geloescht, sondern
 * ZUGEORDNET: nach einem Absturz steht damit fest, ob die neue
 * Autorisierung schon existiert - loeschen haette beide Faelle gleich
 * aussehen lassen.
 *
 * ===================================================================
 * WAS AUSDRUECKLICH NICHT PASSIERT
 * ===================================================================
 * Nichts hiervon setzt Buchhaltung zurueck. Bereits abgegebenes Insulin,
 * offene Pumpenauftraege, Transportreservierungen, globale
 * Expositionsgrenzen und laufende Zeitfenster bleiben, wie sie sind.
 * Aus dem widerrufenen Rest entsteht keine neue Anforderung, und es gibt
 * keine Nachlieferung. Eine neue Huelle ist eine ERLAUBNIS, keine
 * zugesagte Abgabe.
 */
object MarkerReauthorization {

    /**
     * Eine widerrufene Autorisierung. `consumedByAuthId == null` heisst:
     * die Marke ist offen und kann EINE neue volle Huelle eroeffnen.
     */
    data class Revocation(
        val authId: String,
        val markerTs: Long,
        val atTs: Long,
        val consumedByAuthId: String? = null,
    ) {
        val offen: Boolean get() = consumedByAuthId == null
    }

    /** Eine laufende Mahlzeitenautorisierung mit eigener Kennung. */
    data class Authorization(
        val id: String,
        val markerTs: Long,
        /** Der Widerruf, den GENAU DIESE Autorisierung verbraucht hat. */
        val consumedRevocationAuthId: String? = null,
    ) {
        /** Entstanden aus der Folge Abbruch -> neuer Druck. */
        val nachWiderruf: Boolean get() = consumedRevocationAuthId != null
    }

    /** Beides zusammen - sie werden gemeinsam festgeschrieben, nie einzeln. */
    data class Neuautorisierung(val auth: Authorization, val revocation: Revocation?)

    fun kennung(seq: Long): String = "auth-$seq"

    /**
     * DER ABBRUCH. Aus der laufenden Autorisierung wird eine offene Marke.
     * Gibt es keine laufende, entsteht auch keine Marke: ein Abbruch ohne
     * Autorisierung ist kein Ereignis, das spaeter eine Huelle eroeffnen
     * duerfte.
     */
    fun widerrufe(aktuell: Authorization?, atTs: Long): Revocation? =
        aktuell?.let { Revocation(authId = it.id, markerTs = it.markerTs, atTs = atTs) }

    /**
     * DER NEUE DRUCK. Neue Kennung; liegt eine OFFENE Marke vor, wird sie
     * im selben Zug dieser Autorisierung zugeordnet.
     *
     * Beide Werte gehoeren in EINEN Persistenzschritt. Wuerde erst
     * bewaffnet und spaeter die Marke zugeordnet, koennte ein Absturz
     * dazwischen eine zweite volle Huelle erlauben.
     */
    fun autorisiere(seq: Long, markerTs: Long, offeneMarke: Revocation?): Neuautorisierung {
        val id = kennung(seq)
        val marke = offeneMarke?.takeIf { it.offen }
        return Neuautorisierung(
            auth = Authorization(id = id, markerTs = markerTs, consumedRevocationAuthId = marke?.authId),
            revocation = marke?.copy(consumedByAuthId = id),
        )
    }

    /**
     * DARF DIESE AUTORISIERUNG EINE NEUE VOLLE HUELLE EROEFFNEN?
     *
     * Nur wenn sie aus einem Widerruf hervorging UND das Fundament nicht
     * schon fuer GENAU DIESE Kennung bewaffnet wurde. Die zweite
     * Bedingung traegt die Wiederholungsfestigkeit: dieselbe
     * Autorisierung erneut zu verarbeiten - durch einen doppelten
     * Rueckruf, einen erneuten Zyklus oder einen App-Neustart - findet
     * dieselbe Kennung vor und oeffnet nichts.
     */
    fun neueHuelle(auth: Authorization?, fundamentBewaffnetFuer: String?): Boolean {
        val a = auth ?: return false
        if (!a.nachWiderruf) return false
        return a.id != fundamentBewaffnetFuer
    }

    /**
     * DARF DIESES BEDIENEREIGNIS NOCH WIRKEN?
     *
     * Ein Vergleich mit dem ZULETZT verarbeiteten Ereignis reicht nicht.
     * Die Folge
     *
     *     Start E1  ->  Abbruch E2  ->  verspaeteter Rueckruf E1
     *
     * kaeme damit durch: E1 ist nicht E2, also wuerde erneut umgeschaltet -
     * nach dem Abbruch moeglicherweise mit einer weiteren vollen Huelle.
     *
     * Deshalb ist die Ereigniskennung GEORDNET: nur ein Ereignis, das ECHT
     * JUENGER ist als das zuletzt angewandte, wirkt noch. Der verspaetete
     * E1 traegt eine kleinere Ordnung und faellt heraus.
     *
     * Die Ordnung ist eine Folge, keine Uhr - eine zurueckspringende Uhr
     * darf einen alten Rueckruf nicht wieder gueltig machen.
     */
    fun ereignisWirkt(ordnung: Long?, zuletztAngewandt: Long): Boolean =
        ordnung == null || ordnung > zuletztAngewandt

    /** Aus der Kennung `"i<n>"` die Ordnung; `null` = keine Kennung. */
    fun ordnungVon(ereignisId: String?): Long? =
        ereignisId?.removePrefix("i")?.toLongOrNull()

    fun ereignisKennung(ordnung: Long): String = "i$ordnung"

    /**
     * IST DIESER MARKER DAUERHAFT WIDERRUFEN?
     *
     * Der Widerruf steht im Ledger, der Markerzeitpunkt in den
     * Preferences. Endet der Prozess zwischen beiden Schreibvorgaengen,
     * bleibt in den Preferences ein Marker stehen, den es nicht mehr gibt.
     * Ohne diesen Abgleich laesen ihn `mealMarkerArmedTs()` und der Runner
     * einfach weiter - der Widerruf waere folgenlos.
     *
     * Der Ledger ist die Wahrheit: nennt seine Marke genau diesen
     * Zeitpunkt und gibt es keine laufende Autorisierung dafuer, ist der
     * Marker weg. Beide Leser rufen DIESE Funktion, damit sie nicht zu
     * verschiedenen Antworten kommen.
     */
    fun widerrufen(markerTs: Long, auth: Authorization?, revocation: Revocation?): Boolean {
        if (markerTs <= 0L) return false
        val marke = revocation ?: return false
        if (marke.markerTs != markerTs) return false
        // Eine laufende Autorisierung fuer GENAU diesen Zeitpunkt hebt den
        // Widerruf auf - dann wurde nach dem Abbruch neu autorisiert.
        return auth?.markerTs != markerTs
    }
}
