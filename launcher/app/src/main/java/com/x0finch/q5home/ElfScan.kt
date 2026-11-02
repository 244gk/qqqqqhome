package com.x0finch.q5home

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read-only look into a native library (.so, ELF 32 / 64-bit): the functions it exports (JNI entry points like
 * Java_com_wwc2_..._switchSource) and the readable strings that look like device nodes, serial ports, sysfs paths or
 * ioctl / video / MCU words. The library is only read as bytes: never loaded, never run.
 */
object ElfScan {

    class Result(val exports: List<String>, val strings: List<String>)

    /** Strings worth listing: device / sysfs paths, serial ports and words about video sources or the MCU. */
    private val INTERESTING = Regex(
        "^/(dev|sys|proc)/|tty|uart|serial|ioctl|avin|cvbs|lvds|video|channel|source|mcu|canbus|original|camera|mipi",
        RegexOption.IGNORE_CASE,
    )

    fun scan(elf: ByteArray, maxExports: Int = 150, maxStrings: Int = 80): Result? = runCatching {
        if (elf.size < 52 || elf[0] != 0x7F.toByte() || elf[1] != 'E'.code.toByte() || elf[2] != 'L'.code.toByte() ||
            elf[3] != 'F'.code.toByte()) return null
        val is64 = elf[4].toInt() == 2
        val bb = ByteBuffer.wrap(elf).order(if (elf[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        fun addr(off: Int): Long = if (is64) bb.getLong(off) else bb.getInt(off).toLong() and 0xFFFFFFFFL
        val shoff = addr(if (is64) 0x28 else 0x20).toInt()
        val shentsize = bb.getShort(if (is64) 0x3A else 0x2E).toInt() and 0xFFFF
        val shnum = bb.getShort(if (is64) 0x3C else 0x30).toInt() and 0xFFFF
        class Section(val type: Int, val offset: Int, val size: Int, val link: Int, val entsize: Int)
        val sections = (0 until shnum).mapNotNull { i ->
            val o = shoff + i * shentsize
            if (o <= 0 || o + shentsize > elf.size) return@mapNotNull null
            if (is64) Section(bb.getInt(o + 4), bb.getLong(o + 0x18).toInt(), bb.getLong(o + 0x20).toInt(), bb.getInt(o + 0x28),
                bb.getLong(o + 0x38).toInt())
            else Section(bb.getInt(o + 4), bb.getInt(o + 0x10), bb.getInt(o + 0x14), bb.getInt(o + 0x18), bb.getInt(o + 0x24))
        }
        fun cString(off: Int): String {
            var e = off
            while (e < elf.size && elf[e].toInt() != 0) e++
            return String(elf, off, e - off, Charsets.ISO_8859_1)
        }

        // Exported functions: defined (section index != 0) FUNC symbols in .dynsym (SHT_DYNSYM = 11).
        val exports = sortedSetOf<String>()
        sections.filter { it.type == 11 }.forEach { sym ->
            val strtab = sections.getOrNull(sym.link) ?: return@forEach
            val entsize = if (sym.entsize > 0) sym.entsize else if (is64) 24 else 16
            for (i in 0 until sym.size / entsize) {
                val o = sym.offset + i * entsize
                if (o + entsize > elf.size) break
                val nameOff = bb.getInt(o)
                val info = (if (is64) elf[o + 4] else elf[o + 12]).toInt() and 0xFF
                val shndx = bb.getShort(if (is64) o + 6 else o + 14).toInt() and 0xFFFF
                if (info and 0xF != 2 || shndx == 0 || nameOff <= 0) continue // STT_FUNC = 2
                val name = cString(strtab.offset + nameOff)
                if (name.isNotEmpty()) exports += name
            }
        }

        // Readable strings anywhere in the file (ASCII runs of 4+ chars), keeping only the interesting ones.
        val strings = linkedSetOf<String>()
        var start = -1
        for (i in 0..elf.size) {
            val c = if (i < elf.size) elf[i].toInt() and 0xFF else 0
            if (c in 0x20..0x7E) { if (start < 0) start = i; continue }
            if (start >= 0 && i - start in 4..120) {
                val s = String(elf, start, i - start, Charsets.ISO_8859_1)
                if (INTERESTING.containsMatchIn(s) && s !in exports) strings += s
            }
            start = -1
            if (strings.size >= maxStrings) break
        }
        // JNI entry points and non-library names first.
        Result(exports.sortedWith(compareBy({ !it.startsWith("Java_") }, { it })).take(maxExports), strings.toList())
    }.getOrNull()
}
