package com.toxicbakery.app.bison.store

import kotlinx.serialization.json.Json

/**
 * Canonical app-wide [Json] instance used for persisted JSON stores (`machines.json`, `settings.json`)
 * and Herdr Endpoint Generation 1 forward-compatible control payloads.
 */
val appJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = true
}
