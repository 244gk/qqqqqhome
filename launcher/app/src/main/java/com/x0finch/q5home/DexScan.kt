package com.x0finch.q5home

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Minimal read-only dex disassembler. Never runs any code or changes anything; only parses the APK's classes*.dex files.
 *  - [scanApk]: methods that use certain strings, call / access certain methods or fields, or are themselves named that way;
 *    lists, in order, the strings, integer constants, called methods and read/written fields in those methods (what the
 *    vendor launcher does when "原车媒体" is tapped).
 *  - [sitesApk]: every method that calls certain methods (sendBroadcast, startActivity, ...), with the strings it uses
 *    (broadcast actions, extra names, package / class names).
 *  - [constantsApk]: constant tables: the static final fields of matching classes and their values (command tables,
 *    AIDL transaction codes).
 *
 * Only knows instruction lengths and a few instructions (const, const-string, invoke, iget/iput/sget/sput), enough to see the call order.
 */
object DexScan {

    class Method(val name: String, val events: List<String>)

    /** A method calling one of the wanted methods: which wanted calls it makes, and the strings it uses. */
    class Site(val name: String, val calls: List<String>, val strings: List<String>)

    /** A class's static fields; value is null when it is set in code (e.g. enum entries) rather than as a constant. */
    class Constants(val className: String, val fields: List<Pair<String, String?>>)

    /**
     * Finds methods in all of the APK's dex files that reference strings matched by [target], or (with [refs]) call or access
     * methods / fields whose "Class.name" matches, or are themselves named that way. Static initializers are skipped: they
     * only fill tables (see [constantsApk]) and would crowd out the real code.
     */
    fun scanApk(apk: File, target: (String) -> Boolean, maxMethods: Int = 15, maxEvents: Int = 60,
                refs: (String) -> Boolean = { false }, owner: (String) -> Boolean = { true }): List<Method> {
        val out = mutableListOf<Method>()
        eachDex(apk) { bytes -> if (out.size < maxMethods) out += scanDex(bytes, target, maxMethods - out.size, maxEvents, refs, owner) }
        return out
    }

    /** A broken / truncated dex doesn't crash: returns what was found so far. */
    /** [owner] gets each method's "Class.method" name: only methods it accepts are scanned (e.g. vendor code, not bundled libraries). */
    fun scanDex(dex: ByteArray, target: (String) -> Boolean, maxMethods: Int = 15, maxEvents: Int = 60,
                refs: (String) -> Boolean = { false }, owner: (String) -> Boolean = { true }): List<Method> {
        val found = mutableListOf<Method>()
        runCatching { scan(dex, target, refs, owner, maxMethods, maxEvents, found) }
        return found
    }

    fun sitesApk(apk: File, call: (String) -> Boolean, max: Int = 30, owner: (String) -> Boolean = { true },
                 maxStrings: Int = 15): List<Site> {
        val out = mutableListOf<Site>()
        eachDex(apk) { bytes -> if (out.size < max) out += sitesDex(bytes, call, max - out.size, owner, maxStrings) }
        return out
    }

    /** Methods that call a method whose "Class.name" matches [call]; with up to 15 distinct strings each. */
    fun sitesDex(dex: ByteArray, call: (String) -> Boolean, max: Int = 30, owner: (String) -> Boolean = { true },
                 maxStrings: Int = 15): List<Site> {
        val found = mutableListOf<Site>()
        runCatching {
            val d = Dex(dex)
            if (!d.valid) return@runCatching
            val wanted = (0 until d.methodCount).filter { call(d.methodName(it)) }.toHashSet()
            if (wanted.isEmpty()) return@runCatching
            d.eachMethod { methodIdx, insns ->
                val calls = insns.filter { it.op in INVOKE && it.index in wanted }
                if (calls.isEmpty() || !owner(d.methodName(methodIdx))) return@eachMethod true
                val strings = insns.filter { it.op in CONST_STRING }.map { d.string(it.index).take(100) }.distinct().take(maxStrings)
                found += Site(d.methodName(methodIdx), calls.map { d.methodName(it.index) }.distinct(), strings)
                found.size < max
            }
        }
        return found
    }

