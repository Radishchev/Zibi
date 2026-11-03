package com.zibi.app.share

internal fun extractIncomingSharedText(action: String?, mimeType: String?, text: CharSequence?): String? {
    if (action !in setOf("android.intent.action.SEND", "android.intent.action.PROCESS_TEXT") || mimeType != "text/plain") return null
    return text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
