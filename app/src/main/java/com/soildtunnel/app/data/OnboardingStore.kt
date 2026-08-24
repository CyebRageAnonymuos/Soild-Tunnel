package com.soildtunnel.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore(name = "soildtunnel_onboarding")

/**
 * Tracks whether the first-run onboarding (merged into SoildTunnel) has been
 * completed. Kept in its own tiny DataStore so it survives profile resets.
 */
class OnboardingStore(private val context: Context) {
    private val doneKey = booleanPreferencesKey("onboarding_done")

    val completed: Flow<Boolean> =
        context.onboardingDataStore.data.map { it[doneKey] ?: false }

    suspend fun markCompleted() {
        context.onboardingDataStore.edit { it[doneKey] = true }
    }
}
