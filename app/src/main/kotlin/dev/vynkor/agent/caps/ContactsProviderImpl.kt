package dev.vynkor.agent.caps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dev.vynkor.agent.Contact
import dev.vynkor.agent.ContactsProvider

/**
 * Contact lookup via ContactsContract, filtered by query.
 *
 * R-05: the host may ask for a row limit; anything outside 1..[CONTACTS_LIMIT]
 * resolves to [CONTACTS_LIMIT], which is also the hard SQL ceiling so a full
 * address book can never blow the frame payload budget. R-10: the permission
 * is checked on every [list] call, not cached at construction — a grant made
 * after service start works without restarting.
 */
class ContactsProviderImpl(context: Context) : ContactsProvider {
    private val ctx = context.applicationContext

    override fun list(query: String, limit: UInt): List<Contact> {
        if (!isGranted()) return emptyList()
        val effectiveLimit = if (limit in 1u..CONTACTS_LIMIT) limit else CONTACTS_LIMIT
        val result = mutableListOf<Contact>()
        val selection = if (query.isNotBlank()) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        } else {
            null
        }
        val args = if (query.isNotBlank()) arrayOf("%$query%") else null
        val sortOrder =
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT $effectiveLimit"
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            selection,
            args,
            sortOrder,
        ) ?: return result
        cursor.use {
            val nameIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val seen = HashSet<Pair<String, String>>()
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: ""
                val number = it.getString(numIdx) ?: ""
                if (seen.add(name to number)) {
                    result.add(Contact(name = name, phones = listOf(number), emails = emptyList()))
                }
            }
        }
        return result
    }

    private fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CONTACTS_LIMIT: UInt = 200u
    }
}
