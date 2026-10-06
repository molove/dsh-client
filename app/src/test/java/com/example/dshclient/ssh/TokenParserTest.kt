package com.example.dshclient.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TokenParserTest {

    @Test
    fun parse_standardLine_returnsPortAndToken() {
        val output = "http://127.0.0.1:3080/?token=abc123xyz"
        val result = TokenParser.parse(output)

        assertNotNull(result)
        assertEquals(3080, result?.first)
        assertEquals("abc123xyz", result?.second)
    }

    @Test
    fun parse_customPort_returnsCustomPortAndToken() {
        val output = "http://127.0.0.1:3232/?token=xyz"
        val result = TokenParser.parse(output)

        assertNotNull(result)
        assertEquals(3232, result?.first)
        assertEquals("xyz", result?.second)
    }

    @Test
    fun parse_noisyMultiLineOutput_returnsExtractedPortAndToken() {
        val output = """
            Oct 05 20:00:00 testhost systemd[1]: Started dsh-web.service.
            dsh-web listening at http://127.0.0.1:3080/?token=abc123xyz
            Oct 05 20:00:01 testhost dsh-web[1234]: Ready for connections
        """.trimIndent()
        val result = TokenParser.parse(output)

        assertNotNull(result)
        assertEquals(3080, result?.first)
        assertEquals("abc123xyz", result?.second)
    }

    @Test
    fun parse_emptyOutput_returnsNull() {
        assertNull(TokenParser.parse(""))
        assertNull(TokenParser.parse("   "))
        assertNull(TokenParser.parse(null))
    }

    @Test
    fun parse_invalidOutputWithoutToken_returnsNull() {
        assertNull(TokenParser.parse("Oct 05 20:00:00 testhost systemd[1]: No entries"))
        assertNull(TokenParser.parse("http://127.0.0.1:3080/"))
        assertNull(TokenParser.parse("http://127.0.0.1:/?token=xyz"))
    }

    @Test
    fun parse_tokenWithHyphensAndUnderscores_returnsPortAndToken() {
        val output = "http://127.0.0.1:3080/?token=token_with-hyphens_and-underscores_123"
        val result = TokenParser.parse(output)

        assertNotNull(result)
        assertEquals(3080, result?.first)
        assertEquals("token_with-hyphens_and-underscores_123", result?.second)
    }

    @Test
    fun parse_outputWithTrailingWhitespaceAndNewlines_returnsPortAndToken() {
        val output = "  http://127.0.0.1:3080/?token=validtoken123  \n\n"
        val result = TokenParser.parse(output)

        assertNotNull(result)
        assertEquals(3080, result?.first)
        assertEquals("validtoken123", result?.second)
    }

    @Test
    fun parse_outputWithAnsiColorSequences_returnsPortAndToken() {
        val output = "\u001B[32mhttp://127.0.0.1:3080/?token=colored_token_123\u001B[0m\n"
        val result = TokenParser.parse(output)

        assertNotNull(result)
        assertEquals(3080, result?.first)
        assertEquals("colored_token_123", result?.second)
    }

    @Test
    fun parse_multipleTokenUrls_returnsLastTokenUrl() {
        val output = """
            http://127.0.0.1:3080/?token=old_token_1
            http://127.0.0.1:3080/?token=new_token_2
        """.trimIndent()
        val result = TokenParser.parse(output)

        assertNotNull(result)
        assertEquals(3080, result?.first)
        assertEquals("new_token_2", result?.second)
    }

    @Test
    fun parse_invalidPortOutOfRange_returnsNull() {
        assertNull(TokenParser.parse("http://127.0.0.1:0/?token=xyz"))
        assertNull(TokenParser.parse("http://127.0.0.1:999999/?token=xyz"))
    }
}
