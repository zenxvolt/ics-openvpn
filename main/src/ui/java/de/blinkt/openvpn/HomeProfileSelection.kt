package de.blinkt.openvpn

import android.content.Context
import de.blinkt.openvpn.core.GlobalPreferences
import de.blinkt.openvpn.core.Preferences
import de.blinkt.openvpn.core.ProfileManager

/** The Home choice is independent of the always-on/boot profile. */
object HomeProfileSelection {
    const val RANDOM = "random"
    private const val KEY = "homeProfileChoice"

    fun profiles(context: Context): List<VpnProfile> {
        val manager = ProfileManager.getInstance(context)
        manager.refreshVPNList(context)
        return manager.profiles.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun choice(context: Context, profiles: List<VpnProfile>): String? {
        if (GlobalPreferences.getMinimalUi()) return ProfileManager.getAlwaysOnVPN(context)?.uuidString
        val id = Preferences.getDefaultSharedPreferences(context).getString(KEY, null)
        if (id == RANDOM && profiles.isNotEmpty()) return RANDOM
        if (profiles.any { it.uuidString == id }) return id
        return ProfileManager.getAlwaysOnVPN(context)?.uuidString ?: profiles.firstOrNull()?.uuidString
    }

    fun save(context: Context, choice: String) {
        Preferences.getDefaultSharedPreferences(context).edit().putString(KEY, choice).apply()
    }

    fun resolve(context: Context, profiles: List<VpnProfile>): VpnProfile? {
        if (GlobalPreferences.getMinimalUi()) return ProfileManager.getAlwaysOnVPN(context)
        val choice = choice(context, profiles)
        return if (choice == RANDOM) profiles.randomOrNull() else profiles.find { it.uuidString == choice }
    }
}
