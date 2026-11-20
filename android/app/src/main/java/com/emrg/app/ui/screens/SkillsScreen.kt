package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.model.SkillInfo
import kotlinx.coroutines.launch

@Composable
fun SkillsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __skills = remember { mutableStateOf(emptyList<SkillInfo>()) }
    var skills: List<SkillInfo> by __skills
    val __loading = remember { mutableStateOf(true) }
    var loading: Boolean by __loading
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? by __error

    LaunchedEffect(Unit) {
        scope.launch {
            loading = true
            try {
                skills = repository.skills()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Skills") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(
                    text = e,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            if (!loading && error == null) {
                Text(
                    text = "Available skills (${skills.size})",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = padding,
                ) {
                    items(skills, key = { it.name }) { skill ->
                        androidx.compose.material3.Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            androidx.compose.foundation.layout.Column(
                                modifier = androidx.compose.foundation.layout.Modifier.padding(16.dp),
                            ) {
                                Text(
                                    text = skill.name,
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = skill.description,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "source=${skill.source} path=${skill.path}",
                                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
