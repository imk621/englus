package com.englus.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.englus.app.data.SentenceNote

/**
 * The cloud explanation, shared by the player, dictation and review screens.
 *
 * Deliberately behind a button rather than fetched with the sentence: every
 * expansion is an API call against a daily quota, and most sentences never need
 * one. The on-device translation already covers "roughly what did that mean".
 */
@Composable
fun ExplainSection(
    note: SentenceNote?,
    loading: Boolean,
    error: String?,
    configured: Boolean,
    onExplain: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: (() -> Unit)? = null,
) {
    if (!configured) {
        TextButton(onClick = onOpenSettings) {
            Text("자세히 보려면 Gemini API 키 설정 →", fontSize = 12.sp)
        }
        return
    }

    if (note == null && !loading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onExplain) { Text("자세히", fontSize = 13.sp) }
            error?.let {
                Spacer(Modifier.height(0.dp))
                Text(
                    "  $it",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }

    if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("  설명 생성 중…", fontSize = 12.sp)
        }
        return
    }

    note!!
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Field("해석", note.translation)
            // The one thing a dictionary cannot answer: why it was not audible.
            Field("소리", note.sound)
            Field("구조", note.structure)

            val expressions = note.expressionList()
            if (expressions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("표현", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                expressions.forEach { (text, meaning) ->
                    Spacer(Modifier.height(2.dp))
                    Text("· $text — $meaning", fontSize = 13.sp)
                }
            }

            Field("문법", note.grammar)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.model,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onRefresh?.let {
                    TextButton(onClick = it) { Text("다시 생성", fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    if (value.isBlank()) return
    Spacer(Modifier.height(8.dp))
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(2.dp))
    Text(value, fontSize = 13.sp)
}
