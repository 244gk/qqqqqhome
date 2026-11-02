package com.x0finch.q5home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * QR codes: pins the SHA-256 of each matrix to catch future regressions. The matrices behind these values were verified by decoding with OpenCV's QR detector
 * (matrices are written to build/screenshots/qr/, first line is the source text). Covers the length field for versions 1–9 / 10+, version info (7+), and multi-block interleaving.
 */
class QrCodeTest {

    private fun matrix(qr: QrCode) = (0 until qr.size).joinToString("\n") { y ->
        (0 until qr.size).joinToString("") { x -> if (qr[x, y]) "1" else "0" }
    }

    private fun check(name: String, text: String, ecc: QrCode.Ecc, size: Int, sha: String) {
        val qr = QrCode.encode(text, ecc)!!
        val m = matrix(qr)
        System.getProperty("screenshots.dir")?.let { File(it, "qr").apply { mkdirs() } }
            ?.let { File(it, "$name.txt").writeText(text + "\n" + m) }
        assertEquals(size, qr.size)
        assertEquals(sha, MessageDigest.getInstance("SHA-256").digest(m.toByteArray()).joinToString("") { "%02x".format(it) })
    }

    @Test fun shortUrl() = check("url", "http://172.20.10.2:8765/abcd1234/", QrCode.Ecc.M, 29,
        "de70ed22c19c6659bc22bf74c6cff23380117392f0bd198c3447f9da7f796de0")

    @Test fun chineseText() = check("chinese", "Q5 Home 车机摸底 ".repeat(12), QrCode.Ecc.M, 65,
        "7c5105197c98f6f1e515e8b7acda810e310bd72686d82cf574fc850d399b401d")

    @Test fun longL() = check("long-l", "x".repeat(1000), QrCode.Ecc.L, 105,
        "2371e730e69d05b438edef5488202ed6011123766f3098e0a920ef48b0b0d82c")

    @Test fun nearlyFull() = check("full-m", "A".repeat(2000), QrCode.Ecc.M, 169,
        "3f774db555b985026ab1d99b0938e8aa7f395b6fa26e129cd6ba114247d82650")

    @Test fun tooLong() = assertNull(QrCode.encode("A".repeat(3000), QrCode.Ecc.M))
}
