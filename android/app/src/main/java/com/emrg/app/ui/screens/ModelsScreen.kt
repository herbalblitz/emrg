package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.emrg.app.model.ModelInfo
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __models = remember { mutableStateOf(emptyList<ModelInfo>()) }
    var models: List<ModelInfo> by __models
    val __current = remember { mutableStateOf("") }
    var current: String by __current
    val __loading = remember { mutableStateOf(true) }
    var loading: Boolean by __loading
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? by __error

    LaunchedEffect(Unit) {
        scope.launch {
            loading = true
            try {
                val (list, cur) = repository.models()
                models = list
                current = cur
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Models") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            error?.let { e ->
                androidx.compose.material3.Text(
                    text = e,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            if (!loading && error == null) {
                Text(
                    text = "Current model: $current",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = padding,
                ) {
                    items(models, key = { it.name }) { model ->
                        androidx.compose.material3.Text(
                            text =
                                "${model.name} (${model.contextWindow} ctx, vision=${model.vision})",
                        )
                    }
                }
            }
        }
    }
}
