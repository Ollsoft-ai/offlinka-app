package cz.offlinka.app.reader

import android.content.Context
import android.text.TextUtils
import cz.offlinka.ui.R

/** The calm page the reader shows when an article is not on the drive. */
object NotFoundPage {
    fun html(context: Context, dark: Boolean): String {
        val title = TextUtils.htmlEncode(context.getString(R.string.reader_not_found_title))
        val body = TextUtils.htmlEncode(context.getString(R.string.reader_not_found_body))
        return page(title, body, dark)
    }

    fun page(title: String, body: String, dark: Boolean): String {
        val bg = if (dark) "#0F1A14" else "#F5F0E3"
        val ink = if (dark) "#EEE8D8" else "#17231C"
        val ink2 = if (dark) "#BDB6A2" else "#5F5B4B"
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>$title</title>
<style>html,body{margin:0;background:$bg;color:$ink;font:17px/1.5 Archivo,system-ui,sans-serif}
main{max-width:520px;margin:18vh auto 0;padding:0 24px;text-align:center}
.peak{width:28px;height:24px;margin:0 auto 18px;background:#F26A1B;clip-path:polygon(50% 0,100% 100%,0 100%)}
h1{font-size:22px;margin:0 0 8px}p{color:$ink2;margin:0}</style></head>
<body><main><div class="peak"></div><h1>$title</h1><p>$body</p></main></body></html>"""
    }
}
