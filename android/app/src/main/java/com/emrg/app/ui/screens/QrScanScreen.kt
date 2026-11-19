package com.emrg.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Scan the QR code printed by `emrg-gateway pair` and hand its payload
 * back to PairScreen through the saved state handle, which prefills
 * host/port/fingerprint/code. The payload is the gateway's own pairing
 * JSON (type "emrg-pair"); anything else is refused with an error.
 * The camera is only used for this scan; nothing is recorded. */
@Composable
fun QrScanScreen(navController: NavHostController) {
    var error by remember { mutableStateOf<String?>(null) }
    var asked by remember { mutableStateOf(false) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            error = "Không quét được mã — thử lại."
            return@rememberLauncherForActivityResult
        }
        navController.previousBackStackEntry?.savedStateHandle?.set("qr_pair", contents)
        navController.popBackStack()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(ScanOptions().setPrompt("Quét mã QR ghép nối EMRG"))
        } else {
            error = "Cần quyền camera để quét mã QR."
        }
    }
    // Ask for the camera once, then open the scanner.
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Quét mã QR") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            Text(text = "Mở Termux, chạy `emrg-gateway pair` rồi quét mã QR nó in ra. Mã chỉ dùng được một lần, trong 5 phút.")
            Button(onClick = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }) {
                Text(text = "Quét lại")
            }
        }
    }
}
