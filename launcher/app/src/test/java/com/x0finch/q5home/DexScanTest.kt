package com.x0finch.q5home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies disassembly against a real dex (test/resources/oem-switch.dex, compiled from oem-switch.java.txt with javac + d8):
 * the results must match dexdump. recon-extra.dex (from recon-extra.java.txt, same tools) covers call / field matching,
 * call sites and constant tables.
 */
class DexScanTest {
    private val dex = javaClass.classLoader!!.getResourceAsStream("oem-switch.dex")!!.readBytes()
    private val extra = javaClass.classLoader!!.getResourceAsStream("recon-extra.dex")!!.readBytes()

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

    @Test
    fun findsMethodsByCalledOrOwnName() {
        val match = { s: String -> Regex("switchOriginalMenu").containsMatchIn(s) }
        val methods = DexScan.scanDex(extra, { false }, refs = match)
        // The caller and the method itself; the static initializer that also calls it is skipped.
        assertEquals(listOf("com.wwc2.fake.Mcu.switchOriginalMenu", "com.wwc2.fake.Tap.onOriginalClick"), methods.map { it.name }.sorted())
        val tap = methods.first { it.name.endsWith("onOriginalClick") }
        assertEquals(listOf(
            "int 1",
            "call com.wwc2.fake.Mcu.switchOriginalMenu",
            "str \"com.wwc2.main.ACTION_SWITCH_SOURCE\"",
            "call Intent.<init>",
            "str \"source\"",
            "int 33",
            "call Intent.putExtra",
            "call Context.sendBroadcast",
        ), tap.events)
    }

    @Test
    fun listsCallSitesWithTheirStrings() {
        val broadcasts = DexScan.sitesDex(extra, { Regex("\\.sendBroadcast$").containsMatchIn(it) })
        assertEquals(1, broadcasts.size)
        assertEquals("com.wwc2.fake.Tap.onOriginalClick", broadcasts[0].name)
        assertEquals(listOf("Context.sendBroadcast"), broadcasts[0].calls)
        assertEquals(listOf("com.wwc2.main.ACTION_SWITCH_SOURCE", "source"), broadcasts[0].strings)

        val starts = DexScan.sitesDex(extra, { Regex("\\.(startActivity|setClassName)$").containsMatchIn(it) })
        assertEquals(listOf("Intent.setClassName", "Context.startActivity"), starts.single().calls)
        assertEquals(listOf("com.zjinnova.zlink", "com.zjinnova.zlink.MainActivity"), starts.single().strings)
    }

    @Test
    fun readsConstantTables() {
        val tables = DexScan.constantsDex(extra, { it.endsWith("AvinInterface") || it.endsWith(".Mode") })
        val avin = tables.first { it.className == "com.wwc2.fake.AvinInterface" }
        assertEquals(listOf(
            "ACTION" to "\"com.wwc2.main.ACTION_SWITCH_SOURCE\"",
            "BIG" to "5000000000L (0x12A05F200)",
            "NEGATIVE" to "-300 (0xFFFFFED4)",
            "ON" to "true",
            "RATIO" to "1.5f",
            "SWITCH_ORIGINAL" to "33 (0x21)",
        ), avin.fields)
        // Set in code, not a constant: name only.
        assertEquals(listOf("ORIGINAL" to null), tables.first { it.className == "com.wwc2.fake.Mode" }.fields)
        assertTrue(DexScan.constantsDex(ByteArray(10), { true }).isEmpty())
        assertTrue(runCatching { DexScan.constantsDex(extra.copyOf(400), { true }) }.isSuccess)
    }
}
