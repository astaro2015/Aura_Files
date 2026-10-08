package com.aurafiles.app

import java.io.File

/**
 * Pure path-selection logic shared by AuraFileProvider and its JVM regression harness.
 * It mirrors AndroidX FileProvider's "most specific configured root" behavior while
 * keeping URI generation independent from PackageManager meta-data lookup.
 */
object AuraFileProviderPathPolicy {
    data class Root(val name: String, val canonicalPath: String)
    data class Match(val rootName: String, val relativePath: String)

    fun resolve(fileCanonicalPath: String, roots: List<Root>): Match =
        resolveOrNull(fileCanonicalPath, roots)
            ?: throw IllegalArgumentException(
                "Файл находится вне разрешённых путей AuraFileProvider: $fileCanonicalPath",
            )

    fun resolveIncoming(rootName: String, relativePath: String, roots: List<Root>): String {
        require(rootName.isNotBlank()) { "AuraFileProvider root is empty" }
        require(relativePath.isNotBlank() && !relativePath.startsWith('/')) {
            "AuraFileProvider relative path is invalid"
        }
        val root = roots.firstOrNull { it.name == rootName }
            ?: throw IllegalArgumentException("Unknown AuraFileProvider root: $rootName")
        val candidate = File(root.canonicalPath, relativePath).canonicalFile.path
        val resolved = resolveOrNull(candidate, roots)
            ?: throw SecurityException("AuraFileProvider path escaped allowed roots")
        require(resolved.rootName == rootName) {
            "AuraFileProvider root mismatch for $candidate"
        }
        require(resolved.relativePath == relativePath) {
            "AuraFileProvider canonical path mismatch"
        }
        return candidate
    }

    fun resolveOrNull(fileCanonicalPath: String, roots: List<Root>): Match? {
        val filePath = normalize(fileCanonicalPath)
        val selected = roots
            .asSequence()
            .map { it.copy(canonicalPath = normalize(it.canonicalPath)) }
            .filter { root -> isStrictDescendant(filePath, root.canonicalPath) }
            .maxByOrNull { it.canonicalPath.length }
            ?: return null

        val relative = filePath.substring(selected.canonicalPath.length + 1)
        if (relative.isEmpty()) return null
        return Match(selected.name, relative)
    }

    private fun isStrictDescendant(filePath: String, rootPath: String): Boolean =
        filePath.startsWith("$rootPath/")

    private fun normalize(path: String): String =
        if (path.length > 1) path.trimEnd('/') else path
}
