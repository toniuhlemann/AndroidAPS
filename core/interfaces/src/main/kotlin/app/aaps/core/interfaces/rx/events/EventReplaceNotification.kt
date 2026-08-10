package app.aaps.core.interfaces.rx.events

import app.aaps.core.interfaces.notifications.Notification

/**
 * Eine Meldung ATOMAR ersetzen.
 *
 * `EventDismissNotification` und `EventNewNotification` laufen ueber zwei
 * getrennte Rx-Streams mit je eigenem `observeOn(io)`. Wer nacheinander beide
 * sendet, hat KEINE Reihenfolgegarantie: wird das Hinzufuegen zuerst
 * verarbeitet, findet der Speicher die Kennung belegt, verwirft die neue
 * Meldung - und das spaeter eintreffende Entfernen raeumt die alte weg. Uebrig
 * bleibt gar keine Meldung.
 *
 * Dieses Ereignis fuehrt beides in EINEM Empfaenger aus. Es ersetzt die zwei
 * Ereignisse nicht, sondern ergaenzt sie fuer den Fall "derselbe Platz, neuer
 * Inhalt" - typischerweise eine Warnung, deren Text oder Dringlichkeit sich
 * geaendert hat und die deshalb erneut auffallen soll.
 */
class EventReplaceNotification(val notification: Notification) : Event()
