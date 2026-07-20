package app.aaps.plugins.aps.iobaction

import java.security.MessageDigest

/**
 * LocalCommandChannel — PURE Caller-Entscheidung (Spec v1.2 C1 / R3-C1).
 * Shared-UID-Prinzip: die GESAMTE UID ist das Sicherheitsprinzip — das erwartete
 * Viewer-Paket muss vorhanden sein UND JEDES Paket der UID muss einen vertrauten
 * Signer besitzen; eine einzige nicht vertrauenswuerdige Zusatz-App lehnt ab.
 * Digest-Vergleich auf rohen SHA-256-Bytes in konstanter Zeit.
 */
object LocalCommandAuth {

    const val EXPECTED_VIEWER_PACKAGE = "de.toniuhlemann.iobactionnativeviewer"

    data class PackageSigners(val packageName: String, val signerSha256: List<ByteArray>)

    fun decide(packagesOfUid: List<PackageSigners>, trustedDigests: List<ByteArray>): Boolean {
        if (trustedDigests.isEmpty()) return false                      // default-deny
        if (packagesOfUid.isEmpty()) return false
        if (packagesOfUid.none { it.packageName == EXPECTED_VIEWER_PACKAGE }) return false
        // JEDES Paket der UID (auch Multi-Signer: JEDER Signer) muss vertraut sein.
        for (pkg in packagesOfUid) {
            if (pkg.signerSha256.isEmpty()) return false
            for (signer in pkg.signerSha256) {
                if (trustedDigests.none { MessageDigest.isEqual(it, signer) }) return false
            }
        }
        return true
    }
}
