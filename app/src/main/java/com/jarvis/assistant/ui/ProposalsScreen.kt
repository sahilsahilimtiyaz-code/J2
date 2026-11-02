package com.jarvis.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.memory.Proposal
import com.jarvis.assistant.memory.ProposalType
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.theme.JarvisPalette

@Composable
fun ProposalsScreen(
    state: JarvisUiState,
    onSelectTab: (JarvisTab) -> Unit,
    onToggleMic: () -> Unit,
    onApproveProposal: (String) -> Unit,
    onDismissProposal: (String) -> Unit,
    onRunSelfReviewNow: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisPalette.Void),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(JarvisPalette.Amber.copy(alpha = 0.15f))
                            .border(1.dp, JarvisPalette.Amber.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        HudIcon(
                            glyph = HudGlyph.SHIELD,
                            color = JarvisPalette.Amber,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "J.A.R.V.I.S.",
                            style = MaterialTheme.typography.titleMedium,
                            color = JarvisPalette.Cyan,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "SELF-EVOLUTION PROPOSALS",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.TextDim,
                            letterSpacing = 1.sp,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisPalette.Amber.copy(alpha = 0.15f))
                        .border(1.dp, JarvisPalette.Amber.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable(onClick = onRunSelfReviewNow)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "RUN REVIEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.Amber,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (state.proposals.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "All systems operating at peak efficiency.",
                            style = MaterialTheme.typography.titleMedium,
                            color = JarvisPalette.Ice,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "No pending proposals. Tap 'RUN REVIEW' to trigger self-reflection pass.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisPalette.TextDim,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.proposals, key = { it.id }) { proposal ->
                        ProposalCard(
                            proposal = proposal,
                            onApprove = { onApproveProposal(proposal.id) },
                            onDismiss = { onDismissProposal(proposal.id) },
                        )
                    }
                }
            }
        }

        // Bottom Navigation Bar
        com.jarvis.assistant.ui.components.JarvisBottomBar(
            selected = JarvisTab.PROPOSALS,
            onSelect = onSelectTab,
            listening = state.listening,
            busy = state.generating,
            onMic = onToggleMic,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ProposalCard(
    proposal: Proposal,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val typeBadge = when (proposal.type) {
        ProposalType.PERSONA_PROMPT -> "PERSONA PROMPT" to JarvisPalette.Cyan
        ProposalType.NEW_SKILL -> "NEW SKILL" to JarvisPalette.Success
        ProposalType.SETTING_OPTIMIZATION -> "SETTING" to JarvisPalette.Amber
    }

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = typeBadge.second,
        corner = 16.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = proposal.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = JarvisPalette.Ice,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(typeBadge.second.copy(alpha = 0.15f))
                        .border(1.dp, typeBadge.second.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = typeBadge.first,
                        style = MaterialTheme.typography.labelSmall,
                        color = typeBadge.second,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = proposal.rationale,
                style = MaterialTheme.typography.bodySmall,
                color = JarvisPalette.TextDim,
            )

            Spacer(Modifier.height(12.dp))

            // Before / After comparison
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(JarvisPalette.Panel)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column {
                    Text(
                        text = "BEFORE:",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.Danger,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = proposal.beforeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = JarvisPalette.TextDim,
                    )
                }
                Column {
                    Text(
                        text = "PROPOSED UPGRADE:",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.Success,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = proposal.afterText,
                        style = MaterialTheme.typography.bodySmall,
                        color = JarvisPalette.Ice,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(JarvisPalette.Danger.copy(alpha = 0.12f))
                        .border(1.dp, JarvisPalette.Danger.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "DISMISS",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.Danger,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(JarvisPalette.Success.copy(alpha = 0.22f))
                        .border(1.dp, JarvisPalette.Success.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onApprove)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "APPROVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.Success,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
