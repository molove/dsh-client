package com.example.dshclient.ssh

/**
 * Parses authentication token and loopback port from systemd journal query output.
 *
 * Query executed on remote host:
 * since=$(systemctl show dsh-web -p ActiveEnterTimestamp --value); journalctl -u dsh-web --since "$since" --no-pager | grep -o 'http://127.0.0.1:[0-9]*' | tail -1
 */
object TokenParser {

    private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[;?0-9]*[a-zA-Z]")
    private val TOKEN_URL_REGEX = Regex("""http://127\.0\.0\.1:([0-9]+)/\?token=([a-zA-Z0-9_-]+)""")

    /**
     * Parses the journal output string, strips ANSI escapes if present,
     * and extracts the remote port and auth token from the matched URL.
     *
     * @param output raw stdout/stderr from token scraper command
     * @return Pair(port, token) or null if output is blank, invalid, or no matching URL found
     */
    fun parse(output: String?): Pair<Int, String>? {
        if (output.isNullOrBlank()) return null

        val cleanOutput = output.replace(ANSI_ESCAPE_REGEX, "").trim()
        val match = TOKEN_URL_REGEX.findAll(cleanOutput).lastOrNull() ?: return null

        val port = match.groupValues[1].toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        val token = match.groupValues[2]
        if (token.isBlank()) return null

        return Pair(port, token)
    }

    /**
     * Convenience alias for [parse].
     */
    fun extractToken(output: String?): Pair<Int, String>? = parse(output)
}
