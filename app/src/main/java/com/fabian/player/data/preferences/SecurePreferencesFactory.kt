@file:Suppress("DEPRECATION")

package com.fabian.player.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Creates an [EncryptedSharedPreferences] instance for sensitive credentials and API keys.
 * If the Android Keystore entry is unreadable, it resets the encrypted file and retries once.
 * If Keystore remains unavailable, it falls back to a process-scoped in-memory store rather than
 * persisting secrets in plaintext XML on disk.
 */
internal object SecurePreferencesFactory {
    fun open(context: Context, prefsName: String, tag: String): SharedPreferences {
        // Remove any legacy plaintext fallback file created by earlier versions.
        runCatching { context.deleteSharedPreferences("${prefsName}_plain") }

        return try {
            createEncryptedPrefs(context, prefsName)
        } catch (e: Exception) {
            Timber.e(e, "$tag: EncryptedSharedPreferences unreadable, deleting and recreating")
            runCatching { context.deleteSharedPreferences(prefsName) }
            try {
                createEncryptedPrefs(context, prefsName)
            } catch (e2: Exception) {
                Timber.e(e2, "$tag: Encrypted prefs unavailable; using volatile in-memory storage instead of plaintext")
                VolatileSharedPreferences()
            }
        }
    }

    private fun createEncryptedPrefs(context: Context, prefsName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

private class VolatileSharedPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Synchronized
    override fun getAll(): MutableMap<String, *> = HashMap(values)

    @Synchronized
    override fun getString(key: String?, defValue: String?): String? =
        if (key != null && values.containsKey(key)) values[key] as? String ?: defValue else defValue

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val set = if (key != null) values[key] as? Set<String> else null
        return set?.let { HashSet(it) } ?: defValues
    }

    @Synchronized
    override fun getInt(key: String?, defValue: Int): Int =
        (if (key != null) values[key] as? Int else null) ?: defValue

    @Synchronized
    override fun getLong(key: String?, defValue: Long): Long =
        (if (key != null) values[key] as? Long else null) ?: defValue

    @Synchronized
    override fun getFloat(key: String?, defValue: Float): Float =
        (if (key != null) values[key] as? Float else null) ?: defValue

    @Synchronized
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (if (key != null) values[key] as? Boolean else null) ?: defValue

    @Synchronized
    override fun contains(key: String?): Boolean = key != null && values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.remove(listener)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) {
                if (value == null) removals.add(key) else {
                    removals.remove(key)
                    pending[key] = value
                }
            }
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null) {
                if (values == null) removals.add(key) else {
                    removals.remove(key)
                    pending[key] = HashSet(values)
                }
            }
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) {
                removals.remove(key)
                pending[key] = value
            }
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) {
                removals.remove(key)
                pending[key] = value
            }
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) {
                removals.remove(key)
                pending[key] = value
            }
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) {
                removals.remove(key)
                pending[key] = value
            }
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) {
                pending.remove(key)
                removals.add(key)
            }
        }

        override fun clear(): SharedPreferences.Editor = apply {
            pending.clear()
            removals.clear()
            clearRequested = true
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            val changedKeys = ArrayList<String>()
            synchronized(this@VolatileSharedPreferences) {
                if (clearRequested) {
                    changedKeys.addAll(values.keys)
                    values.clear()
                }
                for (key in removals) {
                    if (values.remove(key) != null && key !in changedKeys) {
                        changedKeys.add(key)
                    }
                }
                for ((key, value) in pending) {
                    values[key] = value
                    if (key !in changedKeys) changedKeys.add(key)
                }
            }
            for (key in changedKeys) {
                listeners.forEach { it.onSharedPreferenceChanged(this@VolatileSharedPreferences, key) }
            }
        }
    }
}
