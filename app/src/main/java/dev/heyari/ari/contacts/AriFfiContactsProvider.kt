package dev.heyari.ari.contacts

import uniffi.ari_ffi.FfiContact
import uniffi.ari_ffi.FfiContactChannel
import uniffi.ari_ffi.FfiContactsProvider
import javax.inject.Inject
import javax.inject.Singleton

/** Bridges the FFI contacts contract to [ContactsProvider]. */
@Singleton
class AriFfiContactsProvider @Inject constructor(
    private val contacts: ContactsProvider,
) : FfiContactsProvider {
    override fun `hasPermission`(): Boolean = contacts.hasPermission()

    override fun `lookup`(`query`: String): List<FfiContact> =
        contacts.lookup(query).map { m ->
            FfiContact(
                displayName = m.displayName,
                channels = m.channels.map { FfiContactChannel(service = it.service, id = it.id) },
            )
        }
}
