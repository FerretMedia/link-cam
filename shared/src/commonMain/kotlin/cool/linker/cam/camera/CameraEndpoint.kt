package cool.linker.cam.camera

/**
 * Where a camera is and which of Canon's two remote protocols reaches it.
 * Saved between launches as [address].
 */
sealed interface CameraEndpoint {
    val host: String
    val address: String

    /** The "Remote control (EOS Utility)" mode, over PTP/IP. Needs no activation. */
    data class Eos(override val host: String) : CameraEndpoint {
        override val address: String get() = "$EOS_PREFIX$host"
    }

    /** Camera Control API, once Canon has activated it on the body. */
    data class ControlApi(val base: String) : CameraEndpoint {
        override val host: String get() = base.substringAfter("://").substringBefore('/').substringBefore(':')
        override val address: String get() = base
    }

    companion object {
        private const val EOS_PREFIX = "eos:"

        /** Reads back an [address]. Anything else is null; see [typedHost] for what people type. */
        fun saved(address: String): CameraEndpoint? = when {
            address.startsWith(EOS_PREFIX) -> address.removePrefix(EOS_PREFIX).takeIf { it.isNotBlank() }?.let(::Eos)
            address.startsWith("http") -> Ccapi.baseUrl(address)?.let(::ControlApi)
            else -> null
        }

        /**
         * The host in what someone typed, whether "192.168.1.2" or the
         * `http://192.168.1.2:8080/ccapi` a camera shows.
         */
        fun typedHost(typed: String): String? =
            typed.trim().substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { null }

        /** Whether what someone typed can only mean Camera Control API. */
        fun typedIsCcapi(typed: String): Boolean = typed.contains("/ccapi") || typed.contains(":${Ccapi.DEFAULT_PORT}")
    }
}
