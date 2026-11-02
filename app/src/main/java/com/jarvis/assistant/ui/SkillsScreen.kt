package com.jarvis.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.memory.SkillDefinition
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.theme.JarvisPalette

@Composable
fun SkillsScreen(
    state: JarvisUiState,
    onSelectTab: (JarvisTab) -> Unit,
    onToggleMic: () -> Unit,
    onRunSkill: (SkillDefinition) -> Unit,
    onDeleteSkill: (Long) -> Unit,
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
            // Screen Header
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
                            .background(JarvisPalette.Cyan.copy(alpha = 0.15f))
                            .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        HudIcon(
                            glyph = HudGlyph.BOLT,
                            color = JarvisPalette.Cyan,
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
                            text = "AUTOMATED MACRO SKILLS",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.TextDim,
                            letterSpacing = 1.sp,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisPalette.Cyan.copy(alpha = 0.12f))
                        .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "${state.skills.size} ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.Cyan,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            var searchQuery by remember { mutableStateOf("") }
            val filteredSkills = remember(state.skills, searchQuery) {
                if (searchQuery.isBlank()) {
                    state.skills
                } else {
                    val q = searchQuery.trim().lowercase()
                    state.skills.filter {
                        it.name.lowercase().contains(q) ||
                            it.description.lowercase().contains(q) ||
                            it.triggers.any { t -> t.lowercase().contains(q) }
                    }
                }
            }

            // Search Filter Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "Search macro skills or voice triggers...",
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisPalette.TextDim,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = JarvisPalette.Panel,
                        unfocusedContainerColor = JarvisPalette.Panel.copy(alpha = 0.5f),
                        focusedIndicatorColor = JarvisPalette.Cyan,
                        unfocusedIndicatorColor = JarvisPalette.Line,
                        focusedTextColor = JarvisPalette.Ice,
                        unfocusedTextColor = JarvisPalette.Ice,
                    ),
                )
            }

            if (filteredSkills.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) {
                            "No automated skills found. Say 'Jarvis, save a morning routine skill' to configure one."
                        } else {
                            "No skills match \"$searchQuery\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = JarvisPalette.TextDim,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(filteredSkills, key = { it.id }) { skill ->
                        SkillCard(
                            skill = skill,
                            onRun = { onRunSkill(skill) },
                            onDelete = { onDeleteSkill(skill.id) },
                        )
                    }
                }
            }
        }

        // Bottom Navigation Bar
        com.jarvis.assistant.ui.components.JarvisBottomBar(
            selected = JarvisTab.SKILLS,
            onSelect = onSelectTab,
            listening = state.listening,
            busy = state.generating,
            onMic = onToggleMic,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillCard(
    skill: SkillDefinition,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = JarvisPalette.Cyan,
        corner = 16.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = JarvisPalette.Ice,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (skill.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisPalette.TextDim,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Run button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(JarvisPalette.Success.copy(alpha = 0.2f))
                            .border(1.dp, JarvisPalette.Success.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onRun)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "RUN",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.Success,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Delete button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(JarvisPalette.Danger.copy(alpha = 0.15f))
                            .border(1.dp, JarvisPalette.Danger.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onDelete)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "DEL",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.Danger,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Trigger chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                skill.triggers.forEach { trigger ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(JarvisPalette.Panel)
                            .border(1.dp, JarvisPalette.Line, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "\"$trigger\"",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.Cyan,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Steps overview toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${skill.steps.size} step(s) in sequence",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.TextDim,
                )
                Text(
                    text = if (expanded) "Hide details ▲" else "Show details ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.Cyan,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    skill.steps.forEachIndexed { idx, step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(JarvisPalette.Panel.copy(alpha = 0.5f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${idx + 1}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisPalette.Cyan,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = step.toolName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = JarvisPalette.Ice,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (step.arguments.isNotEmpty()) {
                                    Text(
                                        text = step.arguments.entries.joinToString(", ") { "${it.key}: ${it.value}" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = JarvisPalette.TextDim,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
