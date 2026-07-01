package com.termuxagent.ui

import android.content.Context

/**
 * Holder for the application context bound by [com.termuxagent.ui.nav.AppRoot]
 * at composition time. Used by ViewModels to reach DataStore + the workspace.
 *
 * This is intentionally simple — bound once per composition, unbound on leave.
 * Avoids the verbosity of passing a factory to every `viewModel(...)` call.
 */
object AppContext {
    @Volatile private var ctx: Context? = null
    fun bind(context: Context) { ctx = context.applicationContext }
    fun get(): Context = ctx ?: error("AppContext not bound — AppRoot must call AppContext.bind(context) in its composition.")
    fun unbind() { ctx = null }
}
