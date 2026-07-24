package dev.heyari.ari.router

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router is English-only. FunctionGemma routes non-English confidently
 * but wrongly at 270M, so no other language should ever want a model on disk
 * — [RouterPolicy.routerSupportsLocale] is the gate every model decision
 * passes through before any download/availability check.
 */
class RouterPolicyTest {

    @Test
    fun englishIsSupported() {
        assertTrue(RouterPolicy.routerSupportsLocale("en"))
    }

    @Test
    fun italianIsNotSupported() {
        assertFalse(RouterPolicy.routerSupportsLocale("it"))
    }

    @Test
    fun otherLanguagesAreNotSupported() {
        assertFalse(RouterPolicy.routerSupportsLocale("es"))
        assertFalse(RouterPolicy.routerSupportsLocale("fr"))
        assertFalse(RouterPolicy.routerSupportsLocale("de"))
    }
}
