package com.englus.app

import android.app.Application
import com.englus.app.asr.ModelStore
import com.englus.app.data.EpisodeRepository
import com.englus.app.feed.FeedRepository
import com.englus.app.gemini.ExplainRepository
import com.englus.app.gemini.ExamRepository
import com.englus.app.gemini.WritingRepository
import com.englus.app.ocr.ReadingRepository
import com.englus.app.srs.ReminderWorker
import com.englus.app.srs.SrsRepository
import com.englus.app.live.LiveRepository
import com.englus.app.vocab.VocabRepository

/**
 * Manual dependency wiring. A DI framework would earn its keep in a team
 * codebase; here it would only add build time and indirection to three objects.
 */
class EnglusApp : Application() {

    val repository: EpisodeRepository by lazy { EpisodeRepository(this) }
    val models: ModelStore by lazy { ModelStore(this) }
    val feeds: FeedRepository by lazy { FeedRepository(this) }
    val explain: ExplainRepository by lazy { ExplainRepository(this) }
    val srs: SrsRepository by lazy { SrsRepository(this) }
    val writing: WritingRepository by lazy { WritingRepository(this) }
    val reading: ReadingRepository by lazy { ReadingRepository(this) }
    val exams: ExamRepository by lazy { ExamRepository(this) }
    val vocab: VocabRepository by lazy { VocabRepository(this) }
    val live: LiveRepository by lazy { LiveRepository(this) }

    override fun onCreate() {
        super.onCreate()
        ReminderWorker.schedule(this)
    }
}
