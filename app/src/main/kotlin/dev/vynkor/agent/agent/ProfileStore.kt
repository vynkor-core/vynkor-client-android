package dev.vynkor.agent.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray

/**
 * Persists the set of host profiles plus the id of the active one.
 *
 * Storage evolution:
 * 1. legacy single-config keys (`host_url`/`jwt_token`/`jwt_secret`);
 * 2. plaintext JSON array under [KEY_PROFILES];
 * 3. current: AES-GCM encrypted JSON under [KEY_PROFILES_ENC] (R-03).
 * Each older format migrates forward on first load. A store that fails to
 * decrypt/parse is quarantined, never silently wiped.
 */
object ProfileStore {
    private const val TAG = "ProfileStore"
    private const val PREFS = "vynkor_config"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_PROFILES_ENC = "profiles_enc"
    private const val KEY_ACTIVE = "active_profile"
    private const val QUARANTINE_PREFIX = "corrupt_"

    private const val KEY_HOST_URL = "host_url"
    private const val KEY_JWT = "jwt_token"
    private const val KEY_SECRET = "jwt_secret"

    fun list(context: Context): List<HostProfile> = load(context)

    fun get(context: Context, id: String): HostProfile? =
        load(context).firstOrNull { it.id == id }

    fun active(context: Context): HostProfile? {
        val profiles = load(context)
        val activeId = prefs(context).getString(KEY_ACTIVE, null)
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun save(context: Context, profile: HostProfile) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        persist(context, list)
        if (prefs(context).getString(KEY_ACTIVE, null) == null) {
            setActive(context, profile.id)
        }
    }

    fun delete(context: Context, id: String) {
        val list = load(context).toMutableList()
        list.removeAll { it.id == id }
        persist(context, list)
        if (prefs(context).getString(KEY_ACTIVE, null) == id) {
            prefs(context).edit().putString(KEY_ACTIVE, list.firstOrNull()?.id).apply()
        }
    }

    fun isConfigured(context: Context): Boolean =
        active(context)?.hostUrl?.isNotBlank() == true

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): MutableList<HostProfile> {
        val p = prefs(context)

        p.getString(KEY_PROFILES_ENC, null)?.let { enc ->
            val json = ProfileCrypto.decrypt(enc)
                ?: return quarantine(p, KEY_PROFILES_ENC).toMutableList()
            return parse(json)
                ?: return quarantine(p, KEY_PROFILES_ENC).toMutableList()
        }

        p.getString(KEY_PROFILES, null)?.let { raw ->
            parse(raw)?.takeIf { it.isNotEmpty() }?.let { list ->
                persist(context, list)
                return list
            }
            quarantine(p, KEY_PROFILES)
        }

        migrateLegacy(context)?.let { return it }
        return mutableListOf()
    }

    /** Pre-multi-host format: one profile spread over single-purpose keys. */
    private fun migrateLegacy(context: Context): MutableList<HostProfile>? {
        val p = prefs(context)
        val legacyUrl = p.getString(KEY_HOST_URL, null) ?: return null
        val profile = HostProfile(
            name = "Default",
            hostUrl = legacyUrl,
            deviceId = DeviceIdentity.deviceId(context),
            jwtToken = p.getString(KEY_JWT, null) ?: "",
            jwtSecret = p.getString(KEY_SECRET, null) ?: "",
        )
        val list = mutableListOf(profile)
        persist(context, list)
        p.edit().remove(KEY_HOST_URL).remove(KEY_JWT).remove(KEY_SECRET).apply()
        return list
    }

    /**
     * Fail-closed: when encryption is unavailable the store keeps its previous
     * value and the change stays memory-only — plaintext secrets never land
     * on disk.
     */
    private fun persist(context: Context, list: List<HostProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val enc = ProfileCrypto.encrypt(arr.toString())
        if (enc == null) {
            Log.e(TAG, "encryption unavailable; refusing to write plaintext profiles")
            return
        }
        prefs(context).edit().putString(KEY_PROFILES_ENC, enc).remove(KEY_PROFILES).apply()
    }

    private fun parse(json: String): MutableList<HostProfile>? = try {
        val arr = JSONArray(json)
        (0 until arr.length())
            .map { HostProfile.fromJson(arr.getJSONObject(it)) }
            .toMutableList()
    } catch (_: Exception) {
        null
    }

    /** Moves an unreadable store aside so user data survives for recovery. */
    private fun quarantine(p: SharedPreferences, key: String): List<HostProfile> {
        val value = p.getString(key, null)
        if (value != null) {
            p.edit()
                .putString("$QUARANTINE_PREFIX${System.currentTimeMillis()}_$key", value)
                .remove(key)
                .apply()
        }
        Log.w(TAG, "profile store unreadable, quarantined ($key)")
        return emptyList()
    }
}
