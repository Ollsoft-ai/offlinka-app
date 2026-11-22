package cz.offlinka.app

import android.content.ComponentCallbacks2
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.fakes.FakeContentSource
import cz.offlinka.app.ui.TestApp
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class)
class AppTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as TestApp

    @Test fun `targets Android 16 (API 36) as Google Play requires`() {
        assertThat(app.applicationInfo.targetSdkVersion).isEqualTo(36)
    }

    @Test fun `the core gets a shared cache budget at start and is trimmed only when really backgrounded`() {
        val source = app.graph.source as FakeContentSource
        assertThat(source.budget).isEqualTo(48L * 1024 * 1024)
        val before = source.trims
        // An app switch is not memory pressure: nothing is unloaded (Search would reload 16–76 MB).
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        @Suppress("DEPRECATION")
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Thread.sleep(200)
        assertThat(source.trims).isEqualTo(before)
        // Backgrounded: trimmed, off the main thread.
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (source.trims > before) return@repeat
            Thread.sleep(20)
        }
        assertThat(source.trims).isEqualTo(before + 1)
        assertThat(source.trimThread).isNotEqualTo(Thread.currentThread().name)
    }

    @Test fun `trim levels`() {
        assertThat(OfflinkaApp.shouldTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)).isFalse()
        assertThat(OfflinkaApp.shouldTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)).isTrue()
        @Suppress("DEPRECATION")
        assertThat(OfflinkaApp.shouldTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)).isTrue()
    }

    @Test fun `the bundled guides are copied again after an app update, even with the same size`() {
        val dir = java.nio.file.Files.createTempDirectory("asset").toFile()
        val out = java.io.File(dir, "g.zim")
        BundledAsset.ensure(out, "2-100") { "old".byteInputStream() }
        BundledAsset.ensure(out, "2-100") { error("not copied twice for the same version") }
        assertThat(out.readText()).isEqualTo("old")
        BundledAsset.ensure(out, "3-200") { "new".byteInputStream() }
        assertThat(out.readText()).isEqualTo("new")
    }

    @Test fun `Ulozeno starts with the two emergency guides`() {
        val saved = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                app.graph.db.saved().all().first { it.size >= 2 }
            }
        }
        assertThat(saved.map { it.bookId to it.path }).containsExactly(
            "offlinka_cs_prirucky" to "prvni-pomoc.html",
            "offlinka_cs_prirucky" to "vypadek-proudu.html",
        )
    }

    @Test fun `the privacy policy link follows the UI language`() {
        fun url(tag: String): String {
            val c = android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
            return app.createConfigurationContext(c).getString(cz.offlinka.ui.R.string.about_privacy_url)
        }
        assertThat(url("cs")).isEqualTo("https://offlinka.cz/aplikace/soukromi")
        assertThat(url("sk")).isEqualTo("https://offlinka.sk/aplikacia/sukromie")
        assertThat(url("en")).isEqualTo("https://offlinka.cz/en/app/privacy")
    }

    @Test fun `nothing leaves the phone - backup is off`() {
        assertThat(app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
        val parser = app.resources.getXml(R.xml.data_extraction_rules)
        val excluded = mutableListOf<String>()
        var e = parser.eventType
        while (e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (e == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "include") error("nothing may be included")
            if (e == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude") excluded += parser.getAttributeValue(null, "domain")
            e = parser.next()
        }
        assertThat(excluded).containsAtLeast("root", "file", "database", "sharedpref")
    }

    @Test fun `the WebView sends nothing to Google - no metrics, no Safe Browsing lookups`() {
        val info = app.packageManager.getApplicationInfo(app.packageName, android.content.pm.PackageManager.GET_META_DATA)
        assertThat(info.metaData.getBoolean("android.webkit.WebView.MetricsOptOut")).isTrue()
        assertThat(info.metaData.getBoolean("android.webkit.WebView.EnableSafeBrowsing", true)).isFalse()
    }

    @Test fun `the full licence texts ship inside the app`() {
        val page = app.assets.open("reader/licences.html").use { it.readBytes().toString(Charsets.UTF_8) }
        assertThat(page).contains("libzstd")
        assertThat(page).contains("BSD")
        assertThat(page).contains("Mozilla Public License")
        assertThat(page).contains("lzma-rs")
        assertThat(page).contains("SIL OPEN FONT LICENSE")
    }

    @Test fun `the app icon offers the two guides as shortcuts`() {
        val parser = app.resources.getXml(R.xml.shortcuts)
        val paths = mutableListOf<String>()
        var e = parser.eventType
        while (e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (e == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "extra") {
                paths += parser.getAttributeValue("http://schemas.android.com/apk/res/android", "value")
            }
            e = parser.next()
        }
        assertThat(paths).containsExactly("prvni-pomoc.html", "vypadek-proudu.html").inOrder()
    }

    @Test fun `the guides book ships inside the app`() {
        repeat(250) {
            if (app.graph.shelf.builtin != null) return@repeat
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        val guides = app.graph.shelf.builtin
        assertThat(guides).isNotNull()
        assertThat(guides!!.id).isEqualTo("offlinka_cs_prirucky")
        assertThat(guides.builtin).isTrue()
    }
}
