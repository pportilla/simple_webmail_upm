package io.github.pportilla.upmmail.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import io.github.pportilla.upmmail.data.repository.DeviceContactsSource
import io.github.pportilla.upmmail.model.EmailAddress

/** E-mail addresses from the device address book, only while READ_CONTACTS is granted. */
class AndroidDeviceContacts(private val context: Context) : DeviceContactsSource {

    override fun search(query: String, limit: Int): List<EmailAddress> {
        if (query.isBlank() || limit <= 0) return emptyList()
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        val projection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS, DISPLAY_NAME)
        val result = ArrayList<EmailAddress>()
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val addressIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                val nameIndex = cursor.getColumnIndex(DISPLAY_NAME)
                while (cursor.moveToNext() && result.size < limit) {
                    val address = if (addressIndex >= 0) cursor.getString(addressIndex)?.trim() else null
                    if (address.isNullOrEmpty() || !address.contains('@')) continue
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex)?.trim() else null
                    result += EmailAddress(address, name?.takeIf { it.isNotEmpty() && !it.equals(address, ignoreCase = true) })
                }
            }
        } catch (e: Exception) {
            // Permission revoked meanwhile, or a broken contacts provider: just no suggestions.
            Log.w(TAG, "Device contacts unavailable", e)
        }
        return result.distinctBy { it.address.lowercase() }
    }

    private companion object {
        const val TAG = "AndroidDeviceContacts"

        /** ContactsContract.ContactsColumns.DISPLAY_NAME_PRIMARY (not reachable from Kotlin here). */
        const val DISPLAY_NAME = "display_name"
    }
}
