package com.toxicbakery.app.bison.store

import kotlinx.serialization.json.Json

/**
 * Canonical app-wide [Json] instance used for persisted JSON stores (`machines.json`, `settings.json`)
 * and forward-compatible Herdr JSON payloads.
 */
val appJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = false
}
