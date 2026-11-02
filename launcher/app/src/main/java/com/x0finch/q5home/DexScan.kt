package com.x0finch.q5home

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Minimal read-only dex disassembler: finds methods in an APK that "use certain strings" and lists, in order, the
 * strings, integer constants, called methods and read/written fields in those methods. Used to see what the vendor
 * launcher actually does when "原车媒体" (OEM media) is tapped (which provider it writes which value to, which broadcast
 * it sends), without running any code or changing anything.
 *
 * Only knows instruction lengths and a few instructions (const, const-string, invoke, iget/iput/sget/sput), enough to see the call order.
 */
object DexScan {

    class Method(val name: String, val events: List<String>)

    /** Finds methods in all of the APK's dex files that reference strings matched by [target]. */
    fun scanApk(apk: File, target: (String) -> Boolean, maxMethods: Int = 15, maxEvents: Int = 60): List<Method> {
        val out = mutableListOf<Method>()
        runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { Regex("classes\\d*\\.dex").matches(it.name) && it.size in 1..(64L shl 20) }
                    .forEach { e ->
                        if (out.size >= maxMethods) return@forEach
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        out += scanDex(bytes, target, maxMethods - out.size, maxEvents)
                    }
            }
        }
        return out
    }

    /** A broken / truncated dex doesn't crash: returns what was found so far. */
    fun scanDex(dex: ByteArray, target: (String) -> Boolean, maxMethods: Int = 15, maxEvents: Int = 60): List<Method> {
        val found = mutableListOf<Method>()
        runCatching { scan(dex, target, maxMethods, maxEvents, found) }
        return found
    }

    private fun scan(dex: ByteArray, target: (String) -> Boolean, maxMethods: Int, maxEvents: Int, found: MutableList<Method>) {
        val d = Dex(dex)
        if (!d.valid) return
        val wanted = (0 until d.stringCount).filter { target(d.string(it)) }.toHashSet()
        if (wanted.isEmpty()) return
        for (c in 0 until d.classCount) {
            val classDataOff = d.u4(d.classDefsOff + c * 32 + 24)
            if (classDataOff == 0) continue
            for ((methodIdx, codeOff) in d.methodsOf(classDataOff)) {
                if (codeOff == 0) continue
                val insns = d.instructions(codeOff) ?: continue
                if (insns.none { it.op in CONST_STRING && it.index in wanted }) continue
                val events = insns.mapNotNull { d.describe(it) }.take(maxEvents)
                found += Method(d.methodName(methodIdx), events)
                if (found.size >= maxMethods) return
            }
        }
    }

    private val CONST_STRING = setOf(0x1a, 0x1b)

    /** One instruction: opcode + referenced index (string / method / field) or literal. */
    private class Insn(val op: Int, val index: Int, val literal: Long?)

    private class Dex(val b: ByteArray) {
        val bb: ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val valid = b.size > 0x70 && String(b, 0, 3, Charsets.ISO_8859_1) == "dex"
        val stringCount = if (valid) u4(0x38) else 0
        val stringIdsOff = if (valid) u4(0x3C) else 0
        val typeIdsOff = if (valid) u4(0x44) else 0
        val fieldIdsOff = if (valid) u4(0x54) else 0
        val methodIdsOff = if (valid) u4(0x5C) else 0
        val classCount = if (valid) u4(0x60) else 0
        val classDefsOff = if (valid) u4(0x64) else 0
        private val strings = HashMap<Int, String>()

        fun u2(off: Int) = bb.getShort(off).toInt() and 0xFFFF
        fun u4(off: Int) = bb.getInt(off)

        fun string(i: Int): String = strings.getOrPut(i) {
            runCatching {
                var p = u4(stringIdsOff + i * 4)
                while (b[p].toInt() and 0x80 != 0) p++
                p++
                val start = p
                while (b[p].toInt() != 0) p++
                String(b, start, p - start, Charsets.UTF_8)
            }.getOrDefault("?")
        }

        fun type(i: Int) = string(u4(typeIdsOff + i * 4))

        fun methodName(i: Int): String {
            val off = methodIdsOff + i * 8
            return "${shortClass(type(u2(off)))}.${string(u4(off + 4))}"
        }

        fun fieldName(i: Int): String {
            val off = fieldIdsOff + i * 8
            return "${shortClass(type(u2(off)))}.${string(u4(off + 4))}"
        }

        /** All methods in class_data: (method_idx, code_off). */
        fun methodsOf(off: Int): List<Pair<Int, Int>> {
            var p = off
            fun uleb(): Int {
                var result = 0; var shift = 0
                while (true) {
                    val x = b[p++].toInt() and 0xFF
                    result = result or ((x and 0x7F) shl shift)
                    if (x and 0x80 == 0) return result
                    shift += 7
                }
            }
            val staticFields = uleb(); val instanceFields = uleb()
            val direct = uleb(); val virtual = uleb()
            repeat(staticFields + instanceFields) { uleb(); uleb() }
            val out = mutableListOf<Pair<Int, Int>>()
            for (group in listOf(direct, virtual)) {
                var idx = 0
                repeat(group) {
                    idx += uleb(); uleb()
                    out += idx to uleb()
                }
            }
            return out
        }

        fun instructions(codeOff: Int): List<Insn>? = runCatching {
            val count = u4(codeOff + 12)
            val base = codeOff + 16
            val list = mutableListOf<Insn>()
            var pc = 0
            while (pc < count) {
                val unit = u2(base + pc * 2)
                val op = unit and 0xFF
                // Data payloads (switch tables, array data): skip by length.
                if (op == 0x00 && unit != 0) {
                    pc += when (unit) {
                        0x0100 -> u2(base + pc * 2 + 2) * 2 + 4
                        0x0200 -> u2(base + pc * 2 + 2) * 4 + 2
                        0x0300 -> {
                            val width = u2(base + pc * 2 + 2)
                            val size = u4(base + pc * 2 + 4)
                            (size * width + 1) / 2 + 4
                        }
                        else -> 1
                    }
                    continue
                }
                val len = LENGTH[op]
                val u1 = if (len > 1) u2(base + pc * 2 + 2) else 0
                val index = when (op) {
                    0x1b -> u4(base + pc * 2 + 2)
                    else -> u1
                }
                val literal: Long? = when (op) {
                    0x12 -> ((unit shl 16) shr 28).toLong() // const/4: high 4 bits, signed
                    0x13 -> u1.toShort().toLong()
                    0x14 -> u4(base + pc * 2 + 2).toLong()
                    0x15 -> (u1 shl 16).toLong()
                    else -> null
                }
                list += Insn(op, index, literal)
                pc += len
            }
            list
        }.getOrNull()

        fun describe(i: Insn): String? = when (i.op) {
            0x1a, 0x1b -> "str \"${string(i.index).take(100)}\""
            0x12, 0x13, 0x14, 0x15 -> "int ${i.literal}"
            in 0x6e..0x72, in 0x74..0x78 -> "call ${methodName(i.index)}"
            in 0x52..0x58 -> "get ${fieldName(i.index)}"
            in 0x59..0x5f -> "put ${fieldName(i.index)}"
            in 0x60..0x66 -> "get ${fieldName(i.index)}"
            in 0x67..0x6d -> "put ${fieldName(i.index)}"
            else -> null
        }

        /** java / android classes keep only the simple name; vendor classes keep the full package name. */
        private fun shortClass(desc: String): String {
            val name = desc.removePrefix("L").removeSuffix(";").replace('/', '.')
            return if (name.startsWith("java.") || name.startsWith("android.")) name.substringAfterLast('.') else name
        }
    }

    /** Instruction length per opcode (in 16-bit units), per the Dalvik instruction format table. */
    private val LENGTH = IntArray(256) { 1 }.also { t ->
        fun set(range: IntRange, len: Int) = range.forEach { t[it] = len }
        set(0x02..0x02, 2); set(0x03..0x03, 3); set(0x05..0x05, 2); set(0x06..0x06, 3)
        set(0x08..0x08, 2); set(0x09..0x09, 3)
        set(0x13..0x13, 2); set(0x14..0x14, 3); set(0x15..0x16, 2); set(0x17..0x17, 3)
        set(0x18..0x18, 5); set(0x19..0x1a, 2); set(0x1b..0x1b, 3); set(0x1c..0x1c, 2)
        set(0x1f..0x20, 2); set(0x22..0x23, 2); set(0x24..0x26, 3)
        set(0x29..0x29, 2); set(0x2a..0x2c, 3); set(0x2d..0x3d, 2)
        set(0x44..0x6d, 2); set(0x6e..0x72, 3); set(0x74..0x78, 3)
        set(0x90..0xaf, 2); set(0xd0..0xe2, 2)
        set(0xfa..0xfb, 4); set(0xfc..0xfd, 3); set(0xfe..0xff, 2)
    }
}
