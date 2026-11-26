package com.nightcinder.helix.core.model

import com.nightcinder.helix.BuildConfig

/**
 * Per-deployment values baked in at build time from `HELIX_*` environment variables or Gradle properties
 * (see docs/configuration.md). Every value is optional: blank means "not configured", and the server URLs
 * can also be entered on the sign-in screen at runtime.
 */
object SiteConfig {
    const val AUTHENTIK_URL: String = BuildConfig.HELIX_AUTHENTIK_URL
    const val TERMIX_URL: String = BuildConfig.HELIX_TERMIX_URL
    const val MOONLIGHT_URL: String = BuildConfig.HELIX_MOONLIGHT_URL
    const val PROMETHEUS_URL: String = BuildConfig.HELIX_PROMETHEUS_URL
    const val GRAFANA_URL: String = BuildConfig.HELIX_GRAFANA_URL
    const val JELLYFIN_URL: String = BuildConfig.HELIX_JELLYFIN_URL

    /** IP of the default Sunshine/Vibepollo stream host. */
    const val STREAM_HOST: String = BuildConfig.HELIX_STREAM_HOST

    /** Termix host name to highlight as the recommended stream target. */
    const val STREAM_HOST_NAME: String = BuildConfig.HELIX_STREAM_HOST_NAME

    /** Termix host name and MAC for the dashboard's quick Wake-on-LAN action. */
    const val WOL_HOST: String = BuildConfig.HELIX_WOL_HOST
    const val WOL_MAC: String = BuildConfig.HELIX_WOL_MAC

    /** Termix host whose Diun container logs feed the alert inbox. Blank disables the Diun source. */
    const val DIUN_HOST: String = BuildConfig.HELIX_DIUN_HOST

    /**
     * Host alias table for matching Prometheus instances, Uptime Kuma monitors and Grafana alert labels to
     * Termix hosts. Format: `host=alias,alias;host2=alias` (see [HostAliasTable.parse]).
     */
    const val HOST_ALIASES: String = BuildConfig.HELIX_HOST_ALIASES

    // Effective host-name settings: the build-time values above, swappable at runtime (and by tests).
    @Volatile var streamHostName: String = STREAM_HOST_NAME
    @Volatile var wolHost: String = WOL_HOST
    @Volatile var wolMac: String = WOL_MAC
    @Volatile var diunHost: String = DIUN_HOST

    /** True when any of [names] (Termix host name, display name or hostname) refers to [streamHostName]. */
    fun isRecommendedStreamHost(vararg names: String?): Boolean {
        val target = streamHostName
        return target.isNotBlank() && names.any { it?.contains(target, ignoreCase = true) == true }
    }
}

/**
 * Canonical host keys and their aliases. Each alias may be a full host name or IP, a short name, a token
 * inside a longer name (`node-exporter-myhost`), or, when it contains `-`, a prefix/suffix of a name.
 */
data class HostAliasTable(val aliasesByHost: Map<String, List<String>>) {
    val canonicalHosts: Set<String> get() = aliasesByHost.keys

    /** The first configured host; used when a monitor cannot be attributed to any host. */
    val fallbackHost: String? get() = aliasesByHost.keys.firstOrNull()

    companion object {
        val EMPTY = HostAliasTable(emptyMap())

        /** Parses `host=alias,alias;host2=alias2`. Whitespace is ignored; names are lowercased. */
        fun parse(spec: String): HostAliasTable {
            val map = linkedMapOf<String, List<String>>()
            for (entry in spec.split(';')) {
                val host = entry.substringBefore('=').trim().lowercase()
                if (host.isEmpty()) continue
                val aliases = entry.substringAfter('=', "")
                    .split(',')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && it != host }
                map[host] = (map[host].orEmpty() + aliases).distinct()
            }
            return HostAliasTable(map)
        }
    }
}
