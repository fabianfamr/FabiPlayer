package com.fabian.player.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ThemePreferencesRepositoryTest {

    @Test
    fun `interface preferences default to the bundled font at system scale and change independently`() = runTest {
        val tempDir = Files.createTempDirectory("interface-appearance-test")
        try {
            val repository = ThemePreferencesRepository(
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                )
            )
            assertEquals(InterfaceAppearance(), repository.interfaceAppearanceFlow.first())
            repository.setUseSystemFont(true)
            repository.setInterfaceScale(110)
            assertEquals(InterfaceAppearance(true, 110), repository.interfaceAppearanceFlow.first())
            repository.setInterfaceScale(0)
            assertEquals(InterfaceAppearance(useSystemFont = true), repository.interfaceAppearanceFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `app wide now playing colors default to enabled and persist`() = runTest {
        val tempDir = Files.createTempDirectory("theme-preferences-repository-test")
        try {
            val repository = ThemePreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                )
            )

            assertTrue(repository.globalNowPlayingThemeEnabledFlow.first())

            repository.setGlobalNowPlayingThemeEnabled(false)

            assertFalse(repository.globalNowPlayingThemeEnabledFlow.first())

            repository.setGlobalNowPlayingThemeEnabled(true)

            assertTrue(repository.globalNowPlayingThemeEnabledFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `app wide theme transition duration defaults to 1500ms and clamps to 1s-4s`() = runTest {
        val tempDir = Files.createTempDirectory("theme-transition-test")
        try {
            val repository = ThemePreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                )
            )

            assertEquals(1500, repository.globalNowPlayingThemeTransitionMsFlow.first())

            repository.setGlobalNowPlayingThemeTransitionMs(3500)
            assertEquals(3500, repository.globalNowPlayingThemeTransitionMsFlow.first())

            repository.setGlobalNowPlayingThemeTransitionMs(200)
            assertEquals(1000, repository.globalNowPlayingThemeTransitionMsFlow.first())

            repository.setGlobalNowPlayingThemeTransitionMs(9000)
            assertEquals(4000, repository.globalNowPlayingThemeTransitionMsFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `app wide theme transition mode defaults to gradual and persists selection`() = runTest {
        val tempDir = Files.createTempDirectory("theme-transition-mode-test")
        try {
            val repository = ThemePreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                )
            )

            assertEquals(
                GlobalThemeTransitionMode.GRADUAL,
                repository.globalNowPlayingThemeTransitionModeFlow.first()
            )

            repository.setGlobalNowPlayingThemeTransitionMode(GlobalThemeTransitionMode.AFTER_DELAY)
            assertEquals(
                GlobalThemeTransitionMode.AFTER_DELAY,
                repository.globalNowPlayingThemeTransitionModeFlow.first()
            )

            repository.setGlobalNowPlayingThemeTransitionMode(GlobalThemeTransitionMode.GRADUAL)
            assertEquals(
                GlobalThemeTransitionMode.GRADUAL,
                repository.globalNowPlayingThemeTransitionModeFlow.first()
            )
            assertEquals(
                GlobalThemeTransitionMode.GRADUAL,
                GlobalThemeTransitionMode.fromStorageKey("unknown_mode")
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
