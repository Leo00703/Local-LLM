package com.druk.lmplayground.tools

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address filter behind web_fetch's SSRF guard. Literals only: nothing is resolved. */
class WebFetchToolTest {

    private fun internal(literal: String) = WebFetchTool.isInternal(InetAddress.getByName(literal))

    @Test
    fun privateAndLocalRangesAreInternal() {
        assertTrue(internal("127.0.0.1"))
        assertTrue(internal("10.1.2.3"))
        assertTrue(internal("172.16.0.5"))
        assertTrue(internal("192.168.1.20"))
        assertTrue(internal("169.254.169.254"))
        assertTrue(internal("0.0.0.0"))
        assertTrue(internal("::1"))
    }

    // What java.net's own checks miss.
    @Test
    fun carrierGradeNatAndUniqueLocalAreInternal() {
        assertTrue(internal("100.64.0.1"))
        assertTrue(internal("100.100.100.100"))
        assertTrue(internal("100.127.255.254"))
        assertTrue(internal("fd00::1"))
        assertTrue(internal("fc00::1234"))
    }

    @Test
    fun publicAddressesAreNot() {
        assertFalse(internal("8.8.8.8"))
        assertFalse(internal("1.1.1.1"))
        assertFalse(internal("100.63.255.255"))
        assertFalse(internal("100.128.0.1"))
        assertFalse(internal("2606:4700:4700::1111"))
    }
}
