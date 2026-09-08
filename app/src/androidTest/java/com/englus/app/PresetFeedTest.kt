package com.englus.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.PRESET_FEEDS
import com.englus.app.feed.FeedRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every bundled feed has to actually work through this app's own parser.
 *
 * A dead or unparseable feed does not announce itself: the automatic fetch
 * swallows the failure and simply draws from a smaller pool, so the learner
 * quietly gets the same two shows forever. Checking each one individually is
 * the only way that shows up.
 */
@RunWith(AndroidJUnit4::class)
class PresetFeedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyPresetFeedSubscribesAndYieldsItems() = runBlocking {
        val repo = FeedRepository(context)
        val dao = EnglusDatabase.get(context).feeds()
        val failures = mutableListOf<String>()

        PRESET_FEEDS.forEach { preset ->
            val outcome = runCatching {
                val id = repo.subscribe(preset.feedUrl)
                val items = dao.observeItems(id).first()
                if (items.isEmpty()) error("parsed to zero items")
                items.count { it.audioUrl.isNotBlank() }
            }
            outcome.onFailure { failures += "${preset.title}: ${it.message}" }
            outcome.onSuccess { playable ->
                if (playable == 0) failures += "${preset.title}: no playable audio"
            }
        }

        assertTrue("dead or unparseable feeds:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
