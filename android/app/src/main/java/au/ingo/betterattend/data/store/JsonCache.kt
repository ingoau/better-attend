package au.ingo.betterattend.data.store

import android.content.Context
import au.ingo.betterattend.data.api.AttendJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * Tiny encrypted key→JSON file cache. Keys become file names, so keep them simple. Writes nothing
 * when [SecureBox] has no key: the repositories' in-memory state keeps the app working online.
 */
class JsonCache(context: Context) {
    private val dir = File(context.filesDir, "cache_v1").apply { mkdirs() }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".bin")

    suspend fun <T> read(key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        readBlocking(key, serializer)
    }

    fun <T> readBlocking(key: String, serializer: KSerializer<T>): T? {
        val f = file(key)
        if (!f.exists()) return null
        return runCatching {
            val plain = SecureBox.decrypt(f.readBytes()) ?: return null
            AttendJson.decodeFromString(serializer, plain.decodeToString())
        }.getOrNull()
    }

    suspend fun <T> write(key: String, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        val sealed = SecureBox.encrypt(AttendJson.encodeToString(serializer, value).toByteArray()) ?: return@withContext
        val f = file(key)
        val tmp = File(f.parentFile, f.name + "." + java.util.UUID.randomUUID() + ".tmp")
        try {
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: java.io.IOException) {
            tmp.delete() // e.g. disk full: keep the previous cache rather than crash
        }
    }

    suspend fun remove(key: String) = withContext(Dispatchers.IO) { file(key).delete() }

    suspend fun clear() = withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
}
