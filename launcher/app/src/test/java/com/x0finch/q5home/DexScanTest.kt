package com.x0finch.q5home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies disassembly against a real dex (test/resources/oem-switch.dex, compiled from oem-switch.java.txt with javac + d8):
 * the results must match dexdump.
 */
class DexScanTest {
    private val dex = javaClass.classLoader!!.getResourceAsStream("oem-switch.dex")!!.readBytes()

    @Test
    fun findsMethodsUsingTargetStringsInOrder() {
        val methods = DexScan.scanDex(dex, { it.contains("original_screen") })
        assertEquals(1, methods.size)
        assertEquals("com.wwc2.fake.OemSwitch.enterOriginalMedia", methods[0].name)
        assertEquals(listOf(
            "int 1",
            "call Integer.valueOf",
            "str \"original_screen\"",
            "call Map.put",
            "str \"content://com.wwc2.canmcusdk.CanbusProvider/original_screen\"",
            "put com.wwc2.fake.OemSwitch.sink",
            "get System.out",
            "str \"enter_original_media\"",
            "call PrintStream.println",
        ), methods[0].events)
    }

    @Test
    fun noMatchAndGarbageAreSafe() {
        assertTrue(DexScan.scanDex(dex, { it == "not-there" }).isEmpty())
        assertTrue(DexScan.scanDex(ByteArray(10), { true }).isEmpty())
        // A truncated dex must not crash.
        assertTrue(runCatching { DexScan.scanDex(dex.copyOf(300), { true }) }.isSuccess)
    }
}
