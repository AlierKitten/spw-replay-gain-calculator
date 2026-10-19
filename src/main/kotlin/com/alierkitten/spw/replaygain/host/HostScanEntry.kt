package com.alierkitten.spw.replaygain.host

/**
 * Describes a single host-side candidate entry point for the library scan / refresh action.
 *
 * Salt Player does not expose a public "refresh library" API through
 * [com.xuncorp.spw.workshop.api.WorkshopApi]; the upstream issue
 * https://github.com/Moriafly/spw-workshop-api/issues/28 (status: open as of 0.1.1-dev23)
 * only hints that "refresh + change notifications may be added". Until then, this plugin
 * falls back to reflection against the host's own classpath.
 *
 * Each entry is intentionally narrow: a single Kotlin object / companion / static method.
 * When the host upgrades and the internals move, only this file needs to be updated.
 */
internal sealed interface Receiver {
    /** Static method on a regular class (no receiver). */
    data object Static : Receiver

    /** Kotlin `object` singleton accessed via the `INSTANCE` field. */
    data class SingletonField(val fieldName: String = "INSTANCE") : Receiver

    /** Kotlin `class` companion object accessed via the `Companion` field. */
    data class CompanionField(val fieldName: String = "Companion") : Receiver
}

internal data class HostScanEntry(
    val className: String,
    val methodName: String,
    val parameterTypes: List<String> = emptyList(),
    val receiver: Receiver,
    /**
     * Human-readable rationale for why this entry is plausible. Logged when the call
     * fails so the next maintainer can decide whether to retire the candidate.
     */
    val rationale: String,
)

/**
 * Candidate entry points discovered by `tools/inspect-host.ps1` against
 * Salt Player for Windows 1.19.0. Listed in priority order: the first entry whose
 * class loads and method resolves is the one invoked. Add future entries below; never
 * remove an existing one without an explicit host-version bump.
 */
internal object HostScanCandidates {

    /**
     * `com.xuncorp.voxzen.service.RepoService.rebuildMusicLibrary()`
     *
     * Verified on SPW 1.19.0 via `javap`:
     * ```
     * public final class com.xuncorp.voxzen.service.RepoService extends androidx.compose.ui.?
     *   public static final RepoService INSTANCE;
     *   public final void rebuildMusicLibrary();
     * ```
     * Coroutine work is launched internally; the call returns immediately, so we do not
     * wait for the scan to finish (single-round flow).
     */
    val Primary = HostScanEntry(
        className = "com.xuncorp.voxzen.service.RepoService",
        methodName = "rebuildMusicLibrary",
        parameterTypes = emptyList(),
        receiver = Receiver.SingletonField(),
        rationale = "SPW 1.19.0: RepoService.INSTANCE.rebuildMusicLibrary() 是「音乐库 → 刷新音乐库」对应的内部入口",
    )
}