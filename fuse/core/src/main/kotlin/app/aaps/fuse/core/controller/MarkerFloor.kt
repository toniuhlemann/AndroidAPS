package app.aaps.fuse.core.controller

/**
 * DER BODEN DER MANUELLEN AUTORISIERUNG - als eine Zeile mit einem Namen.
 *
 * Modellbasierte Vetos duerfen den markerfinanzierten Anteil SENKEN, aber nicht
 * unter ihn fallen. Als BODEN am Ende der Modellkette und nicht als weitere
 * uebersprungene Pruefung: bei uebersprungenen Pruefungen muss jede einzelne
 * Stelle die Ausnahme kennen - vier Tore waren so geoeffnet, und ein fuenftes
 * nullte die Menge trotzdem. Ein Boden kennt keine Stelle, die ihn vergessen
 * koennte.
 *
 * WARUM ALS EIGENE FUNKTION, und der Grund ist ein Testbefund: der Fall, auf den
 * es ankommt - Basis GROESSER als der Markerboden, danach ein Veto - ist im
 * Runner nicht herstellbar. Er verlangt eine abtauchende Bahn (fuer das Veto)
 * UND Bedarf (fuer die groessere Basis), aber die Kandidatensuche prueft
 * denselben Guard und nullt die Basis schon vorher. Gemessen: bei BG 250
 * steigend liegt der Schwanz-Headroom bei +2,4 U, ein Veto entsteht nicht.
 *
 * Hier ist derselbe Fall eine Zeile: `apply(verified = 0, authCap = 0,05) ->
 * 0,05`. Das ist keine Ersatzhandlung fuer einen fehlenden Integrationstest,
 * sondern die richtige Stelle - die Rechnung hat keinen Zustand.
 */
object MarkerFloor {

    /**
     * @param verified die Entscheidung NACH der Modellkette (Kandidatensuche,
     *   Guard-Riegel, Schwanz-Veto, Rest-Zaehler).
     * @param authCapU der markerfinanzierte Anteil aus
     *   [FuseController.Decision.markerAuthorizedU] der GEHOBENEN Entscheidung -
     *   nicht deren `smbU`. Seit der Lift auch dann stempelt, wenn die Basis
     *   schon groesser war, sind die beiden verschieden, und `smbU` wuerde in
     *   genau dem Fall die vom Veto verworfene groessere Basis wiederherstellen.
     * @param kernelValid ob ein Einheitskern gebaut werden konnte.
     *
     *   Ein verworfener Kern ist KEIN Guard-Urteil ueber die Zukunft, sondern
     *   ein Integritaetsbefund ueber das Insulinmodell selbst
     *   (NON_FINITE_SAMPLE, NON_LINEAR_MODEL, negative Aktivitaet, IOB
     *   ausserhalb des gueltigen Bereichs, nicht ausgelaufener Modellschwanz).
     *   Der Einstellungstext sagt zu, dass unglaubwuerdige Messwerte nicht
     *   ueberstimmt werden.
     */
    fun apply(
        verified: FuseController.Decision,
        authCapU: Double,
        kernelValid: Boolean,
    ): FuseController.Decision {
        if (!kernelValid || authCapU <= 0.0) return verified

        // DIE PROVENIENZ UEBERLEBT AUCH OHNE ANHEBUNG (Toni 11.08.).
        //
        // Reicht die verifizierte Menge ohnehin, bleiben Dosis und Grund
        // unangetastet - der Boden ist ein Boden, keine Kappe und kein Stempel
        // fuer fremde Mengen. Die AUTORISIERUNGSGRENZE muss aber trotzdem
        // mitfahren, sonst geht sie eine Stufe spaeter verloren:
        //
        // `verified` kann aus dem Rueckfall auf `vetted` stammen - der
        // kleineren Basis, die das Veto ueberlebt hat. Die traegt keine
        // Provenienz. FuseTbrTranslator sieht dann bei SAFETY_ZERO keine
        // Autorisierung und nullt die GANZE Menge, obwohl der Markerdruck
        // mindestens authCapU deckt. Genau die Verwechslung, gegen die die
        // typisierte Herkunft ueberhaupt eingefuehrt wurde.
        if (verified.smbU >= authCapU)
            return if (verified.markerAuthorizedU >= authCapU) verified
            else verified.copy(markerAuthorizedU = authCapU)
        return verified.copy(
            smbU = authCapU,
            block = FuseController.Block.NONE,
            markerAuthorizedU = authCapU,
            bindingLimit = "markerAuth|" + verified.bindingLimit,
            // Die Basiskappen haben diese Menge nicht bestimmt. Leere Liste mit
            // eigener Stufe sagt das - eine stehengebliebene Basisliste
            // behauptete das Gegenteil.
            caps = emptyList(),
            capsStage = FuseController.STAGE_PRIME,
        )
    }
}
