package dev.heyari.ari.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AriFfiSettingWriterTest {
    class FakeSecret { val w = mutableMapOf<Pair<String, String>, String>() }
    class FakePlain { val w = mutableMapOf<Pair<String, String>, String>() }
    class FakeMirror { val w = mutableMapOf<Pair<String, String>, String>() }

    private fun route(
        secret: FakeSecret,
        plain: FakePlain,
        mirror: FakeMirror,
        skillId: String,
        key: String,
        value: String,
        isSecret: Boolean,
    ): Boolean = AriFfiSettingWriter.route(
        skillId = skillId,
        key = key,
        value = value,
        isSecret = isSecret,
        persistSecret = { s, k, v -> secret.w[s to k] = v },
        persistPlain = { s, k, v -> plain.w[s to k] = v },
        updateMirror = { s, k, v -> mirror.w[s to k] = v },
    )

    @Test fun secret_value_goes_to_secret_store_and_mirror() {
        val secret = FakeSecret(); val plain = FakePlain(); val mirror = FakeMirror()
        val ok = route(secret, plain, mirror, "dev.heyari.homeassistant", "token", "abc", true)
        assertTrue(ok)
        assertEquals("abc", secret.w["dev.heyari.homeassistant" to "token"])
        assertEquals("abc", mirror.w["dev.heyari.homeassistant" to "token"])
        assertEquals(null, plain.w["dev.heyari.homeassistant" to "token"])
    }

    @Test fun plain_value_goes_to_datastore_and_mirror() {
        val secret = FakeSecret(); val plain = FakePlain(); val mirror = FakeMirror()
        val ok = route(secret, plain, mirror, "dev.heyari.homeassistant", "auth_mode", "oauth", false)
        assertTrue(ok)
        assertEquals("oauth", plain.w["dev.heyari.homeassistant" to "auth_mode"])
        assertEquals("oauth", mirror.w["dev.heyari.homeassistant" to "auth_mode"])
        assertEquals(null, secret.w["dev.heyari.homeassistant" to "auth_mode"])
    }
}
