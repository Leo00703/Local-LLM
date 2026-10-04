package com.druk.lmplayground.tools

/**
 * Which tools start on, and what the "Enable all" switch touches. Pure, so the rules are
 * unit-tested; the Android permission check is passed in by the caller.
 */
object ToolDefaults {

    /** Name of the Location tool, the only one that needs a runtime permission. */
    const val LOCATION = "location"

    /** Prefix of a remote model's `filename` (see ConversationViewModel.loadRemoteModel). */
    const val REMOTE_PREFIX = "remote:"

    fun isRemote(filename: String): Boolean = filename.startsWith(REMOTE_PREFIX)

    /**
     * A tool's enablement when no choice is saved for the model. A model on a remote server
     * starts with every tool on, because the user picked that server to work with them;
     * Location only when its permission is already held, since a tool that cannot work is
     * worse than one that is off. Any other model follows the global default.
     */
    fun defaultFor(filename: String, toolName: String, globalDefault: Boolean, locationGranted: Boolean): Boolean =
        if (isRemote(filename)) toolName != LOCATION || locationGranted else globalDefault

    /**
     * The tools the "Enable all" switch changes. Switching on leaves Location alone without
     * its permission (the switch does not pop a permission dialog); switching off touches all.
     */
    fun bulkTargets(names: List<String>, locationGranted: Boolean, enable: Boolean): List<String> =
        if (enable && !locationGranted) names.filter { it != LOCATION } else names

    /** Whether the "Enable all" switch reads as on: every tool it could switch on already is. */
    fun allOn(names: List<String>, states: Map<String, Boolean>, locationGranted: Boolean): Boolean {
        val targets = bulkTargets(names, locationGranted, enable = true)
        return targets.isNotEmpty() && targets.all { states[it] == true }
    }
}
