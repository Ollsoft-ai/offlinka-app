package cz.offlinka.app.reader

/**
 * Decides when the reader must reload its page because it was shown while its book was not served yet
 * (after the process was restored, while the drive was unplugged, during the first scan, or while that
 * book was still opening). Pure logic, driven by the WebView client and the shelf.
 */
class PageRecovery {
    /** The book whose page is waiting, or null. */
    private var waitingFor: String? = null

    /** A main-frame page of [bookId] started loading; [served] = that book was served at that moment. */
    fun onPageStarted(bookId: String?, served: Boolean) {
        waitingFor = if (bookId != null && !served) bookId else null
    }

    /** The main frame got an HTTP error: 503 = its book was not available (closed, unplugged, still opening). */
    fun onMainFrameStatus(bookId: String?, status: Int) {
        if (status == 503 && bookId != null) waitingFor = bookId
    }

    /** The served books changed: reload once if the waiting page's book is there now. */
    fun shouldReload(served: Set<String>): Boolean {
        val book = waitingFor ?: return false
        if (book !in served) return false
        waitingFor = null
        return true
    }
}

/** Text size of the reader: the user's setting times the system font scale (accessibility). */
object ReaderZoom {
    /** WebView text zoom (percent) for pages that are not MediaWiki. */
    fun textZoom(percent: Int, fontScale: Float): Int = (percent * fontScale.coerceIn(0.5f, 3f)).toInt().coerceIn(50, 400)

    /** Scale of the reader stylesheet (1 = 17 px body text) for MediaWiki pages, whose WebView zoom stays 100. */
    fun cssScale(percent: Int, fontScale: Float): Float = textZoom(percent, fontScale) / 100f
}

/** The reader's WebView history kept across activity recreation (lives in the app graph). */
class RetainedReaderState {
    @Volatile var bundle: android.os.Bundle? = null
}

/** A heading of the article for the contents sheet. */
data class Heading(val id: String, val text: String, val level: Int) {
    companion object {
        /** Parses the JSON string that evaluateJavascript returns (a JSON-encoded JSON array). */
        fun parse(evaluated: String?): List<Heading> = runCatching {
            if (evaluated == null || evaluated == "null") return emptyList()
            val inner = org.json.JSONTokener(evaluated).nextValue()
            val arr = if (inner is String) org.json.JSONArray(inner) else inner as org.json.JSONArray
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Heading(o.getString("id"), o.getString("text"), o.optInt("level", 2))
            }
        }.getOrDefault(emptyList())
    }
}

/** When a page may hand something to another app (dialer, mail, a viewer for a file). */
object LinkPolicy {
    /** A download started within [windowMillis] of the user's last touch counts as the user's. */
    fun userInitiated(lastTouch: Long, now: Long, windowMillis: Long = 3_000): Boolean =
        lastTouch > 0 && now - lastTouch in 0..windowMillis
}
