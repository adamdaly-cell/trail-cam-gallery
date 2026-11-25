package app.trailcam.gallery

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared UI state: sync status and a counter the screens watch to reload the gallery. */
object SyncState {
    data class Status(val running: Boolean = false, val text: String = "", val error: String? = null)

    val status = MutableStateFlow(Status())

    /** Bumped whenever the local gallery files change. */
    val galleryVersion = MutableStateFlow(0)

    fun changed() {
        galleryVersion.value = galleryVersion.value + 1
    }
}
