package com.toxicbakery.app.bison.common.version

/**
 * Fetches the text of [url] with an HTTPS GET request for the GitHub Releases API.
 * Returns `null` when the network is offline, the status is not 200, or the call times out.
 */
internal expect suspend fun fetchGitHubReleaseJson(
    url: String,
    timeoutMs: Int = 5_000,
): String?
