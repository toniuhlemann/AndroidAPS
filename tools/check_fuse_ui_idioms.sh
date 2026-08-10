#!/usr/bin/env bash
#
# FUSE-Bedienwaechter: die Oberflaeche benutzt die Hausmittel der App.
#
# ## Warum es diese Pruefung gibt
#
# Am 10.08.2026 hat Toni drei Fehler am Geraet gefunden, die KEINER von 1135
# Tests gezeigt hat. Alle drei lagen in der Bedienung, und zwei davon hatten
# dieselbe Ursache: nicht das benutzt, was AAPS dafuer hat.
#
#   1. Unterbildschirm "Reparatur" blieb SCHWARZ - eine handgeschriebene Liste
#      erlaubter Schluessel kannte den neuen Abschnitt nicht. (Strukturell
#      behoben: die Abschnitte registrieren sich selbst, es gibt keine zweite
#      Liste mehr. Nichts zu pruefen.)
#   2. Der Bestaetigungsdialog kam als LEERER WEISSER KASTEN - weisse Schrift
#      auf weissem Grund, weil `android.app.AlertDialog` das Thema des
#      Contexts nicht so uebernimmt wie erwartet.
#   3. Ein Ledger-Hold stoppte die Abgabe, ohne dass es irgendwo stand.
#      (Behoben und getestet, s. FuseScreenModelTest.)
#
# Fall 2 ist der, den eine Maschine finden kann: es ist eine reine
# Textbedingung, sie ist billig zu befolgen und sie faellt bei einem Review
# nicht auf - der Aufruf sieht voellig normal aus, und der Fehler zeigt sich
# erst auf einem dunklen Bildschirm.
#
# Ein Verhaltenstest waere hier das falsche Mittel: er braeuchte Robolectric,
# also ein komplettes Test-Framework im Fork, fuer eine Eigenschaft, die ein
# grep beweist.
#
# Aufruf:  tools/check_fuse_ui_idioms.sh
# Beendet mit 0, wenn sauber, sonst 1.

set -euo pipefail

FEHLER=0

pruefe() {
    local muster="$1" was="$2" statt="$3"
    # Nur AUFRUFE, nicht Erwaehnungen: das Muster enthaelt die Klammer. So darf
    # ein Kommentar die Falle weiter beim Namen nennen, ohne die Pruefung
    # auszuloesen - eine Regel, die ihre eigene Begruendung verbietet, wird
    # frueher oder spaeter unverstanden entfernt.
    local treffer
    treffer="$(grep -rn --include='*.kt' -- "$muster" fuse/ || true)"
    if [ -n "$treffer" ]; then
        echo "VERLETZUNG: $was"
        echo "$treffer" | sed 's/^/  /'
        echo "  -> stattdessen: $statt"
        echo
        FEHLER=1
    fi
}

pruefe 'android.app.AlertDialog.Builder(' \
    'Plattform-Dialog in FUSE' \
    'AlertDialogHelper.Builder(context) bzw. OKDialog - beide legen einen ContextThemeWrapper unter den Material-Builder'

pruefe 'android.app.ProgressDialog(' \
    'Plattform-Fortschrittsdialog in FUSE' \
    'ein Material-Dialog aus core:ui'

if [ "$FEHLER" -eq 0 ]; then
    echo "OK: FUSE benutzt die Dialog-Hausmittel der App."
    exit 0
fi

echo "Regel: die FUSE-Oberflaeche baut auf core:ui auf, nicht auf den"
echo "Plattformklassen. Ein Plattform-Dialog erbt das dunkle AAPS-Thema nicht"
echo "und erscheint als leerer weisser Kasten - sichtbar erst am Geraet."
exit 1
