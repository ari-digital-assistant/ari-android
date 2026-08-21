package dev.heyari.ari.messaging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped catalogue is data a contributor edits without touching Kotlin,
 * so it gets tested like data: parsed here from the real asset, not a fixture.
 */
class MessagingServicesTest {

    private val catalogue: Map<String, MessagingService> by lazy {
        MessagingServices.parse(File("src/main/assets/messaging-services.json").readText())
    }

    @Test
    fun theShippedCatalogueParses() {
        assertTrue("catalogue is empty — did the asset move?", catalogue.isNotEmpty())
    }

    @Test
    fun everyEntryKeyMatchesItsId() {
        catalogue.forEach { (key, s) -> assertEquals(key, s.id) }
    }

    @Test
    fun atMostOneServiceClaimsEachStandardContactField() {
        // Two claiming phone numbers would make which one a contact's number
        // is attributed to depend on map ordering.
        MessagingService.ContactSource.entries.forEach { source ->
            assertTrue(
                "more than one service claims $source",
                catalogue.values.count { it.contactSource == source } <= 1,
            )
        }
    }

    @Test
    fun smsTakesThePhoneNumberAndEmailTakesTheAddress() {
        assertEquals(MessagingService.ContactSource.PHONE, catalogue["sms"]!!.contactSource)
        assertEquals(MessagingService.ContactSource.EMAIL, catalogue["email"]!!.contactSource)
    }

    @Test
    fun emailIsAddressedBySchemeRatherThanByApp() {
        // mailto: goes to whichever client the user set as default, so there
        // is no package to target — and it needs SENDTO, not VIEW.
        val email = catalogue["email"]!!
        assertTrue(email.packages.isEmpty())
        assertEquals(MessagingService.IntentAction.SENDTO, email.intentAction)
        assertEquals("mailto:gail@x.com?body=hi", email.chatUris("gail@x.com", "hi").single())
    }

    @Test
    fun everyOtherServiceStillDeclaresPackages() {
        catalogue.values.filter { it.intentAction == MessagingService.IntentAction.VIEW }
            .forEach { assertTrue("${it.id} has no package", it.packages.isNotEmpty()) }
    }

    @Test
    fun mimetypesAreUniqueAcrossServices() {
        val mimetypes = catalogue.values.mapNotNull { it.contactMimetype }
        assertEquals("two services claim the same contacts row", mimetypes.distinct(), mimetypes)
    }

    @Test
    fun everyMimetypeLooksLikeAContactsDataRow() {
        catalogue.values.mapNotNull { it.contactMimetype }.forEach {
            assertTrue("$it is not a contacts data mimetype", it.startsWith("vnd.android.cursor.item/"))
        }
    }

    @Test
    fun whatsappJidLosesItsServerSuffix() {
        assertEquals("35699000000", catalogue["whatsapp"]!!.extractId("35699000000@s.whatsapp.net"))
    }

    @Test
    fun telegramRowLosesItsMessagePrefix() {
        // Telegram stores a display string, not a bare id: "Message +356…".
        // The + goes too: both tg:// and wa.me want bare digits.
        assertEquals("35699000000", catalogue["telegram"]!!.extractId("Message +35699000000"))
    }

    @Test
    fun eachServiceReadsItsOwnColumn() {
        // Reading the wrong column yields a plausible-looking wrong id.
        assertEquals(MessagingService.IdColumn.DATA1, catalogue["whatsapp"]!!.idColumn)
        assertEquals(MessagingService.IdColumn.DATA3, catalogue["telegram"]!!.idColumn)
    }

    @Test
    fun affixOnlyValuesYieldNothing() {
        assertNull(catalogue["telegram"]!!.extractId("Message +"))
        assertNull(catalogue["whatsapp"]!!.extractId("   "))
    }

    @Test
    fun aValueWithoutTheExpectedAffixIsPassedThrough() {
        assertEquals("35699000000", catalogue["whatsapp"]!!.extractId("35699000000"))
    }

    @Test
    fun discordIsAbsentBecauseItCannotResolveARecipient() {
        // Discord writes no contacts row for anybody, so a spoken name can
        // never be turned into a Discord identity.
        assertNull(catalogue["discord"])
    }

    @Test
    fun aMalformedEntryIsSkippedNotFatal() {
        // One bad contributed entry must not take the rest of the file down.
        val parsed = MessagingServices.parse(
            """{"services":[{"id":"broken"},{"id":"ok","display_name":"OK","packages":["a.b"]}]}"""
        )
        assertNull(parsed["broken"])
        assertNotNull(parsed["ok"])
    }

    @Test
    fun anUnreadableCatalogueParsesToEmptyRatherThanThrowing() {
        assertTrue(MessagingServices.parse("""{"version":1}""").isEmpty())
    }
}
