package com.englus.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A spoken conversation, kept as text.
 *
 * The audio is not stored - it is large, and nothing later reads it. The
 * transcript is what makes the session worth anything the next day: it can be
 * corrected, mined for sentences to review, and compared against the one before.
 * A live conversation that leaves no record is entertainment.
 */
@Entity(tableName = "conversations", indices = [Index("startedAt")])
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val startedAt: Long,
    val durationSec: Int = 0,
    val model: String,
    /** Filled in afterwards by a cheap text call; empty until then. */
    val feedback: String = "",
    /** How many of the learner's turns had something to correct. */
    val mistakeCount: Int = 0,
)

@Entity(
    tableName = "conversation_turns",
    indices = [Index("conversationId")],
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ConversationTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val position: Int,
    /** True for the learner, false for the model. */
    val fromUser: Boolean,
    val text: String,
    /**
     * A better way to say what the learner said, filled in after the session.
     * Null for the model's turns and for anything that needed no change.
     */
    val correction: String? = null,
    val note: String? = null,
)
