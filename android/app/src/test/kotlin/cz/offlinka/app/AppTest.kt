package cz.offlinka.app

import android.content.ComponentCallbacks2
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.fakes.FakeContentSource
import cz.offlinka.app.ui.TestApp
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

    @Test fun `the core gets a shared cache budget at start and is trimmed on memory pressure`() {
        val source = app.graph.source as FakeContentSource
        assertThat(source.budget).isAtLeast(16L * 1024 * 1024)
        assertThat(source.budget).isAtMost(64L * 1024 * 1024)
        val before = source.trims
        @Suppress("DEPRECATION")
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertThat(source.trims).isEqualTo(before + 2)
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
