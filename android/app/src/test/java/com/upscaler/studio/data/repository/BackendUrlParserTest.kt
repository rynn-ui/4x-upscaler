package com.upscaler.studio.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendUrlParserTest {

    @Test
    fun parsesPercentEncodedDeepLinkWithToken() {
        val raw = "upscaler://connect?url=https%3A%2F%2Fdemo-test.trycloudflare.com%2F&token=tok%2B1%2F2%3D"
        val res = BackendUrlParser.parsePairingInput(raw)
        assertTrue(res.isSuccess)
        val parsed = res.getOrThrow()
        assertEquals("https://demo-test.trycloudflare.com", parsed.baseUrl)
        assertEquals("tok+1/2=", parsed.authToken)
    }

    @Test
    fun preservesLocalDevelopmentHttpTargets() {
        val localhost = BackendUrlParser.parsePairingInput("http://127.0.0.1:8000/")
        assertEquals("http://127.0.0.1:8000", localhost.getOrThrow().baseUrl)

        val lan = BackendUrlParser.parsePairingInput("192.168.1.42:8000")
        assertEquals("http://192.168.1.42:8000", lan.getOrThrow().baseUrl)

        val emulator = BackendUrlParser.parsePairingInput("http://10.0.2.2:8000")
        assertEquals("http://10.0.2.2:8000", emulator.getOrThrow().baseUrl)
    }

    @Test
    fun enforcesHttpsForPublicHostsAndRejectsMalformedOrEmpty() {
        val upgraded = BackendUrlParser.parsePairingInput("http://abc-xyz.trycloudflare.com")
        assertEquals("https://abc-xyz.trycloudflare.com", upgraded.getOrThrow().baseUrl)
        assertNull(upgraded.getOrThrow().authToken)

        assertTrue(BackendUrlParser.parsePairingInput("").isFailure)
        assertTrue(BackendUrlParser.parsePairingInput("   ").isFailure)
        assertTrue(BackendUrlParser.parsePairingInput("javascript:alert(1)").isFailure)
        assertTrue(BackendUrlParser.parsePairingInput("http://public-example.org").isFailure)
        assertTrue(BackendUrlParser.parsePairingInput("upscaler://other?url=https://a.com").isFailure)
    }
}

