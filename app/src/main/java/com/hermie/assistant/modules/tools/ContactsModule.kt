package com.hermie.assistant.modules.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.hermie.assistant.modules.*

/**
 * Tool module for searching the user's contacts.
 * Read-only — we don't create or modify contacts.
 */
class ContactsModule : HermieModule, ToolModule {

    override val id = "contacts"
    override val displayName = "Contacts"
    override val description = "Search contacts for names and phone numbers"
    override val iconName = "contacts"
    override var isActive: Boolean = false
        private set

    override val requiredPermissions = listOf(Manifest.permission.READ_CONTACTS)

    private var context: Context? = null

    override suspend fun initialize(context: Context) {
        this.context = context
        isActive = hasPermission(context)
    }

    override suspend fun start() {
        isActive = context?.let { hasPermission(it) } ?: false
    }

    override suspend fun stop() { isActive = false }
    override fun release() { context = null }

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "contacts.search",
            description = "Search contacts by name. Returns name, phone, email.",
            parameters = mapOf(
                "query" to ToolParam("str", "Name or partial name to search for", required = true)
            )
        ),
        ToolDefinition(
            name = "contacts.get_phone",
            description = "Get phone number for a specific contact by name.",
            parameters = mapOf(
                "name" to ToolParam("str", "Exact or partial contact name", required = true)
            )
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        val ctx = context ?: return ToolResult.Error("Contacts module not initialized")
        if (!hasPermission(ctx)) {
            return ToolResult.Error("Contacts permission not granted.")
        }

        return when (name) {
            "contacts.search" -> searchContacts(ctx, params)
            "contacts.get_phone" -> getPhone(ctx, params)
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    private fun searchContacts(ctx: Context, params: Map<String, String>): ToolResult {
        val query = params["query"] ?: return ToolResult.Error("Missing query parameter")

        val contacts = findContacts(ctx, query, limit = 10)
        return if (contacts.isEmpty()) {
            ToolResult.Success("No contacts found matching '$query'.")
        } else {
            ToolResult.Success("Found ${contacts.size} contact(s):\n${contacts.joinToString("\n")}")
        }
    }

    private fun getPhone(ctx: Context, params: Map<String, String>): ToolResult {
        val name = params["name"] ?: return ToolResult.Error("Missing name parameter")

        val contacts = findContacts(ctx, name, limit = 3)
        if (contacts.isEmpty()) {
            return ToolResult.Success("No contact found for '$name'.")
        }
        return ToolResult.Success(contacts.first())
    }

    private fun findContacts(ctx: Context, query: String, limit: Int): List<String> {
        val results = mutableListOf<String>()
        var cursor: Cursor? = null

        try {
            // Search by display name
            cursor = ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER
                ),
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )

            cursor?.let {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val id = it.getString(0)
                    val name = it.getString(1) ?: "Unknown"
                    val hasPhone = it.getInt(2) > 0

                    val entry = buildString {
                        append("- $name")
                        if (hasPhone) {
                            val phone = getPhoneForContact(ctx, id)
                            if (phone != null) append(" | $phone")
                        }
                        val email = getEmailForContact(ctx, id)
                        if (email != null) append(" | $email")
                    }
                    results.add(entry)
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search contacts", e)
        } finally {
            cursor?.close()
        }
        return results
    }

    private fun getPhoneForContact(ctx: Context, contactId: String): String? {
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId), null
            )
            if (cursor?.moveToFirst() == true) return cursor.getString(0)
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun getEmailForContact(ctx: Context, contactId: String): String? {
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId), null
            )
            if (cursor?.moveToFirst() == true) return cursor.getString(0)
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return null
    }

    companion object {
        private const val TAG = "ContactsModule"

        fun hasPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
