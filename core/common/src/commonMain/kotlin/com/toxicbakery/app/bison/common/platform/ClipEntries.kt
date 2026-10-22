package com.toxicbakery.app.bison.common.platform

import androidx.compose.ui.platform.ClipEntry

/** Makes a plain text [ClipEntry] for `LocalClipboard`. [label] is the label that the user can see. */
internal expect fun plainTextClipEntry(label: String, text: String): ClipEntry
