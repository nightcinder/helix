package com.nightcinder.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SftpEntryLinkPathTest {

    private fun link(path: String, target: String?) =
        SftpEntry(name = path.substringAfterLast('/'), path = path, isSymlink = true, linkTarget = target)

    @Test
    fun relativeTarget_resolvesAgainstParent() {
        assertEquals("/usr/bin", link("/bin", "usr/bin").resolvedLinkPath("/"))
        assertEquals("/srv/data", link("/srv/current", "data").resolvedLinkPath("/srv"))
    }

    @Test
    fun absoluteTarget_isUsedAsIs() {
        assertEquals("/mnt/pool/data", link("/srv/data", "/mnt/pool/data").resolvedLinkPath("/srv"))
    }

    @Test
    fun dotAndDotDotSegments_areCollapsed() {
        assertEquals("/srv/shared", link("/srv/app/shared", "../shared").resolvedLinkPath("/srv/app"))
        assertEquals("/srv/x", link("/srv/l", "./x/").resolvedLinkPath("/srv/"))
        assertEquals("/", link("/a", "../../..").resolvedLinkPath("/a"))
    }

    @Test
    fun missingTarget_fallsBackToLinkPath() {
        assertEquals("/srv/link", link("/srv/link", null).resolvedLinkPath("/srv"))
        assertEquals("/srv/link", link("/srv/link", "  ").resolvedLinkPath("/srv"))
    }
}
