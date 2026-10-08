package com.aurafiles.app.cloud.google

import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

interface GoogleDriveApi {
    fun about(): GoogleDriveAbout
    fun file(fileId: String): GoogleDriveFile?
    fun listChildren(parentId: String): List<GoogleDriveFile>
    fun findChildren(parentId: String, name: String): List<GoogleDriveFile>
    fun createFolder(parentId: String, name: String): GoogleDriveFile
    fun rename(fileId: String, newName: String): GoogleDriveFile
    fun move(fileId: String, destinationParentId: String, currentParents: List<String>): GoogleDriveFile
    fun trash(fileId: String)
    fun startResumableUpload(
        fileId: String?,
        parentId: String,
        name: String,
        mimeType: String,
        expectedSize: Long?,
    ): String
}

class GoogleDriveApiClient internal constructor(
    private val accessToken: String,
    private val transport: GoogleHttpTransport = UrlConnectionGoogleHttpTransport(),
) : GoogleDriveApi {
    init {
        require(accessToken.isNotBlank()) { "Пустой Google access token" }
    }

    override fun about(): GoogleDriveAbout {
        val response = request(
            "GET",
            "about",
            mapOf("fields" to "user(permissionId,displayName,emailAddress),storageQuota(limit,usage,usageInDrive,usageInDriveTrash)"),
        )
        requireSuccess(response, setOf(200))
        val json = parseGoogleJsonObject(response)
        val user = json.optJSONObject("user")
        val quota = json.optJSONObject("storageQuota")
        return GoogleDriveAbout(
            user = GoogleDriveUser(
                permissionId = user?.optString("permissionId").orEmpty(),
                displayName = user?.optString("displayName").orEmpty(),
                emailAddress = user?.optString("emailAddress").orEmpty(),
            ),
            limit = quota.nullableLong("limit"),
            usage = quota.nullableLong("usage"),
            usageInDrive = quota.nullableLong("usageInDrive"),
            usageInDriveTrash = quota.nullableLong("usageInDriveTrash"),
        )
    }

    override fun file(fileId: String): GoogleDriveFile? {
        require(fileId.isNotBlank()) { "Пустой Google Drive file ID" }
        val response = request(
            "GET",
            "files/${googleUrlEncode(fileId)}",
            mapOf("fields" to FILE_FIELDS, "supportsAllDrives" to "true"),
        )
        if (response.statusCode == 404) return null
        requireSuccess(response, setOf(200))
        return parseFile(parseGoogleJsonObject(response))
    }

    override fun listChildren(parentId: String): List<GoogleDriveFile> = listQuery(
        "'${escapeDriveQuery(parentId)}' in parents and trashed = false",
    )

    override fun findChildren(parentId: String, name: String): List<GoogleDriveFile> = listQuery(
        "'${escapeDriveQuery(parentId)}' in parents and name = '${escapeDriveQuery(name)}' and trashed = false",
    )

    override fun createFolder(parentId: String, name: String): GoogleDriveFile {
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", GOOGLE_DRIVE_FOLDER_MIME)
            .put("parents", JSONArray().put(parentId))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val response = request(
            "POST",
            "files",
            mapOf("fields" to FILE_FIELDS, "supportsAllDrives" to "true"),
            body,
        )
        requireSuccess(response, setOf(200, 201))
        return parseFile(parseGoogleJsonObject(response))
    }

    override fun rename(fileId: String, newName: String): GoogleDriveFile {
        val body = JSONObject().put("name", newName).toString().toByteArray(Charsets.UTF_8)
        val response = request(
            "PATCH",
            "files/${googleUrlEncode(fileId)}",
            mapOf("fields" to FILE_FIELDS, "supportsAllDrives" to "true"),
            body,
        )
        requireSuccess(response, setOf(200))
        return parseFile(parseGoogleJsonObject(response))
    }

    override fun move(fileId: String, destinationParentId: String, currentParents: List<String>): GoogleDriveFile {
        val params = linkedMapOf(
            "addParents" to destinationParentId,
            "fields" to FILE_FIELDS,
            "supportsAllDrives" to "true",
        )
        currentParents.filter(String::isNotBlank).joinToString(",").takeIf(String::isNotBlank)?.let {
            params["removeParents"] = it
        }
        val response = request("PATCH", "files/${googleUrlEncode(fileId)}", params, "{}".toByteArray(Charsets.UTF_8))
        requireSuccess(response, setOf(200))
        return parseFile(parseGoogleJsonObject(response))
    }

    override fun trash(fileId: String) {
        val body = JSONObject().put("trashed", true).toString().toByteArray(Charsets.UTF_8)
        val response = request(
            "PATCH",
            "files/${googleUrlEncode(fileId)}",
            mapOf("fields" to "id,trashed", "supportsAllDrives" to "true"),
            body,
        )
        requireSuccess(response, setOf(200))
    }

    override fun startResumableUpload(
        fileId: String?,
        parentId: String,
        name: String,
        mimeType: String,
        expectedSize: Long?,
    ): String {
        val isUpdate = !fileId.isNullOrBlank()
        val metadata = JSONObject().put("name", name)
        if (!isUpdate) metadata.put("parents", JSONArray().put(parentId))
        val url = buildUrl(
            base = UPLOAD_BASE,
            endpoint = if (isUpdate) "files/${googleUrlEncode(requireNotNull(fileId))}" else "files",
            params = mapOf("uploadType" to "resumable", "fields" to FILE_FIELDS, "supportsAllDrives" to "true"),
        )
        val headers = linkedMapOf(
            "Authorization" to "Bearer $accessToken",
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=UTF-8",
            "X-Upload-Content-Type" to mimeType,
            "User-Agent" to USER_AGENT,
        )
        expectedSize?.takeIf { it >= 0L }?.let { headers["X-Upload-Content-Length"] = it.toString() }
        val response = transport.execute(
            GoogleHttpRequest(
                method = if (isUpdate) "PATCH" else "POST",
                url = url,
                headers = headers,
                body = metadata.toString().toByteArray(Charsets.UTF_8),
            )
        )
        requireSuccess(response, setOf(200, 201))
        return response.header("Location")?.takeIf(String::isNotBlank)
            ?: throw GoogleDriveApiException(
                kind = GoogleDriveFailureKind.BAD_RESPONSE,
                message = "Google Drive не вернул URL resumable upload session.",
            )
    }

    private fun listQuery(query: String): List<GoogleDriveFile> {
        val result = mutableListOf<GoogleDriveFile>()
        var pageToken = ""
        do {
            val params = linkedMapOf(
                "q" to query,
                "spaces" to "drive",
                "pageSize" to PAGE_SIZE.toString(),
                "fields" to "nextPageToken,files($FILE_FIELDS)",
                "orderBy" to "folder,name_natural",
                "supportsAllDrives" to "true",
                "includeItemsFromAllDrives" to "true",
            )
            if (pageToken.isNotBlank()) params["pageToken"] = pageToken
            val response = request("GET", "files", params)
            requireSuccess(response, setOf(200))
            val json = parseGoogleJsonObject(response)
            val files = json.optJSONArray("files")
            for (index in 0 until (files?.length() ?: 0)) {
                files?.optJSONObject(index)?.let { result += parseFile(it) }
            }
            pageToken = json.optString("nextPageToken")
        } while (pageToken.isNotBlank())
        return result.sortedWith(compareByDescending<GoogleDriveFile> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.presentedName() })
    }

    private fun request(
        method: String,
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): GoogleHttpResponse {
        val headers = linkedMapOf(
            "Authorization" to "Bearer $accessToken",
            "Accept" to "application/json",
            "User-Agent" to USER_AGENT,
        )
        if (body != null) headers["Content-Type"] = "application/json; charset=UTF-8"
        return transport.execute(
            GoogleHttpRequest(
                method = method,
                url = buildUrl(API_BASE, endpoint, params),
                headers = headers,
                body = body,
            )
        )
    }

    private fun requireSuccess(response: GoogleHttpResponse, expected: Set<Int>) {
        if (response.statusCode !in expected) throw parseGoogleError(response)
    }

    private fun parseFile(json: JSONObject): GoogleDriveFile {
        val parentsJson = json.optJSONArray("parents")
        val parents = buildList {
            for (index in 0 until (parentsJson?.length() ?: 0)) {
                parentsJson?.optString(index)?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val capabilities = json.optJSONObject("capabilities")
        val shortcut = json.optJSONObject("shortcutDetails")
        return GoogleDriveFile(
            id = json.optString("id"),
            name = json.optString("name"),
            mimeType = json.optString("mimeType"),
            size = json.optString("size").toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            modifiedAtEpochMs = parseGoogleTime(json.optString("modifiedTime")),
            parents = parents,
            canDownload = capabilities?.optBoolean("canDownload", true) ?: true,
            shortcutTargetId = shortcut?.optString("targetId").orEmpty(),
            trashed = json.optBoolean("trashed", false),
        ).also { require(it.id.isNotBlank()) { "Google Drive вернул файл без id" } }
    }

    private fun buildUrl(base: String, endpoint: String, params: Map<String, String>): String {
        val url = base.trimEnd('/') + "/" + endpoint.trimStart('/')
        if (params.isEmpty()) return url
        return url + "?" + params.entries.joinToString("&") { (key, value) -> "${googleUrlEncode(key)}=${googleUrlEncode(value)}" }
    }

    private fun escapeDriveQuery(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")

    private fun parseGoogleTime(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(value).toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun JSONObject?.nullableLong(name: String): Long? {
        if (this == null || !has(name) || isNull(name)) return null
        return optString(name).toLongOrNull() ?: runCatching { getLong(name) }.getOrNull()
    }

    companion object {
        const val API_BASE = "https://www.googleapis.com/drive/v3/"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3/"
        private const val PAGE_SIZE = 1000
        private const val USER_AGENT = "AuraFiles/1.2.4"
        private const val FILE_FIELDS = "id,name,mimeType,size,modifiedTime,parents,trashed,capabilities(canDownload),shortcutDetails(targetId,targetMimeType)"
    }
}
