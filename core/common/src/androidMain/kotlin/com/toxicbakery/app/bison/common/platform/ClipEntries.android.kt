package com.toxicbakery.app.bison.common.platform

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun plainTextClipEntry(label: String, text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText(label, text))
