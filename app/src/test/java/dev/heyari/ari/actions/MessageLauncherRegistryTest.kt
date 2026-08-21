package dev.heyari.ari.actions

import dev.heyari.ari.messaging.MessagingService
import dev.heyari.ari.messaging.MessagingServices
import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLauncherRegistryTest {

    private val catalogue: Map<String, MessagingService> by lazy {
        MessagingServices.parse(File("src/main/assets/messaging-services.json").readText())
    }

    @Test
    fun whatsappEntryHasExpectedPackagesAndName() {
        val s = catalogue["whatsapp"]
        assertNotNull(s)
        assertEquals("WhatsApp", s!!.displayName)
        assertTrue(s.packages.contains("com.whatsapp"))
    }

    @Test
    fun discordIsAbsentBecauseItCannotResolveARecipient() {
        // Discord writes no contacts row for anybody — a spoken name can't be
        // turned into a Discord identity, so targeting it by name would only
        // promise something we can't deliver.
        assertNull(catalogue["discord"])
    }

    @Test
    fun everyEntryKeyMatchesItsServiceId() {
        catalogue.forEach { (key, service) ->
            assertEquals("registry key must match the service id", key, service.id)
        }
    }

    @Test
    fun aSchemeAddressedServiceNeedsNoPackageToTarget() {
        // Email goes to whichever client the user set as default, so there is
        // no package to check for and none to scope the intent to.
        val plan = MessageLauncher.plan(
            "email", catalogue, "gail@x.com", "hi",
        ) { false } as MessageLauncher.Plan.Templated
        assertNull(plan.pkg)
        assertEquals("mailto:gail@x.com?body=hi", plan.uris.single())
    }

    @Test
    fun aSchemeAddressedServiceWithNoRecipientFallsToTheChooser() {
        // Nothing to address and no app to scope to — the user picks both.
        assertEquals(
            MessageLauncher.Plan.Chooser,
            MessageLauncher.plan("email", catalogue, null, "hi") { true },
        )
    }

    @Test
    fun namedAndInstalledServiceIsTargeted() {
        val plan = MessageLauncher.plan("whatsapp", catalogue, null, "hi") { it == "com.whatsapp" }
        assertEquals(
            MessageLauncher.Plan.Targeted("com.whatsapp", "WhatsApp"),
            plan,
        )
    }

    @Test
    fun serviceIdIsMatchedCaseInsensitively() {
        val plan = MessageLauncher.plan("WhatsApp", catalogue, null, "hi") { true }
        assertTrue(plan is MessageLauncher.Plan.Targeted)
    }

    @Test
    fun namedButAbsentServiceReportsNotInstalled() {
        val plan = MessageLauncher.plan("telegram", catalogue, null, "hi") { false }
        assertEquals(MessageLauncher.Plan.NotInstalled("Telegram"), plan)
    }

    @Test
    fun firstInstalledPackageWins() {
        // Signal lists Molly as an alternative build; whichever is present.
        val plan = MessageLauncher.plan("signal", catalogue, null, "hi") { it == "im.molly.app" }
        assertEquals(
            MessageLauncher.Plan.Targeted("im.molly.app", "Signal"),
            plan,
        )
    }

    @Test
    fun unnamedServiceFallsToTheChooser() {
        assertEquals(MessageLauncher.Plan.Chooser, MessageLauncher.plan(null, catalogue, null, "hi") { true })
    }

    @Test
    fun unknownServiceFallsToTheChooserRatherThanFailing() {
        // A messenger we've never heard of — LINE, Zalo, KakaoTalk — must still
        // reach the user, one extra tap rather than not at all.
        assertEquals(MessageLauncher.Plan.Chooser, MessageLauncher.plan("kakaotalk", catalogue, null, "hi") { true })
    }

    @Test
    fun aResolvedRecipientGetsTheChatOpenedDirectly() {
        val plan = MessageLauncher.plan(
            "whatsapp", catalogue, "35699000000", "I%27ll%20be%20home",
        ) { it == "com.whatsapp" }
        val templated = plan as MessageLauncher.Plan.Templated
        assertEquals("com.whatsapp", templated.pkg)
        assertEquals(
            "whatsapp://send?phone=35699000000&text=I%27ll%20be%20home",
            templated.uris.first(),
        )
        assertTrue(
            "the https form must come after the scheme — it falls through to a " +
                "browser install page when the app is absent",
            templated.uris.last().startsWith("https://wa.me/"),
        )
    }

    @Test
    fun telegramTemplatesOnItsOwnScheme() {
        val plan = MessageLauncher.plan(
            "telegram", catalogue, "35699000000", "hi",
        ) { true } as MessageLauncher.Plan.Templated
        assertEquals("tg://resolve?phone=35699000000&text=hi", plan.uris.single())
    }

    @Test
    fun noResolvedRecipientMeansNoTemplate() {
        // Without an id there is nobody to address, so the share intent takes
        // over and the user picks the name.
        val plan = MessageLauncher.plan("whatsapp", catalogue, null, "hi") { true }
        assertTrue(plan is MessageLauncher.Plan.Targeted)
    }

    @Test
    fun aServiceWithNoTemplateStaysOnTheSharePath() {
        val plan = MessageLauncher.plan("messenger", catalogue, "someone", "hi") { true }
        assertTrue(plan is MessageLauncher.Plan.Targeted)
    }

    @Test
    fun telegramIdStripsThePlusBecauseBothSchemesWantBareDigits() {
        assertEquals("35699000000", catalogue["telegram"]!!.extractId("Message +35699000000"))
    }
}