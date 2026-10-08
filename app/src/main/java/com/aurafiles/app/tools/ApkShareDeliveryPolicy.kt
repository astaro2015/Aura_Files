package com.aurafiles.app.tools

/** Stable routing rules for exporting APK files to other applications. */
object ApkShareDeliveryPolicy {
    const val SHARE_MIME = "application/octet-stream"
    const val MEDIASTORE_MIN_SDK = 29

    fun useMediaStore(sdkInt: Int): Boolean = sdkInt >= MEDIASTORE_MIN_SDK
}
