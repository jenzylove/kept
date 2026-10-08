package app.kept.wallet

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MwaIdentityTest {
    @Test
    fun walletIdentityUsesRelativeIconUriRequiredByMwa() {
        val identity = URI(MWA_IDENTITY_URI)
        val icon = URI(MWA_ICON_RELATIVE_URI)

        assertEquals("https", identity.scheme)
        assertFalse("MWA rejects an absolute iconRelativeUri", icon.isAbsolute)
        assertEquals("favicon.ico", icon.toString())
    }
}
