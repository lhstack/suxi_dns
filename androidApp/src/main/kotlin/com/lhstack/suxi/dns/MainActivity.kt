package com.lhstack.suxi.dns

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lhstack.suxi.dns.ui.DnsConfigScreen
import com.lhstack.suxi.dns.ui.DnsConfigViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: DnsConfigViewModel by viewModels()
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startVpn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DnsConfigScreen(
                        viewModel = viewModel,
                        onStartRequested = ::requestVpnStart,
                    )
                }
            }
        }
    }

    private fun requestVpnStart() {
        val permissionIntent: Intent? = viewModel.createVpnPermissionIntent()
        if (permissionIntent == null) {
            viewModel.startVpn()
        } else {
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }
}
