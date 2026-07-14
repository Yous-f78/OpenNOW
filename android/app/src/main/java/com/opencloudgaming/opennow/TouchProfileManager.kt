package com.opencloudgaming.opennow

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages per-game touch control profiles.
 *
 * Each game gets its own AndroidTouchSettings snapshot, saved as JSON
 * in the app's private storage. When the user launches a game, the
 * corresponding profile is loaded; when they leave, the current settings
 * are saved back.
 *
 * Layout: files/touch_profiles/<gameId>.json
 */
class TouchProfileManager private constructor(private val context: Context) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val profilesDir: File by lazy {
        File(context.filesDir, "touch_profiles").also { it.mkdirs() }
    }

    /**
     * Load the touch profile for a game. Returns null if no profile exists
     * for this game, in which case the caller should use the default settings.
     */
    fun loadProfile(gameId: String): AndroidTouchSettings? {
        if (gameId.isBlank()) return null
        val file = File(profilesDir, "${sanitize(gameId)}.json")
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<AndroidTouchSettings>(file.readText())
        }.getOrNull()
    }

    /**
     * Save the current touch settings as the profile for a game.
     */
    fun saveProfile(gameId: String, settings: AndroidTouchSettings) {
        if (gameId.isBlank()) return
        val file = File(profilesDir, "${sanitize(gameId)}.json")
        runCatching {
            file.writeText(json.encodeToString(settings))
        }
    }

    /**
     * Delete the profile for a game (reset to default).
     */
    fun deleteProfile(gameId: String) {
        if (gameId.isBlank()) return
        File(profilesDir, "${sanitize(gameId)}.json").delete()
    }

    /**
     * Check if a profile exists for a game.
     */
    fun hasProfile(gameId: String): Boolean {
        if (gameId.isBlank()) return false
        return File(profilesDir, "${sanitize(gameId)}.json").exists()
    }

    /**
     * List all saved profile game IDs.
     */
    fun listProfileIds(): List<String> {
        return profilesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * Export a profile to a JSON string (for sharing).
     */
    fun exportProfile(gameId: String): String? {
        val settings = loadProfile(gameId) ?: return null
        return json.encodeToString(settings)
    }

    /**
     * Import a profile from a JSON string.
     */
    fun importProfile(gameId: String, jsonContent: String): Boolean {
        return runCatching {
            val settings = json.decodeFromString<AndroidTouchSettings>(jsonContent)
            saveProfile(gameId, settings)
            true
        }.getOrElse { false }
    }

    private fun sanitize(gameId: String): String {
        return gameId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    companion object {
        @Volatile
        private var instance: TouchProfileManager? = null

        fun get(context: Context): TouchProfileManager {
            return instance ?: synchronized(this) {
                instance ?: TouchProfileManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
