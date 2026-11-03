package com.x0finch.q5home

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Reads the code's string constants out of an APK (the string tables of classes*.dex), read-only.
 * Used to find the broadcast / command names vendor apps use to switch to the OEM screen: these are usually
 * plain-text constants, visible even without adb.
 */
object ApkStrings {

    /** Words possibly related to OEM-screen switching (kept if they appear in a class name or constant). */
    private val KEYWORDS = Regex(
        // Only head-unit-specific terms; generic words like reverse / origin / source are everywhere in library code and would flood the output.
        "oem|yuanche|原车|原厂|original|orig_|avin|aux_?in|cvbs|lvds|canbus|carinfo|video_?source|source_?switch|" +
            "screen_?switch|mediabox|media_?box|backcar|reverse_?car|org_?car|ori_?car|^content://",
        RegexOption.IGNORE_CASE,
    )
    /** Strings that look like broadcast / Intent actions: dotted names with at least three parts, last part all caps (e.g. com.wwc2.main.ACTION_X). */
    private val ACTION_LIKE = Regex("^[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+){2,}$")

    /** Third-party library / compiler-generated strings, unrelated to vendor logic; excluded first (otherwise things like kotlin's reversed flood the output). */
    private val LIBRARY = Regex(
        "^L?(kotlin|kotlinx|java|javax|androidx|android[./]support|com[./]google|okhttp3|okio|retrofit2|io[./]reactivex|org)[./]",
    )

    /**
     * Built-in Android constants (media metadata, speech, extras, etc.) and generated classes (resources R$, data binding):
     * every app has loads of them and they are unrelated to vendor logic. android.intent.action.* is kept: vendors define custom actions there.
     */
    private val NOISE = Regex(
        "^(android\\.(media|speech|service|view|intent\\.extra|intent\\.category|hardware|car|wearable|net|provider)\\.|aios\\.|\\[L)" +
            "|/R\\$|/R;|/databinding/|/holdr/|/BuildConfig;",
    )

    /** Matches a head-unit keyword (sorted to the top of the report). */
    fun isKeyword(s: String) = KEYWORDS.containsMatchIn(s)

    fun interesting(s: String): Boolean {
        if (s.length !in 2..120 || s.any { it == ' ' || it == '\n' }) return false
        if (s.startsWith("\$this\$") || LIBRARY.containsMatchIn(s) || NOISE.containsMatchIn(s)) return false
        if (KEYWORDS.containsMatchIn(s)) return true
        if (!ACTION_LIKE.matches(s)) return false
        val last = s.substringAfterLast('.')
        return last.length >= 3 && last == last.uppercase() && last.any { it.isLetter() } &&
            !s.startsWith("android.permission.") && !s.startsWith("java.") && !s.startsWith("javax.")
    }

    /** After excluding library code / built-in Android constants, filters with [pattern] (used for "related strings" in the report). */
    fun matching(pattern: Regex): (String) -> Boolean = { s ->
        s.length in 2..120 && s.none { it == ' ' || it == '\n' } && !s.startsWith("\$this\$") &&
            !LIBRARY.containsMatchIn(s) && !NOISE.containsMatchIn(s) && pattern.containsMatchIn(s)
    }

    /** Strings that look like Intent extra names (extras passed when opening a page, e.g. start_page, jump_type). */
    private val EXTRA_LIKE = Regex(
        "^[a-zA-Z][a-zA-Z0-9_]{2,40}$",
    )
    private val EXTRA_WORDS = Regex("page|jump|start_?type|open_?type|launch_?type|tab_?index|mode_?type|media_?type|enter_?mode", RegexOption.IGNORE_CASE)

    /** Broader filter for key apps: also includes field names that look like navigation extras. */
    fun interestingDeep(s: String): Boolean =
        interesting(s) || (EXTRA_LIKE.matches(s) && EXTRA_WORDS.containsMatchIn(s) && s.contains('_'))

    /** Strings from all dex files in the APK, filtered by [filter], deduplicated and sorted, at most [limit]. Empty if unreadable. */
    fun fromApk(apk: File, limit: Int = 200, filter: (String) -> Boolean = ::interesting): List<String> {
        val found = sortedSetOf<String>()
        runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { Regex("classes\\d*\\.dex").matches(it.name) && it.size in 1..(64L shl 20) }
                    .forEach { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        dexStrings(bytes).filterTo(found) { filter(it) }
                    }
            }
        }
        // Keyword hits go first so they don't get truncated.
        return found.sortedWith(compareBy({ !isKeyword(it) }, { it })).take(limit)
    }

    /** dex string table: header 0x38 = count, 0x3C = offset; each entry is a uleb128 length + MUTF-8 + 0. */
    fun dexStrings(dex: ByteArray): List<String> {
        if (dex.size < 0x70 || String(dex, 0, 3, Charsets.ISO_8859_1) != "dex") return emptyList()
        val bb = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.getInt(0x38)
        val idsOff = bb.getInt(0x3C)
        if (count <= 0 || idsOff <= 0 || idsOff.toLong() + count * 4L > dex.size) return emptyList()
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            var p = bb.getInt(idsOff + i * 4)
            if (p !in 0 until dex.size) continue
            // Skip the uleb128 UTF-16 length.
            while (p < dex.size && dex[p].toInt() and 0x80 != 0) p++
            p++
            out += decodeMutf8(dex, p) ?: continue
        }
        return out
    }

    /** Decodes MUTF-8 (0-terminated; surrogate pairs are decoded as two separate 3-byte sequences, which recombine into UTF-16). */
    private fun decodeMutf8(b: ByteArray, start: Int): String? {
        val sb = StringBuilder()
        var p = start
        while (p < b.size) {
            val c = b[p].toInt() and 0xFF
            when {
                c == 0 -> return sb.toString()
                c < 0x80 -> { sb.append(c.toChar()); p += 1 }
                c and 0xE0 == 0xC0 && p + 1 < b.size -> {
                    sb.append((((c and 0x1F) shl 6) or (b[p + 1].toInt() and 0x3F)).toChar()); p += 2
                }
                c and 0xF0 == 0xE0 && p + 2 < b.size -> {
                    sb.append((((c and 0x0F) shl 12) or ((b[p + 1].toInt() and 0x3F) shl 6) or
                        (b[p + 2].toInt() and 0x3F)).toChar()); p += 3
                }
                else -> return null
            }
            if (sb.length > 500) return null
        }
        return null
    }
}
