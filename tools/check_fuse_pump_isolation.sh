#!/usr/bin/env bash
#
# FUSE-Architekturwaechter: der Fork darf KEINEN Pumpentreiber aendern.
#
# Warum das eine eigene Pruefung braucht: die Regel ist billig zu befolgen und
# teuer zu verletzen. Eine einzige "kleine" Zeile in BLEComm oder MedtrumService
# nimmt FUSE die Portierbarkeit auf neue AAPS-Staende - und sie faellt bei einem
# Review nicht auf, weil sie an der richtigen Stelle richtig aussieht.
#
# WARUM NICHT DER KUMULIERTE DIFF. `git diff BASIS HEAD -- pump/` waere nach dem
# naechsten AAPS-Merge dauerhaft rot: Upstream aendert seine eigenen Treiber, und
# das ist erlaubt. Geprueft wird deshalb die HERKUNFT, nicht der Zustand - kein
# im Fork GESCHRIEBENER Commit darf pump/ anfassen. Upstream-Aenderungen kommen
# als Merge-Commit herein und sind ausgenommen; alles andere ist eigene Arbeit.
#
# Aufruf:
#   tools/check_fuse_pump_isolation.sh [<basis>]
# Vorgabe fuer <basis> ist der erste FUSE-Commit.
#
# Beendet mit 0, wenn sauber, sonst 1 und einer Liste der Verletzer.

set -euo pipefail

# Erster Commit, der fuse/ angelegt hat. Ab hier gilt die Regel.
BASE="${1:-9e7658cd766640a5a5323612f62c3fb476cde6fb}"

if ! git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null; then
    echo "FEHLER: Basis-Commit $BASE nicht gefunden."
    echo "Bei einem flachen Klon: git fetch --unshallow"
    exit 2
fi

# --no-merges ist der ganze Trick: ein Merge-Commit, der Upstream-Aenderungen an
# pump/ hereinbringt, ist kein Verstoss. Ein selbst geschriebener Commit ist es.
VERLETZER="$(git log --no-merges --format='%H %an %s' "$BASE..HEAD" -- pump/ || true)"

if [ -z "$VERLETZER" ]; then
    echo "OK: kein im Fork geschriebener Commit aendert pump/."
    exit 0
fi

echo "VERLETZUNG der FUSE-Architekturauflage: pump/ wurde im Fork geaendert."
echo
echo "$VERLETZER" | while read -r sha rest; do
    echo "  $sha  $rest"
    git show --stat --format='' "$sha" -- pump/ | sed 's/^/      /'
done
echo
echo "Regel: keine FUSE-Sicherheitsinvariante haengt an einer privaten"
echo "Treiberstelle. Pumpenerkennung nur ueber Pump.model()/PumpType."
echo "Siehe FUSE_UPSTREAM_PORTING_MATRIX.md, Abschnitt 0."
exit 1
