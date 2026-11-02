package com.zibi.app.privacy

/**
 * Finds sensitive values: phone numbers, e-mail addresses, card and ID numbers, passwords and keys.
 * Port of backend/zibi/privacy.py. Both are tested with the same cases; change one, change both.
 *
 * Honest limit: this recognises common PATTERNS. It can't know that a free-text sentence is private, which is why
 * facts can also be hidden by hand.
 */
data class Span(val start: Int, val end: Int, val kind: String)

private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
private val CARD = Regex("\\b(?:\\d[ -]?){12,18}\\d\\b")
private val AADHAAR = Regex("(?<!\\d[ -])\\b\\d{4}[ -]\\d{4}[ -]\\d{4}\\b(?![ -]\\d)")
private val PAN = Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b")
private val SSN = Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")
private val KEYWORD = Regex(
    "(?i)\\b(?:password|passcode|pin|otp|cvv|secret|token|api[ _-]?key)\\b(\\s*(?:is|=|:)\\s*|\\s+)[\"']?([^\\s\"']{3,})"
)
private val LONG_TOKEN = Regex("\\b[A-Za-z0-9_\\-]{28,}\\b")
private val PHONE = Regex("(?<![\\w:./-])\\+?\\(?\\d[\\d ()\\-]{6,}\\d(?![\\w:/-])")
private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}-\\d{1,2}-\\d{2,4}")
private val NON_DIGIT = Regex("\\D")
private val CONNECTOR = Regex("(?i)[=:]|\\bis\\b")

fun luhn(digits: String): Boolean {
    var total = 0
    digits.reversed().forEachIndexed { i, ch ->
        var d = ch - '0'
        if (i % 2 == 1) {
            d *= 2
            if (d > 9) d -= 9
        }
        total += d
    }
    return total % 10 == 0
}

/** File names and slugs (Lab_Report_2026_10_30_Final_v2) are words and short numbers; real keys are random. */
fun looksLikeSlug(token: String): Boolean {
    val parts = token.split('_', '-').filter { it.isNotEmpty() }
    if (parts.size < 3) return false
    val plain = parts.count { p -> p.all { it.isLetter() } || (p.all { it.isDigit() } && p.length <= 4) }
    return plain.toDouble() / parts.size >= 0.6
}

fun isPhone(candidate: String): Boolean {
    val c = candidate.trim()
    val digits = c.replace(NON_DIGIT, "")
    if (digits.length !in 8..15) return false
    if (DATE.matches(c)) return false
    val singles = Regex("\\d+").findAll(c).count { it.value.length == 1 }
    if (singles > 1) return false // "3 5 7 9 11 13": a list of numbers, not a phone number
    return c.any { it in " ()+-" } || digits.length >= 10
}

fun findSensitive(text: String): List<Span> {
    val spans = mutableListOf<Span>()
    fun add(start: Int, end: Int, kind: String) {
        if (spans.all { end <= it.start || start >= it.end }) spans += Span(start, end, kind)
    }
    EMAIL.findAll(text).forEach { add(it.range.first, it.range.last + 1, "EMAIL") }
    CARD.findAll(text).forEach {
        val digits = it.value.replace(NON_DIGIT, "")
        if (digits.length in 13..19 && luhn(digits)) add(it.range.first, it.range.last + 1, "CARD")
    }
    listOf(AADHAAR, PAN, SSN).forEach { rx -> rx.findAll(text).forEach { add(it.range.first, it.range.last + 1, "ID") } }
    KEYWORD.findAll(text).forEach {
        val connector = it.groupValues[1]
        val value = it.groups[2]!!
        if (CONNECTOR.containsMatchIn(connector) || value.value.any(Char::isDigit)) {
            add(value.range.first, value.range.last + 1, "SECRET")
        }
    }
    LONG_TOKEN.findAll(text).forEach {
        val t = it.value
        if (t.any { c -> c in 'a'..'z' } && t.any { c -> c in 'A'..'Z' } && t.any(Char::isDigit) && !looksLikeSlug(t)) {
            add(it.range.first, it.range.last + 1, "SECRET")
        }
    }
    PHONE.findAll(text).forEach { if (isPhone(it.value)) add(it.range.first, it.range.last + 1, "PHONE") }
    return spans.sortedBy { it.start }
}
