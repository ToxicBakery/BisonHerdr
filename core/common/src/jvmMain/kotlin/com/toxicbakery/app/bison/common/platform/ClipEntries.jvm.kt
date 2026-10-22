package com.toxicbakery.app.bison.common.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

// The desktop ClipEntry constructor that takes an AWT Transferable is experimental.
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(label: String, text: String): ClipEntry =
    ClipEntry(StringSelection(text))
