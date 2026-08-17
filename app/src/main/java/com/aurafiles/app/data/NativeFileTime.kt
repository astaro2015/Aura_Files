package com.aurafiles.app.data

internal object NativeFileTime {
    init {
        System.loadLibrary("aura_file_time")
    }

    fun setModified(descriptor: Int, timestampMillis: Long): Int {
        return setModifiedNative(descriptor, timestampMillis)
    }

    private external fun setModifiedNative(descriptor: Int, timestampMillis: Long): Int
}

