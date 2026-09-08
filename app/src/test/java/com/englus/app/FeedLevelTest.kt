package com.englus.app

import com.englus.app.data.PRESET_FEEDS
import com.englus.app.data.feedsForLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedLevelTest {

    @Test
    fun everyPresetIsUsableAndTagged() {
        assertTrue("the pool is too small to be random", PRESET_FEEDS.size >= 6)
        PRESET_FEEDS.forEach {
            assertTrue("${it.title} is not https", it.feedUrl.startsWith("https://"))
            assertTrue("${it.title} has no level", it.level in setOf("초급", "중급", "고급"))
            assertTrue("${it.title} has no title", it.title.isNotBlank())
        }
        // Duplicate URLs would make one source twice as likely to be picked.
        assertEquals(PRESET_FEEDS.size, PRESET_FEEDS.map { it.feedUrl }.toSet().size)
    }

    @Test
    fun everyLevelHasSomethingToOffer() {
        listOf("A2", "B1", "B2", "C1", "C2", null).forEach { level ->
            val feeds = feedsForLevel(level)
            assertTrue("$level got nothing", feeds.isNotEmpty())
            // One feed is a rotation of the same show; random needs more.
            assertTrue("$level got only ${feeds.size}", feeds.size >= 2)
        }
    }

    /** Boundaries take from both sides rather than a single narrow band. */
    @Test
    fun boundaryLevelsSpanTwoBands() {
        assertEquals(setOf("초급", "중급"), feedsForLevel("B1").map { it.level }.toSet())
        assertEquals(setOf("중급", "고급"), feedsForLevel("C1").map { it.level }.toSet())
        assertEquals(setOf("초급"), feedsForLevel("A2").map { it.level }.toSet())
        assertEquals(setOf("고급"), feedsForLevel("C2").map { it.level }.toSet())
    }

    /** An unmeasured learner must not be handed NPR on day one. */
    @Test
    fun unknownLevelStartsEasy() {
        assertTrue(feedsForLevel(null).none { it.level == "고급" })
    }
}
