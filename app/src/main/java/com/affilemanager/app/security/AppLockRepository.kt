package com.affilemanager.app.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppLockRepository(context: Context) {
    companion object {
        private const val PREFS = "security_settings_v1"
        private const val KEY_ENABLED = "app_lock_enabled"
    }

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()) { "Užrakto nustatymo įrašyti nepavyko" }
        _enabled.value = enabled
    }
}
