package com.termuxagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.termuxagent.ui.chat.ChatViewModel
import com.termuxagent.ui.settings.SettingsViewModel
import com.termuxagent.ui.terminal.TerminalViewModel
import com.termuxagent.ui.workspace.WorkspaceViewModel

/**
 * Reusable ViewModel factories. Each factory pulls the application context
 * from [AppContext] (bound by AppRoot at composition time) and hands it to
 * the ViewModel constructor.
 */
object ViewModelFactories {

    val chat: ViewModelProvider.Factory = viewModelFactory {
        initializer { ChatViewModel(AppContext.get()) }
    }

    val settings: ViewModelProvider.Factory = viewModelFactory {
        initializer { SettingsViewModel(AppContext.get()) }
    }

    val workspace: ViewModelProvider.Factory = viewModelFactory {
        initializer { WorkspaceViewModel(AppContext.get()) }
    }

    val terminal: ViewModelProvider.Factory = viewModelFactory {
        initializer { TerminalViewModel(AppContext.get()) }
    }
}
