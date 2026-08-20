package com.englus.app

import android.app.Application
import com.englus.app.asr.ModelStore
import com.englus.app.data.EpisodeRepository
import com.englus.app.feed.FeedRepository
import com.englus.app.gemini.ExplainRepository

/**
 * Manual dependency wiring. A DI framework would earn its keep in a team
 * codebase; here it would only add build time and indirection to three objects.
 */
class EnglusApp : Application() {

    val repository: EpisodeRepository by lazy { EpisodeRepository(this) }
    val models: ModelStore by lazy { ModelStore(this) }
    val feeds: FeedRepository by lazy { FeedRepository(this) }
    val explain: ExplainRepository by lazy { ExplainRepository(this) }
}
