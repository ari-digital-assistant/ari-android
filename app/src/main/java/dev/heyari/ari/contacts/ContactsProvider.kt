package dev.heyari.ari.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.messaging.MessagingService
import dev.heyari.ari.messaging.MessagingServices
import javax.inject.Inject
import javax.inject.Singleton

/** One way to reach a person, as the engine's contacts capability sees it. */
data class Channel(val service: String, val id: String)

data class Match(val displayName: String, val channels: List<Channel>)

/**
 * Looks people up in the address book and reports which messaging services
 * they can be reached on.
 *
 * **Lookup only, deliberately.** There is no "list every contact" here and
 * there shouldn't be: a skill asks about a name the user already said aloud
 * and gets the matches. Handing a WASM sandbox the ability to walk the whole
 * address book is the difference between a messaging skill and a scraper.
 *
 * Reachability comes from each app's own row in `ContactsContract.Data`.
 * Messaging apps that sync contacts write a row under their own mimetype so
 * the contact card can show "Message on WhatsApp"; that row also carries the
 * identifier the app addresses the person by, already normalised. That beats
 * parsing `phone_v2` on two counts — the number is in international form, and
 * its presence proves the person is actually registered on that service.
 */
@Singleton
class ContactsProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val services: MessagingServices,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun lookup(query: String): List<Match> {
        if (query.isBlank() || !hasPermission()) return emptyList()
        val ids = contactIdsMatching(query)
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull { (id, name) ->
            val channels = channelsFor(id)
            if (channels.isEmpty()) null else Match(name, channels)
        }
    }

    /** Contact ids whose display name matches, capped — a name matching
     *  hundreds of people is a disambiguation failure, not a result set. */
    private fun contactIdsMatching(query: String): List<Pair<Long, String>> {
        val uri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI,
            Uri.encode(query.trim()),
        )
        val out = mutableListOf<Pair<Long, String>>()
        query(
            uri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null,
            null,
        ) { c ->
            while (c.moveToNext() && out.size < MAX_MATCHES) {
                val name = c.getString(1) ?: continue
                out += c.getLong(0) to name
            }
        }
        return out
    }

    private fun channelsFor(contactId: Long): List<Channel> {
        val channels = mutableListOf<Channel>()
        val byMimetype = services.byMimetype()
        if (byMimetype.isEmpty()) return standardFieldChannels(contactId)
        val mimetypes = byMimetype.keys.toTypedArray()
        val selection = buildString {
            append("${ContactsContract.Data.CONTACT_ID} = ? AND ")
            append(ContactsContract.Data.MIMETYPE)
            append(" IN (")
            append(mimetypes.joinToString(",") { "?" })
            append(")")
        }
        query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA3,
            ),
            selection,
            arrayOf(contactId.toString()) + mimetypes,
        ) { c ->
            while (c.moveToNext()) {
                val svc = byMimetype[c.getString(0)] ?: continue
                val raw = if (svc.idColumn == MessagingService.IdColumn.DATA3) {
                    c.getString(2)
                } else {
                    c.getString(1)
                }
                val id = svc.extractId(raw ?: continue) ?: continue
                if (channels.none { it.service == svc.id }) {
                    channels += Channel(svc.id, id)
                }
            }
        }
        return channels + standardFieldChannels(contactId)
    }

    /** Channels that come from a standard contact field rather than a
     *  service's own row — a phone number, an email address. */
    private fun standardFieldChannels(contactId: Long): List<Channel> =
        services.all().values.mapNotNull { s ->
            when (s.contactSource) {
                MessagingService.ContactSource.PHONE ->
                    phoneFor(contactId)?.let { Channel(s.id, it) }
                MessagingService.ContactSource.EMAIL ->
                    emailFor(contactId)?.let { Channel(s.id, it) }
                null -> null
            }
        }

    private fun emailFor(contactId: Long): String? {
        var address: String? = null
        query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
        ) { c ->
            if (c.moveToFirst()) address = c.getString(0)
        }
        return address?.takeIf { it.isNotBlank() }
    }

    private fun phoneFor(contactId: Long): String? {
        var number: String? = null
        query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
        ) { c ->
            if (c.moveToFirst()) {
                number = c.getString(0) ?: c.getString(1)
            }
        }
        return number?.takeIf { it.isNotBlank() }
    }

    private inline fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        body: (android.database.Cursor) -> Unit,
    ) {
        val resolver: ContentResolver = context.contentResolver
        try {
            resolver.query(uri, projection, selection, args, null)?.use(body)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the query, or scoped
            // away by the OS. Callers treat empty as "nobody by that name",
            // which is why hasPermission() is a separate question.
            Log.w(TAG, "contacts query refused", e)
        }
    }

    companion object {
        private const val TAG = "ContactsProvider"
        private const val MAX_MATCHES = 10
    }
}
