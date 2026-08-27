package com.aurafiles.app.data

internal object ArchiveSafety {
    fun safeSegment(name: String): String =
        name.replace('/', '_').replace('\\', '_').ifBlank { "Без названия" }

    fun safePath(rawPath: String): List<String> {
        require(!rawPath.startsWith('/') && !rawPath.startsWith('\\')) { "Небезопасный путь в архиве" }
        require(!WINDOWS_DRIVE.matches(rawPath.substringBefore('/').substringBefore('\\'))) {
            "Небезопасный путь в архиве"
        }
        return rawPath.replace('\\', '/').split('/')
            .filter(String::isNotBlank)
            .onEach { segment ->
                require(segment != "." && segment != ".." && ':' !in segment) {
                    "Небезопасный путь в архиве"
                }
            }
    }

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:$")
}
