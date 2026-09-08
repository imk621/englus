package com.englus.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.englus.app.data.DownloadState
import com.englus.app.data.EnglusDatabase
import com.englus.app.material.MaterialSettings
import com.englus.app.material.MaterialWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fetch runs against the real feeds.
 *
 * A test with a stubbed feed would prove the code compiles. What can actually go
 * wrong here is a dead URL or a feed whose shape does not parse - three of the
 * four VOA zone ids tried while writing this returned an empty document that
 * looked exactly like a working subscription - and only a real request finds
 * that.
 */
@RunWith(AndroidJUnit4::class)
class MaterialWorkerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao = EnglusDatabase.get(context).feeds()

    private fun run(): ListenableWorker.Result =
        TestListenableWorkerBuilder<MaterialWorker>(context).build().let {
            runBlocking { it.doWork() }
        }

    /** Off means off: no subscribing, no downloading, no data spent. */
    @Test
    fun doesNothingWhenTurnedOff() = runBlocking {
        MaterialSettings(context).enabled = false
        val before = dao.observePodcasts().first().size

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals("it subscribed while disabled", before, dao.observePodcasts().first().size)
    }

    @Test
    fun subscribesAndQueuesWithoutAnySetup() = runBlocking {
        val settings = MaterialSettings(context)
        settings.enabled = true
        settings.perDay = 1
        try {
            val result = run()
            assertEquals(ListenableWorker.Result.success(), result)

            val podcasts = dao.observePodcasts().first()
            assertTrue("nothing was subscribed", podcasts.isNotEmpty())

            val items = podcasts.flatMap { dao.observeItems(it.id).first() }
            assertTrue("the feeds parsed to nothing", items.isNotEmpty())
            assertTrue(
                "no episode was picked up",
                items.any { it.downloadState != DownloadState.NONE },
            )
            assertTrue("nothing was recorded as fetched", settings.lastFetchAt > 0)
        } finally {
            settings.enabled = false
        }
    }
}
