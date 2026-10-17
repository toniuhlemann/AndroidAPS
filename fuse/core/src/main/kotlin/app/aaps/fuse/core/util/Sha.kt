package app.aaps.fuse.core.util

import java.security.MessageDigest

/** Ein Hash, eine Stelle. Verlustfreie Zahlenform ueber die IEEE-754-Bits —
 *  eine gerundete Textform koennte zwei verschiedene Kerne auf denselben Hash
 *  abbilden, und genau das soll ein Provenienzhash verhindern. */
object Sha {

    fun of(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun lossless(d: Double): String {
        require(d.isFinite()) { "non-finite value must never reach the hash path" }
        return java.lang.Double.doubleToLongBits(if (d == 0.0) 0.0 else d).toString()
    }
}
