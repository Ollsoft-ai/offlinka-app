package cz.offlinka.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Runs the instrumented tests on [FixtureApp]: the real app, with a folder standing in for the USB drive. */
class FixtureRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, FixtureApp::class.java.name, context)
}
