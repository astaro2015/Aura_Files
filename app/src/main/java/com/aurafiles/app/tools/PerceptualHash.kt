package com.aurafiles.app.tools

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aurafiles.app.model.FileEntry
import java.io.File
import java.io.IOException
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlinx.coroutines.ensureActive

object PerceptualHash {
    data class Fingerprint(val dHash: Long, val pHash: Long)

    fun fingerprint(bitmap: Bitmap): Fingerprint = Fingerprint(dHash(bitmap), pHash(bitmap))

    fun dHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        try {
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val left = luma(scaled.getPixel(x, y))
                    val right = luma(scaled.getPixel(x + 1, y))
                    if (left > right) hash = hash or (1L shl bit)
                    bit += 1
                }
            }
            return hash
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun pHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        try {
            val pixels = Array(32) { y -> DoubleArray(32) { x -> luma(scaled.getPixel(x, y)).toDouble() } }
            // Separable 2-D DCT. The cosine table is precomputed once instead of
            // doing ~65k cos() calls for every photograph.
            val horizontal = Array(32) { DoubleArray(8) }
            for (y in 0 until 32) for (u in 0 until 8) {
                var sum = 0.0
                for (x in 0 until 32) sum += pixels[y][x] * DCT_COS[u][x]
                horizontal[y][u] = sum
            }
            val coeff = Array(8) { DoubleArray(8) }
            for (v in 0 until 8) for (u in 0 until 8) {
                var sum = 0.0
                for (y in 0 until 32) sum += horizontal[y][u] * DCT_COS[v][y]
                val au = if (u == 0) INV_SQRT_2 else 1.0
                val av = if (v == 0) INV_SQRT_2 else 1.0
                coeff[v][u] = 0.25 * au * av * sum
            }
            val values = buildList {
                for (v in 0 until 8) for (u in 0 until 8) if (!(u == 0 && v == 0)) add(coeff[v][u])
            }.sorted()
            val median = values[values.size / 2]
            var hash = 0L
            var bit = 0
            for (v in 0 until 8) for (u in 0 until 8) {
                // DC mainly represents average brightness and is deliberately
                // excluded from perceptual comparison. Keep its bit clear.
                if (!(u == 0 && v == 0) && coeff[v][u] > median) hash = hash or (1L shl bit)
                bit += 1
            }
            return hash
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun similarity(a: Fingerprint, b: Fingerprint): Double {
        val dDistance = java.lang.Long.bitCount(a.dHash xor b.dHash)
        val pDistance = java.lang.Long.bitCount(a.pHash xor b.pHash)
        val distance = (dDistance + pDistance) / 2.0
        return (1.0 - distance / 64.0).coerceIn(0.0, 1.0)
    }

    private val INV_SQRT_2 = 1.0 / kotlin.math.sqrt(2.0)
    private val DCT_COS = Array(8) { u ->
        DoubleArray(32) { x -> cos(((2 * x + 1) * u * PI) / 64.0) }
    }

    private fun luma(pixel: Int): Int {
        val r = pixel shr 16 and 0xff
        val g = pixel shr 8 and 0xff
        val b = pixel and 0xff
        return (0.299 * r + 0.587 * g + 0.114 * b).roundToInt()
    }
}

class SimilarPhotoFinder(private val context: Context) {
    data class SimilarPair(val left: FileEntry, val right: FileEntry, val similarity: Double)
    data class Result(
        val groups: List<List<FileEntry>>,
        val pairs: List<SimilarPair>,
        val limited: Boolean,
        val candidateLimitReached: Boolean,
        val comparisonLimitReached: Boolean,
        val pairLimitReached: Boolean,
        val comparisons: Long,
    )

