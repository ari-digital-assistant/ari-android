package dev.heyari.ari.router

import dev.heyari.ari.di.EngineModule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RouterPolicy.required], the pure decision behind
 * FunctionGemma's auto-management.
 *
 * The router is wanted exactly when Ari must understand commands itself —
 * the built-in on-device assistant or no assistant. A cloud assistant
 * (active, or chosen-but-not-yet-installed) does its own NLU, so the router
 * is redundant there.
 *
 * Locale is deliberately absent. Every locale can have a router now; which
 * ones actually do is a network question owned by [RouterAvailability] and
 * applied in `requiredFromState`, not a compile-time fact about English.
 */
class RouterPolicyTest {

    private val builtin = EngineModule.BUILTIN_ASSISTANT_ID
    private val cloud = "com.openai.chatgpt"

    @Test
    fun onDeviceAssistant_required() {
        assertTrue(RouterPolicy.required(builtin, pendingCloudSetup = false))
    }

    @Test
    fun noAssistant_required() {
        assertTrue(RouterPolicy.required(null, pendingCloudSetup = false))
    }

    @Test
    fun cloudAssistant_notRequired() {
        assertFalse(RouterPolicy.required(cloud, pendingCloudSetup = false))
    }

    @Test
    fun pendingCloudSetup_notRequired() {
        // Chose Cloud during onboarding but hasn't installed one yet —
        // activeAssistantId may still be null/builtin, but intent is cloud.
        assertFalse(RouterPolicy.required(null, pendingCloudSetup = true))
        assertFalse(RouterPolicy.required(builtin, pendingCloudSetup = true))
    }
}
