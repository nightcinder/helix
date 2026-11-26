package com.nightcinder.helix

import com.nightcinder.helix.core.model.SiteConfig
import com.nightcinder.helix.monitoring.HostKeyMatcher

/** Example deployment used by tests (documentation addresses only), applied in place of the HELIX_* build values. */
object TestSite {
    const val HOST_ALIASES =
        "core=docker-host,dockerhost,192.0.2.10,nvidia-gpu,nvidia-gpu-exporter;" +
            "desktop=192.0.2.20;" +
            "server1=vcenter,esxi,esxi-poller,192.0.2.30;" +
            "workstation=192.0.2.40"
    const val WOL_MAC = "02:00:00:00:00:01"

    fun apply() {
        HostKeyMatcher.configure(HOST_ALIASES)
        SiteConfig.streamHostName = "desktop"
        SiteConfig.wolHost = "workstation"
        SiteConfig.wolMac = WOL_MAC
        SiteConfig.diunHost = "core"
    }
}
