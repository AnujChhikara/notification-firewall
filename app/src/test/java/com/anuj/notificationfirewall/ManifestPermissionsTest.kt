package com.anuj.notificationfirewall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {

    @Test
    fun internetPermissionIsDeclared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertTrue(
            "model requests need the install-time INTERNET capability",
            info.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET),
        )
    }
}