    fun constantsApk(apk: File, classMatch: (String) -> Boolean, maxClasses: Int = 40, maxFields: Int = 80): List<Constants> {
        val out = mutableListOf<Constants>()
        eachDex(apk) { bytes -> if (out.size < maxClasses) out += constantsDex(bytes, classMatch, maxClasses - out.size, maxFields) }
        return out
    }

    /** Static fields (with their constant values) of classes whose full name matches [classMatch]. */
    fun constantsDex(dex: ByteArray, classMatch: (String) -> Boolean, maxClasses: Int = 40, maxFields: Int = 80): List<Constants> {
        val found = mutableListOf<Constants>()
        runCatching {
            val d = Dex(dex)
            if (!d.valid) return@runCatching
            for (c in 0 until d.classCount) {
                val def = d.classDefsOff + c * 32
                val name = d.className(d.u4(def))
                if (!classMatch(name)) continue
                val classDataOff = d.u4(def + 24)
                if (classDataOff == 0) continue
                val fields = d.staticFieldsOf(classDataOff)
                if (fields.isEmpty()) continue
                val values = d.u4(def + 28).let { if (it == 0) emptyList() else d.encodedArray(it) }
                found += Constants(name, fields.take(maxFields).mapIndexed { i, f -> d.fieldSimpleName(f) to values.getOrNull(i) })
                if (found.size >= maxClasses) return@runCatching
            }
        }
        return found
    }

