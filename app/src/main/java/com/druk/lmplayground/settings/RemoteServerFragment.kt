package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.theme.PlaygroundTheme

class RemoteServerFragment : Fragment() {

    private val viewModel: RemoteServerViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val serverName by viewModel.serverName.observeAsState("")
                val serverUrl by viewModel.serverUrl.observeAsState("")
                val apiKey by viewModel.apiKey.observeAsState("")
                val enabled by viewModel.enabled.observeAsState(false)
                val scanning by viewModel.scanning.observeAsState(false)
                val foundServers by viewModel.foundServers.observeAsState(emptyList())
                RemoteServerScreen(
                    serverName = serverName,
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    enabled = enabled,
                    scanning = scanning,
                    foundServers = foundServers,
                    onNameChange = { viewModel.setServerName(it) },
                    onUrlChange = { viewModel.setServerUrl(it) },
                    onApiKeyChange = { viewModel.setApiKey(it) },
                    onEnabledChange = { viewModel.setEnabled(it) },
                    onScan = { viewModel.scan() },
                    onUseServer = { viewModel.useServer(it) },
                    onBackClick = { findNavController().popBackStack() },
                )
            }
        }
    }
}
