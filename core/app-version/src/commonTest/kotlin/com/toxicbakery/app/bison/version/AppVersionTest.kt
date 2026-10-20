package com.toxicbakery.app.bison.version

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppVersionTest {

    @Test
    fun `app version is non-blank and strips volatile build metadata by default`() {
        assertTrue(AppVersion.NAME.isNotBlank())
        assertFalse(AppVersion.NAME.contains('+'))
        assertTrue(AppVersion.CODE >= 1)
    }
}
