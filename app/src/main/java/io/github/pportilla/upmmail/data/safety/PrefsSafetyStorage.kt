package io.github.pportilla.upmmail.data.safety

import android.content.Context

/** App-private, excluded from Android backup by the application's existing backup policy. */
class PrefsSafetyStorage(context: Context) : SafetyStorage {
    private val preferences = context.getSharedPreferences("safety_profile", Context.MODE_PRIVATE)
    override fun read(): String? = preferences.getString("policy", null)
    override fun write(value: String) {
        check(preferences.edit().putString("policy", value).commit()) { "Cannot persist the safety policy" }
    }
}
