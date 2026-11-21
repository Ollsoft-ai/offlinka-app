package cz.offlinka.app

import cz.offlinka.app.drive.DriveAccess

/** The real app (real core, real WebView), only the drive is a folder in app storage ([FolderDrive]). */
class FixtureApp : OfflinkaApp() {
    val drive: FolderDrive by lazy { FolderDrive(this) }

    override fun createDrives(): DriveAccess = drive

    // No USB volume on an emulator: the mount broadcasts would never come; tests call onStorageChanged().
    override val watchStorageBroadcasts: Boolean = false
}
