package dev.anilbeesetti.nextplayer.core.common.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubReleaseFetcherTest {

    @Test
    fun compareVersionNames_ordersSemverSegments() {
        assertTrue(GithubReleaseFetcher.compareVersionNames("0.20.0", "0.16.3") > 0)
        assertTrue(GithubReleaseFetcher.compareVersionNames("0.16.3", "0.20.0") < 0)
        assertEquals(0, GithubReleaseFetcher.compareVersionNames("0.20.0", "0.20.0"))
    }
}
