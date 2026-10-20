package com.toxicbakery.app.bison.composite.util

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {

    @Test
    fun `exact tag on a clean tree is the release version`() {
        assertEquals("26.30.1", parseGitDescribe("v26.30.1"))
    }

    @Test
    fun `commits since the tag make it a dev build`() {
        assertEquals("26.30.1-dev+7-gabc123", parseGitDescribe("v26.30.1-7-gabc123"))
    }

    @Test
    fun `a dirty tree on an exact tag is still a dev build`() {
        assertEquals("26.30.1-dev+dirty", parseGitDescribe("v26.30.1-dirty"))
    }

    @Test
    fun `no tag reachable falls back to the sha`() {
        assertEquals("0.0.0-dev+gcf4c9cf", parseGitDescribe("cf4c9cf"))
        assertEquals("0.0.0-dev+gcf4c9cf-dirty", parseGitDescribe("cf4c9cf-dirty"))
    }

    @Test
    fun `no git output at all still yields a valid dev version`() {
        assertEquals("0.0.0-dev", parseGitDescribe(null))
        assertEquals("0.0.0-dev", parseGitDescribe(""))
        assertEquals("0.0.0-dev", parseGitDescribe("   "))
    }

    @Test
    fun `version code matches the documented derivation`() {
        assertEquals(2630001, projectVersionCode("26.30.1"))
        assertEquals(2629001, projectVersionCode("26.29.1"))
    }

    @Test
    fun `version code ignores the dev marker`() {
        assertEquals(2630001, projectVersionCode("26.30.1-dev+7-gabc123"))
    }

    @Test
    fun `version code is monotonic within and across weeks and years`() {
        assert(projectVersionCode("26.30.2") > projectVersionCode("26.30.1"))
        assert(projectVersionCode("26.30.1") > projectVersionCode("26.29.9"))
        assert(projectVersionCode("27.1.1") > projectVersionCode("26.52.9"))
    }

    @Test
    fun `version code floors at one for an untagged dev build`() {
        assertEquals(1, projectVersionCode("0.0.0-dev"))
        assertEquals(1, projectVersionCode("0.0.0-dev+gabc123"))
    }

    @Test
    fun `version code tolerates malformed input rather than failing the build`() {
        assertEquals(1, projectVersionCode(""))
        assertEquals(1, projectVersionCode("garbage"))
        assertEquals(2630000, projectVersionCode("26.30"))
    }

    @Test
    fun `version code stays under the Play ceiling`() {
        assert(projectVersionCode("99.53.999") < 2_100_000_000)
    }

    @Test
    fun `a four digit year would produce the same code`() {
        assertEquals(projectVersionCode("26.29.1"), projectVersionCode("2026.29.1"))
    }

    @Test
    fun `packaging version strips the dev marker jpackage rejects`() {
        assertEquals("26.30.1", packagingVersion("26.30.1-dev+7-gabc123"))
        assertEquals("0.0.0", packagingVersion("0.0.0-dev+gcf4c9cf-dirty"))
    }

    @Test
    fun `packaging version leaves a clean release version untouched`() {
        assertEquals("26.30.1", packagingVersion("26.30.1"))
    }

    @Test
    fun `packaging version keeps the major under the MSI cap of 255`() {
        val major = packagingVersion("26.30.1").substringBefore('.').toInt()
        assert(major <= 255) { "MSI rejects a major version above 255" }
    }
}
