package cz.offlinka.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import cz.offlinka.app.content.ContentSource
import cz.offlinka.app.content.CoreContentSource
import cz.offlinka.app.data.AppDatabase
import cz.offlinka.app.data.Settings
import cz.offlinka.app.drive.DriveAccess
import cz.offlinka.app.drive.SafDriveAccess

open class OfflinkaApp : Application() {
    lateinit var graph: AppGraph
        private set

    /** Tests override these with fakes. */
    protected open fun createSource(): ContentSource = CoreContentSource()

    protected open fun createDrives(): DriveAccess = SafDriveAccess(this)

    protected open fun createDatabase(): AppDatabase = AppDatabase.create(this)

    protected open val watchStorageBroadcasts: Boolean = true

    override fun onCreate() {
        super.onCreate()
        val settings = Settings(this)
        settings.applyTheme()
        graph = AppGraph(
            context = this,
            source = createSource(),
            drives = createDrives(),
            db = createDatabase(),
            settings = settings,
        )
        graph.shelf.start()
        if (watchStorageBroadcasts) watchStorage()
    }

    /** USB drive mounted / unplugged: let the shelf re-check at once instead of on the next failed read. */
    private fun watchStorage() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                graph.shelf.onStorageChanged()
            }
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }
}

val Context.graph: AppGraph get() = (applicationContext as OfflinkaApp).graph
