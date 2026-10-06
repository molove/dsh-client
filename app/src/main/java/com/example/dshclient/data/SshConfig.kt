package com.example.dshclient.data

data class SshConfig(
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val privateKey: String = "",
    val passphrase: String? = null,
    val pinnedHostKey: String? = null
) {
    val isValid: Boolean
        get() = host.isNotBlank() && port in 1..65535 && user.isNotBlank() && privateKey.isNotBlank()
}
