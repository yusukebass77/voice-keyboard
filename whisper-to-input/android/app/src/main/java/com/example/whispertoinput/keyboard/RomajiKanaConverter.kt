/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Phase 6 #2 (2026-05-07): minimal rōmaji → ひらがな converter.
 *
 * Scope:
 *   - basic gojūon (a-i-u-e-o, ka-ki-ku-ke-ko, …)
 *   - voiced/dakuon series (ga, za, da, ba, pa, …)
 *   - common digraphs (kya, sha, cha, …)
 *   - sokuon: doubled consonant before kana (kk → っk → っか etc.)
 *   - hatsuon: nn → ん, n followed by non-vowel/non-y consonant → ん
 *
 * Out of scope (Phase 6 #2 minimum line):
 *   - kana → kanji conversion (commits hiragana directly)
 *   - punctuation conversion (English period/comma kept as-is)
 *
 * Strategy: process the romaji buffer left-to-right, longest-match-first.
 * Emit committed hiragana plus a remaining buffer of unconsumed romaji
 * letters (1-3 chars). The IME composes the remaining buffer as the
 * composing text, so the user sees their in-progress romaji until it
 * resolves into kana.
 */

package com.example.whispertoinput.keyboard

object RomajiKanaConverter {

    // Longest-match-first table. Order matters when keys overlap (e.g.
    // "shi" must be tried before "sh"). We use a sorted list of
    // (romaji, kana) pairs and iterate from longest to shortest.
    private val table: List<Pair<String, String>> = listOf(
        // 4-char (rare but include for extra small-tsu / xtu prefixes)
        // — handled programmatically via sokuon detection, not here.

        // 3-char digraphs first (longest match)
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ", "shi" to "し", "she" to "しぇ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ", "syi" to "しぃ", "sye" to "しぇ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ", "chi" to "ち", "che" to "ちぇ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ", "tyi" to "ちぃ", "tye" to "ちぇ",
        "tsa" to "つぁ", "tsi" to "つぃ", "tse" to "つぇ", "tso" to "つぉ", "tsu" to "つ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "ja"  to "じゃ", "ju"  to "じゅ", "jo"  to "じょ", "ji" to "じ", "je" to "じぇ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ", "jye" to "じぇ",
        "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ", "zye" to "じぇ",
        "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "fya" to "ふゃ", "fyu" to "ふゅ", "fyo" to "ふょ",
        "vya" to "ゔゃ", "vyu" to "ゔゅ", "vyo" to "ゔょ",
        "fa"  to "ふぁ", "fi"  to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
        "tha" to "てゃ", "thi" to "てぃ", "thu" to "てゅ", "the" to "てぇ", "tho" to "てょ",
        "dha" to "でゃ", "dhi" to "でぃ", "dhu" to "でゅ", "dhe" to "でぇ", "dho" to "でょ",
        "wha" to "うぁ", "whi" to "うぃ", "whe" to "うぇ", "who" to "うぉ",
        "xtu" to "っ", "xtsu" to "っ",
        "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
        "xwa" to "ゎ",
        "xka" to "ゕ", "xke" to "ゖ",
        "xtsu" to "っ",
        // 2-char monographs
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "sa" to "さ", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "ta" to "た", "ti" to "ち", "te" to "て", "to" to "と", "tu" to "つ",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "fu" to "ふ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "ye" to "いぇ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wo" to "を", "wi" to "うぃ", "we" to "うぇ",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "za" to "ざ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
        "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
        "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
        "nn" to "ん",
        // 1-char vowels
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "-" to "ー",
    ).sortedByDescending { it.first.length }

    // Set of consonants used to detect sokuon doubling and lone-n→ん.
    private val consonants = "kgsztdcnhfbpmyrwjvxl".toSet()
    private val vowels = "aiueo".toSet()

    /**
     * Conversion result: [committed] is the kana to display/commit so far,
     * [remaining] is the unconverted romaji buffer that should be shown as
     * composing text.
     */
    data class Result(val committed: String, val remaining: String)

    /**
     * Convert a buffer of romaji to kana. Returns the kana that has been
     * unambiguously consumed plus the romaji tail that still needs more
     * letters to disambiguate.
     *
     * Examples:
     *   "ka"     -> ("か", "")
     *   "k"      -> ("",   "k")
     *   "kk"     -> ("っ", "k")     // sokuon: double k commits っ
     *   "konnit" -> ("こんに", "t") // 'nn' resolves to ん
     *   "n"      -> ("",   "n")     // wait — could be na/ni/nu/ne/no/ny*
     *   "nk"     -> ("ん", "k")     // n + non-vowel non-y → ん
     */
    fun convert(input: String): Result {
        if (input.isEmpty()) return Result("", "")
        val sb = StringBuilder()
        var i = 0
        val s = input.lowercase()
        while (i < s.length) {
            val rest = s.length - i
            // Try longest-match against the table.
            var matched: Pair<String, String>? = null
            for ((rom, kana) in table) {
                if (rom.length <= rest && s.regionMatches(i, rom, 0, rom.length)) {
                    matched = rom to kana
                    break
                }
            }
            if (matched != null) {
                sb.append(matched.second)
                i += matched.first.length
                continue
            }
            // Sokuon: same consonant doubled (except n).
            val ch = s[i]
            if (ch in consonants && ch != 'n' && i + 1 < s.length && s[i + 1] == ch) {
                sb.append('っ')
                i++
                continue
            }
            // Lone n + non-vowel/non-y consonant → ん.
            if (ch == 'n' && i + 1 < s.length) {
                val next = s[i + 1]
                if (next in consonants && next != 'y' && next != 'n') {
                    sb.append('ん')
                    i++
                    continue
                }
            }
            // Otherwise, this character can't be resolved yet — keep
            // everything from i onward as the remaining buffer.
            return Result(sb.toString(), s.substring(i))
        }
        return Result(sb.toString(), "")
    }

    /**
     * Force-flush any trailing romaji that can be reasonably interpreted.
     * Used when the user hits space/enter and we want to commit whatever
     * is in the buffer rather than keep it pending.
     *
     *   "n"  -> "ん"
     *   "ka" -> "か"
     *   ""   -> ""
     *   "ky" -> "ky"  (no kana mapping — return as-is so it isn't lost)
     */
    fun flush(remaining: String): String {
        if (remaining.isEmpty()) return ""
        // Special case: a lone trailing 'n' resolves to ん at flush time.
        if (remaining == "n") return "ん"
        // Try table conversion of the whole tail.
        val (committed, leftover) = convert(remaining)
        return if (leftover.isEmpty()) committed else committed + leftover
    }
}
