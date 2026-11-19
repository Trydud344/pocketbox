package dev.pocketbox.runtime.install

/**
 * Validated PRoot bind-mount specification (`-b hostPath:guestPath`).
 * Rejects colons because PRoot's `-b` parser splits on the first `:`.
 */
data class BindMount(
    val hostPath: String,
    val guestPath: String,
) {
    init {
        require(hostPath.startsWith("/")) { "hostPath must be absolute: $hostPath" }
        require(guestPath.startsWith("/")) { "guestPath must be absolute: $guestPath" }
        require(':' !in hostPath) { "hostPath must not contain ':': $hostPath" }
        require(':' !in guestPath) { "guestPath must not contain ':': $guestPath" }
    }

    fun toProotArg(): String = "$hostPath:$guestPath"
}
