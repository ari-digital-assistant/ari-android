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
 * the built-in on-device assistant or no assistant — and only in English.
 * A cloud assistant (active, or chosen-but-not-yet-installed) does its own
 * NLU, so the router is redundant there.
 */
class RouterPolicyTest {

    private val builtin = EngineModule.BUILTIN_ASSISTANT_ID
    private val cloud = "com.openai.chatgpt"

    @Test
    fun onDeviceAssistantEnglish_required() {
        assertTrue(RouterPolicy.required(builtin, pendingCloudSetup = false, locale = "en"))
    }

    @Test
    fun noAssistantEnglish_required() {
        assertTrue(RouterPolicy.required(null, pendingCloudSetup = false, locale = "en"))
    }

    @Test
    fun cloudAssistantEnglish_notRequired() {
        assertFalse(RouterPolicy.required(cloud, pendingCloudSetup = false, locale = "en"))
    }

    @Test
    fun pendingCloudSetupEnglish_notRequired() {
        // Chose Cloud during onboarding but hasn't installed one yet —
        // activeAssistantId may still be null/builtin, but intent is cloud.
        assertFalse(RouterPolicy.required(null, pendingCloudSetup = true, locale = "en"))
        assertFalse(RouterPolicy.required(builtin, pendingCloudSetup = true, locale = "en"))
    }

    @Test
    fun onDeviceAssistantNonEnglish_notRequired() {
        // FunctionGemma is English-only.
        assertFalse(RouterPolicy.required(builtin, pendingCloudSetup = false, locale = "it"))
    }

    @Test
    fun noAssistantNonEnglish_notRequired() {
        assertFalse(RouterPolicy.required(null, pendingCloudSetup = false, locale = "it"))
    }
}
