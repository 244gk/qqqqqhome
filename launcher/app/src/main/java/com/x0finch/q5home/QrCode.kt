package com.x0finch.q5home

import android.graphics.Bitmap
import android.graphics.Color

/**
 * QR code generation (no third-party library). Byte mode only, versions 1–40, error correction levels L / M.
 * Algorithm per ISO/IEC 18004; structure modeled on Project Nayuki's QR Code generator (MIT).
 */
class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {

    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    /** Black-and-white bitmap with a white border of [border] modules on each side; each module is [scale] pixels. */
    fun toBitmap(scale: Int, border: Int = 4, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
        val n = (size + border * 2) * scale
        val pixels = IntArray(n * n) { light }
        for (y in 0 until size) for (x in 0 until size) {
            if (!modules[y][x]) continue
            for (dy in 0 until scale) {
                val row = (y + border) * scale + dy
                pixels.fill(dark, row * n + (x + border) * scale, row * n + (x + border + 1) * scale)
            }
        }
        return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
    }

    enum class Ecc(val row: Int, val formatBits: Int) { L(0, 1), M(1, 0) }

    companion object {
        /** Picks the smallest version that fits; returns null if it doesn't fit. [mask] is for tests only, -1 means auto. */
        fun encode(text: String, ecc: Ecc = Ecc.M, mask: Int = -1, minVersion: Int = 1): QrCode? =
            encode(text.toByteArray(Charsets.UTF_8), ecc, mask, minVersion)

        fun encode(data: ByteArray, ecc: Ecc, mask: Int = -1, minVersion: Int = 1): QrCode? {
            var version = minVersion
            while (true) {
                val capacityBits = dataCodewords(version, ecc) * 8
                val needed = 4 + (if (version < 10) 8 else 16) + data.size * 8
                if (needed <= capacityBits) break
                if (++version > 40) return null
            }
            // Mode 0100 (byte) + length + data + terminator + padding.
            val bits = BitBuffer()
            bits.append(0x4, 4)
            bits.append(data.size, if (version < 10) 8 else 16)
            data.forEach { bits.append(it.toInt() and 0xFF, 8) }
            val capacity = dataCodewords(version, ecc) * 8
            bits.append(0, minOf(4, capacity - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { bits.append(pad, 8); pad = pad xor 0xEC xor 0x11 }

            val codewords = ByteArray(bits.size / 8)
            for (i in 0 until bits.size) if (bits[i]) codewords[i ushr 3] = (codewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            val all = addEccAndInterleave(codewords, version, ecc)
            return Builder(version, ecc).build(all, mask)
        }

        // EC codewords per block and block count: [EC level][version] (only L and M kept).
        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        )
        private val BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        )

        /** Number of modules available for data after removing function patterns. */
        internal fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        internal fun dataCodewords(version: Int, ecc: Ecc): Int =
            rawModules(version) / 8 - ECC_PER_BLOCK[ecc.row][version] * BLOCKS[ecc.row][version]

        private fun addEccAndInterleave(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
            val numBlocks = BLOCKS[ecc.row][version]
            val eccLen = ECC_PER_BLOCK[ecc.row][version]
            val raw = rawModules(version) / 8
            val numShort = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks
            val divisor = rsDivisor(eccLen)
            val blocks = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until numBlocks) {
                val len = shortLen - eccLen + (if (i < numShort) 0 else 1)
                val dat = data.copyOfRange(k, k + len)
                k += len
                val block = ByteArray(shortLen + 1)
                dat.copyInto(block)
                rsRemainder(dat, divisor).copyInto(block, block.size - eccLen)
                blocks += block
            }
            val result = ByteArray(raw)
            var idx = 0
            for (i in 0 until shortLen + 1) for (j in blocks.indices) {
                // Short blocks have one fewer data codeword; skip that position.
                if (i != shortLen - eccLen || j >= numShort) result[idx++] = blocks[j][i]
            }
            return result
        }

        private fun rsDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = gfMul(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < degree) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = (result[i].toInt() xor gfMul(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return result
        }

        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        operator fun get(i: Int) = bits[i]
        fun append(value: Int, len: Int) { for (i in len - 1 downTo 0) bits += (value ushr i) and 1 != 0 }
    }

    /** Draws function patterns, places data, picks the mask. */
    private class Builder(val version: Int, val ecc: Ecc) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }

        fun build(data: ByteArray, forcedMask: Int): QrCode {
            drawFunctionPatterns()
            drawCodewords(data)
            var mask = forcedMask
            if (mask < 0) {
                var best = Int.MAX_VALUE
                for (m in 0 until 8) {
                    applyMask(m); drawFormat(m)
                    val penalty = penalty()
                    if (penalty < best) { best = penalty; mask = m }
                    applyMask(m) // XOR twice to restore
                }
            }
            applyMask(mask)
            drawFormat(mask)
            return QrCode(size, modules)
        }

        private fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; function[y][x] = true }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val align = alignmentPositions()
            val n = align.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            drawFormat(0) // placeholder for now
            drawVersion()
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val d = maxOf(Math.abs(dx), Math.abs(dy))
                    set(x, y, d != 2 && d != 4)
                }
            }
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val num = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2
            val result = IntArray(num)
            result[0] = 6
            var pos = size - 7
            for (i in num - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        private fun drawFormat(mask: Int) {
            val data = ecc.formatBits shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = (data shl 10 or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = version shl 12 or rem
            for (i in 0 until 18) {
                val b = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3; val c = i / 3
                set(a, c, b); set(c, a, b)
            }
        }

        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!function[y][x] && i < data.size * 8) {
                        modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (function[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) modules[y][x] = !modules[y][x]
            }
        }

        private fun penalty(): Int {
            var result = 0
            // Rules 1 and 3: runs of same-color modules and finder-like patterns in rows and columns.
            for (pass in 0..1) for (a in 0 until size) {
                var runColor = false; var run = 0
                val history = IntArray(7)
                for (b in 0 until size) {
                    val dark = if (pass == 0) modules[a][b] else modules[b][a]
                    if (dark == runColor) {
                        run++
                        if (run == 5) result += 3 else if (run > 5) result++
                    } else {
                        finderHistoryAdd(run, history)
                        if (!runColor) result += finderCount(history) * 40
                        runColor = dark; run = 1
                    }
                }
                result += finderTerminate(runColor, run, history) * 40
            }
            // Rule 2: 2×2 blocks of the same color.
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            // Rule 4: deviation of the dark-module ratio from 50%.
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * 10
            return result
        }

        private fun finderHistoryAdd(run: Int, history: IntArray) {
            var r = run
            if (history[0] == 0) r += size // treat outside the edge as white
            System.arraycopy(history, 0, history, 1, history.size - 1)
            history[0] = r
        }

        private fun finderCount(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }

        private fun finderTerminate(dark: Boolean, run: Int, history: IntArray): Int {
            var r = run
            if (dark) { finderHistoryAdd(r, history); r = 0 }
            r += size
            finderHistoryAdd(r, history)
            return finderCount(history)
        }
    }
}
