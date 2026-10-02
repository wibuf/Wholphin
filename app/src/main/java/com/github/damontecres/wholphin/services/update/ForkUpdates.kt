package com.github.damontecres.wholphin.services.update

/** Upstream's release feed, the default update URL for everything but personal fork builds */
const val UPSTREAM_UPDATE_URL = "https://api.github.com/repos/damontecres/Wholphin/releases/latest"

// Fork releases on GitHub, named for the branch that built them
private const val FORK_GITHUB_RELEASES = "https://api.github.com/repos/wibuf/Wholphin/releases/"

/**
 * The update URL a fork install should switch to, or null to keep the one it has
 *
 * Installs from before the fork had its own feed stored either upstream's default, which would
 * offer the official app as an "update", or a GitHub release for one particular branch, which goes
 * quiet as soon as work moves to another branch. Any other URL was set on purpose and is kept.
 */
fun forkUpdateUrlMigration(
    stored: String,
    forkDefault: String,
): String? {
    val outdated =
        stored.isBlank() ||
            stored == UPSTREAM_UPDATE_URL ||
            stored.startsWith(FORK_GITHUB_RELEASES)
    return forkDefault.takeIf { outdated && stored != it }
}

enum class InstallAction {
    /** Install now without asking */
    SILENT,

    /** Show the system install prompt now */
    PROMPT,

    /** Leave it for a better moment */
    WAIT,
}

/**
 * Decide whether a downloaded update should be installed now, and how
 *
 * Installing restarts the app, so nothing is ever installed over something playing. Where the
 * device allows it the update goes in silently, but only once the app is off screen, so it never
 * pulls the app out from under someone. Older devices cannot install without asking, so they are
 * asked when the app is on screen, at most once per [promptIntervalMs] so a "not now" sticks.
 *
 * @param silentPossible the device can install without asking, ie Android 12 or newer, and the
 * last silent attempt did not turn out to need the user after all
 * @param inForeground whether the app is on screen
 * @param busy whether something is playing that installing would cut off
 * @param sinceLastPromptMs how long ago the user was last asked, or null if never
 */
fun decideInstall(
    silentPossible: Boolean,
    inForeground: Boolean,
    busy: Boolean,
    sinceLastPromptMs: Long?,
    promptIntervalMs: Long,
): InstallAction =
    when {
        busy -> InstallAction.WAIT
        silentPossible -> if (inForeground) InstallAction.WAIT else InstallAction.SILENT
        !inForeground -> InstallAction.WAIT
        sinceLastPromptMs == null || sinceLastPromptMs >= promptIntervalMs -> InstallAction.PROMPT
        else -> InstallAction.WAIT
    }
