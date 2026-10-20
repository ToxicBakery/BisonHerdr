package com.toxicbakery.app.bison.version

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppVersionTest {

    @Test
    fun `app version is non-blank and matches AppVersion object`() {
        assertTrue(APP_VERSION.isNotBlank())
        assertEquals(APP_VERSION, AppVersion.NAME)
        assertTrue(AppVersion.CODE >= 1)
    }
}
