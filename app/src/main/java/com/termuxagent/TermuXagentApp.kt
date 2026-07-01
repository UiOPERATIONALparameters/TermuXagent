package com.termuxagent

import android.app.Application
import com.termuxagent.data.agent.tools.AndroidContext
import com.termuxagent.data.workspace.WorkspaceManager

class TermuXagentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContext.bind(this)
        WorkspaceManager.init(this)
    }
}
