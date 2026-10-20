package app.aaps.core.nssdk.networking

/**
 * Removes secrets from text that goes into the log.
 *
 * The token refresh puts the Nightscout access token into the URL path, `/api/v2/authorization/request/<token>`
 * (see [NsAuth]). The request logger wrote that URL as it was, so every refresh copied the long-lived access token
 * into the AAPS log in clear text. Logs are shared for support and read by analysis tools, so the token must not be
 * in them.
 */
internal object NsLogRedaction {

    private val tokenInPath = Regex("(/authorization/request/)[^/?#\\s]+")

    /** The URL with the access token replaced by `***`. Every other part stays as it is. */
    fun url(url: String): String = tokenInPath.replace(url, "$1***")
}
