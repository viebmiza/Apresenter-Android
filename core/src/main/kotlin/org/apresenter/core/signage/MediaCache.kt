package org.apresenter.core.signage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Persists the offline manifest, manual override, and SHA-256 verified media files on disk so the TV
 * player continues running even when the Apresenter Desktop PC is offline.
 */
class MediaCache(rootDir: File) {
    private val signageDir = File(rootDir, "signage").apply { mkdirs() }
    private val filesDir = File(signageDir, "files").apply { mkdirs() }
    private val manifestFile = File(signageDir, "manifest.json")
    private val manifestVersionFile = File(signageDir, "manifest.version")
    private val overrideFile = File(signageDir, "override.json")

    fun loadManifest(): Manifest? = runCatching {
        if (!manifestFile.isFile) return null
        SignageJson.decodeFromString(Manifest.serializer(), manifestFile.readText())
    }.getOrNull()

    fun loadManifestVersion(): String? = runCatching {
        manifestVersionFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun saveManifest(manifest: Manifest, version: String?) {
        manifestFile.writeText(SignageJson.encodeToString(Manifest.serializer(), manifest))
        if (version != null) {
            manifestVersionFile.writeText(version)
        }
    }

    fun loadOverride(): ScreenOverride? = runCatching {
        if (!overrideFile.isFile) return null
        SignageJson.decodeFromString(ScreenOverride.serializer(), overrideFile.readText())
    }.getOrNull()

    fun saveOverride(override: ScreenOverride?) {
        if (override == null) {
            overrideFile.delete()
        } else {
            overrideFile.writeText(SignageJson.encodeToString(ScreenOverride.serializer(), override))
        }
    }

    fun fileFor(sha256: String): File? {
        if (!isSha256Hex(sha256)) return null
        val target = File(filesDir, sha256)
        return target.takeIf { it.isFile && it.length() > 0L }
    }

    fun isPlaylistReady(playlist: Playlist): Boolean {
        if (playlist.items.isEmpty()) return false
        return playlist.items.any { item ->
            item.kind.isLink || fileFor(item.sha256) != null
        }
    }

    fun clearAll() {
        manifestFile.delete()
        manifestVersionFile.delete()
        overrideFile.delete()
        filesDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun syncFiles(
        manifest: Manifest,
        baseHttpUrl: String,
        bearerToken: String,
        client: OkHttpClient,
    ): List<String> = withContext(Dispatchers.IO) {
        val wantedHashes = manifest.fileHashes().filter(::isSha256Hex).toSet()
        val errors = mutableListOf<String>()

        for (sha in wantedHashes) {
            if (fileFor(sha) != null) continue
            val tempFile = File(filesDir, "$sha.tmp")
            val destFile = File(filesDir, sha)
            val request = Request.Builder()
                .url("$baseHttpUrl/api/signage/files/$sha")
                .header("Authorization", "Bearer $bearerToken")
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    val body = response.body ?: error("corpo vazio")
                    val digest = MessageDigest.getInstance("SHA-256")
                    tempFile.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                digest.update(buffer, 0, read)
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actualHash != sha) {
                        tempFile.delete()
                        error("SHA-256 divergente para $sha")
                    }
                    if (!tempFile.renameTo(destFile)) {
                        tempFile.copyTo(destFile, overwrite = true)
                        tempFile.delete()
                    }
                }
            }.onFailure { err ->
                tempFile.delete()
                errors += "Falha ao baixar midia ${sha.take(8)}: ${err.message}"
            }
        }

        // Remove orphaned media no longer referenced in manifest
        filesDir.listFiles()?.forEach { file ->
            if (file.name !in wantedHashes && !file.name.endsWith(".tmp")) {
                file.delete()
            }
        }
        errors
    }

    fun freeSpaceBytes(): Long = signageDir.usableSpace
}