    suspend fun find(
        images: List<FileEntry>,
        threshold: Double = 0.86,
        maxImages: Int = DEFAULT_MAX_IMAGES,
        maxComparisons: Long = DEFAULT_MAX_COMPARISONS,
        maxStoredPairs: Int = DEFAULT_MAX_STORED_PAIRS,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): Result {
        require(maxImages > 0) { "Image limit must be positive" }
        require(maxComparisons >= 0) { "Comparison limit must not be negative" }
        require(maxStoredPairs >= 0) { "Pair limit must not be negative" }
        val eligibleCount = images.count { !it.isDirectory }
        val candidates = images.asSequence().filterNot(FileEntry::isDirectory).take(maxImages).toList()
        val candidateLimitReached = eligibleCount > candidates.size
        val fingerprints = mutableListOf<Pair<FileEntry, PerceptualHash.Fingerprint>>()
        candidates.forEachIndexed { index, entry ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val bitmap = decode(entry)
            if (bitmap != null) {
                try { fingerprints += entry to PerceptualHash.fingerprint(bitmap) }
                finally { bitmap.recycle() }
            }
            onProgress(index + 1, candidates.size, entry.name)
        }

        val parent = IntArray(fingerprints.size) { it }
        fun root(x: Int): Int {
            var n = x
            while (parent[n] != n) {
                parent[n] = parent[parent[n]]
                n = parent[n]
            }
            return n
        }
        fun union(a: Int, b: Int) {
            val ra = root(a); val rb = root(b)
            if (ra != rb) parent[rb] = ra
        }

        // Keep only the strongest matches for presentation. Union every match,
        // including those not retained here, so the duplicate groups stay intact
        // without allowing millions of SimilarPair objects to exhaust the heap.
        val strongestPairs = PriorityQueue<SimilarPair>(compareBy(SimilarPair::similarity))
        var pairLimitReached = false
        var comparisons = 0L
        var comparisonLimitReached = false
        comparisonLoop@ for (i in fingerprints.indices) {
            for (j in i + 1 until fingerprints.size) {
                if (comparisons >= maxComparisons) {
                    comparisonLimitReached = true
                    break@comparisonLoop
                }
                if ((comparisons and 0x3ffL) == 0L) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                }
                comparisons += 1
                val similarity = PerceptualHash.similarity(fingerprints[i].second, fingerprints[j].second)
                if (similarity >= threshold) {
                    union(i, j)
                    val pair = SimilarPair(fingerprints[i].first, fingerprints[j].first, similarity)
                    when {
                        maxStoredPairs == 0 -> pairLimitReached = true
                        strongestPairs.size < maxStoredPairs -> strongestPairs += pair
                        else -> {
                            pairLimitReached = true
                            if (pair.similarity > requireNotNull(strongestPairs.peek()).similarity) {
                                strongestPairs.poll()
                                strongestPairs += pair
                            }
                        }
                    }
                }
            }
        }
        val groups = fingerprints.indices.groupBy(::root).values
            .filter { it.size > 1 }
            .map { indices -> indices.map { fingerprints[it].first } }
            .sortedByDescending(List<FileEntry>::size)
        val pairs = strongestPairs.toList().sortedByDescending(SimilarPair::similarity)
        return Result(
            groups = groups,
            pairs = pairs,
            limited = candidateLimitReached || comparisonLimitReached || pairLimitReached,
            candidateLimitReached = candidateLimitReached,
            comparisonLimitReached = comparisonLimitReached,
            pairLimitReached = pairLimitReached,
            comparisons = comparisons,
        )
    }

    private fun decode(entry: FileEntry): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (entry.uri.scheme == ContentResolver.SCHEME_FILE) {
            BitmapFactory.decodeFile(entry.uri.path, bounds)
        } else {
            context.contentResolver.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        return if (entry.uri.scheme == ContentResolver.SCHEME_FILE) {
            BitmapFactory.decodeFile(entry.uri.path, options)
        } else {
            context.contentResolver.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }

    companion object {
        const val DEFAULT_MAX_IMAGES = 2_000
        const val DEFAULT_MAX_COMPARISONS = 2_000_000L
        const val DEFAULT_MAX_STORED_PAIRS = 10_000
    }
}