    private fun eachDex(apk: File, block: (ByteArray) -> Unit) {
        runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { Regex("classes\\d*\\.dex").matches(it.name) && it.size in 1..(64L shl 20) }
                    .forEach { e -> block(zip.getInputStream(e).use { it.readBytes() }) }
            }
        }
    }

    private fun scan(dex: ByteArray, target: (String) -> Boolean, refs: (String) -> Boolean, owner: (String) -> Boolean,
                     maxMethods: Int, maxEvents: Int, found: MutableList<Method>) {
        val d = Dex(dex)
        if (!d.valid) return
        val wanted = (0 until d.stringCount).filter { target(d.string(it)) }.toHashSet()
        val wantedMethods = (0 until d.methodCount).filter { refs(d.methodName(it)) }.toHashSet()
        val wantedFields = (0 until d.fieldCount).filter { refs(d.fieldName(it)) }.toHashSet()
        if (wanted.isEmpty() && wantedMethods.isEmpty() && wantedFields.isEmpty()) return
        d.eachMethod { methodIdx, insns ->
            if (d.methodSimpleName(methodIdx) == "<clinit>" || !owner(d.methodName(methodIdx))) return@eachMethod true
            val hit = methodIdx in wantedMethods || insns.any {
                (it.op in CONST_STRING && it.index in wanted) || (it.op in INVOKE && it.index in wantedMethods) ||
                    (it.op in FIELD && it.index in wantedFields)
            }
            if (hit) found += Method(d.methodName(methodIdx), insns.mapNotNull { d.describe(it) }.take(maxEvents))
            found.size < maxMethods
        }
    }

    private val CONST_STRING = setOf(0x1a, 0x1b)
    private val INVOKE = (0x6e..0x72).toSet() + (0x74..0x78).toSet()
    private val FIELD = (0x52..0x6d).toSet()

    /** One instruction: opcode + referenced index (string / method / field) or literal. */
    class Insn(val op: Int, val index: Int, val literal: Long?)

    private class Dex(val b: ByteArray) {
        val bb: ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val valid = b.size > 0x70 && String(b, 0, 3, Charsets.ISO_8859_1) == "dex"
        val stringCount = if (valid) u4(0x38) else 0
        val stringIdsOff = if (valid) u4(0x3C) else 0
        val typeIdsOff = if (valid) u4(0x44) else 0
        val fieldCount = if (valid) u4(0x50) else 0
        val fieldIdsOff = if (valid) u4(0x54) else 0
        val methodCount = if (valid) u4(0x58) else 0
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

        fun methodSimpleName(i: Int) = string(u4(methodIdsOff + i * 8 + 4))

        fun fieldName(i: Int): String {
            val off = fieldIdsOff + i * 8
            return "${shortClass(type(u2(off)))}.${string(u4(off + 4))}"
        }

        fun fieldSimpleName(i: Int) = string(u4(fieldIdsOff + i * 8 + 4))

        /** Full dotted class name of a type index. */
        fun className(typeIdx: Int) = type(typeIdx).removePrefix("L").removeSuffix(";").replace('/', '.')

        private var p = 0
        private fun uleb(): Int {
            var result = 0; var shift = 0
            while (true) {
                val x = b[p++].toInt() and 0xFF
                result = result or ((x and 0x7F) shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
        }

        /** All methods in class_data: (method_idx, code_off). */
        fun methodsOf(off: Int): List<Pair<Int, Int>> {
            p = off
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

        /** Field indices of the static fields in class_data, in declaration order (the order of static_values). */
        fun staticFieldsOf(off: Int): List<Int> {
            p = off
            val staticFields = uleb(); uleb(); uleb(); uleb()
            var idx = 0
            return List(staticFields) { idx += uleb(); uleb(); idx }
        }

        /** Calls [block] with every method that has code; stops when it returns false. */
        fun eachMethod(block: (methodIdx: Int, insns: List<Insn>) -> Boolean) {
            for (c in 0 until classCount) {
                val classDataOff = u4(classDefsOff + c * 32 + 24)
                if (classDataOff == 0) continue
                for ((methodIdx, codeOff) in methodsOf(classDataOff)) {
                    if (codeOff == 0) continue
                    val insns = instructions(codeOff) ?: continue
                    if (!block(methodIdx, insns)) return
                }
            }
        }

        /** encoded_array (static_values): each value as text; nested arrays / annotations are summarized. */
        fun encodedArray(off: Int): List<String?> {
            p = off
            val size = uleb()
            return List(size.coerceAtMost(4096)) { value() }
        }

        private fun value(): String? {
            val h = b[p++].toInt() and 0xFF
            val kind = h and 0x1F
            val arg = h shr 5
            fun bytes(n: Int, signed: Boolean): Long {
                var v = 0L
                for (i in 0 until n) v = v or ((b[p + i].toLong() and 0xFF) shl (8 * i))
                p += n
                if (signed && n < 8) { val shift = 64 - 8 * n; v = (v shl shift) shr shift }
                return v
            }
            return when (kind) {
                0x00, 0x02, 0x04, 0x06 -> bytes(arg + 1, true).let { v ->
                    // Command codes are often written in hex: show both when it helps.
                    val hex = if (kind == 0x06) java.lang.Long.toHexString(v) else Integer.toHexString(v.toInt())
                    (if (kind == 0x06) "${v}L" else v.toString()) + if (v >= 16 || v < -16) " (0x${hex.uppercase()})" else ""
                }
                0x03 -> bytes(arg + 1, false).toString()
                0x10 -> java.lang.Float.intBitsToFloat((bytes(arg + 1, false) shl (8 * (3 - arg))).toInt()).toString() + "f"
                0x11 -> java.lang.Double.longBitsToDouble(bytes(arg + 1, false) shl (8 * (7 - arg))).toString()
                0x17 -> "\"" + string(bytes(arg + 1, false).toInt()).take(100) + "\""
                0x18 -> shortClass(type(bytes(arg + 1, false).toInt()))
                0x19, 0x1b -> fieldName(bytes(arg + 1, false).toInt())
                0x1a -> methodName(bytes(arg + 1, false).toInt())
                0x15, 0x16 -> { bytes(arg + 1, false); "?" }
                0x1c -> { val n = uleb(); repeat(n) { value() }; "[$n 项]" }
                0x1d -> { uleb(); val n = uleb(); repeat(n) { uleb(); value() }; "@注解" }
                0x1e -> "null"
                0x1f -> (arg != 0).toString()
                else -> throw IllegalStateException("bad encoded value $kind")
            }
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
        fun shortClass(desc: String): String {
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
