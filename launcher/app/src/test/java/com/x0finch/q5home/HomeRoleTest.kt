package com.x0finch.q5home

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RC

@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class HomeRoleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val roles get() = shadowOf(context.getSystemService(RoleManager::class.java))

    @Test
    fun requestsTheHomeRoleWhenAvailable() {
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        assertEquals("android.app.role.action.REQUEST_ROLE", HomeRole.requestIntent(context).action)
        assertFalse(HomeRole.isDefault(context))
        roles.addHeldRole(RoleManager.ROLE_HOME)
        assertTrue(HomeRole.isDefault(context))
    }

    @Test
    fun withoutRoleFallsBackToHomeSettings() {
        val info = ActivityInfo().apply {
            packageName = "com.android.settings"; name = "com.android.settings.HomeSettings"; exported = true
            applicationInfo = ApplicationInfo().apply { packageName = "com.android.settings" }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Settings.ACTION_HOME_SETTINGS), ResolveInfo().apply { activityInfo = info })
        assertEquals(Settings.ACTION_HOME_SETTINGS, HomeRole.requestIntent(context).action)
    }

    @Test
    fun homeSettingsFallsBackToSystemSettings() {
        assertEquals(Settings.ACTION_SETTINGS, HomeRole.homeSettingsIntent(context).action)
    }
}
