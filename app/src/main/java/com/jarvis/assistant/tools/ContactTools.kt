package com.jarvis.assistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.jarvis.assistant.brain.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal data class ContactMatch(val name: String, val number: String)

class CallContactTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "call_contact",
        description = "Call a contact by name, or a phone number. Always asks the owner to " +
            "confirm before the call is placed.",
        parameters = objectSchema(
            required = listOf("contact"),
            properties = mapOf(
                "contact" to stringProperty("Contact name, or a phone number with country code."),
            ),
        ),
    )

    override val label = "Call contact"
    override val category = ToolCategory.CONTACTS

    override fun availability(context: Context): ToolAvailability =
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ToolAvailability("Ready", true)
        } else {
            ToolAvailability("Needs contacts", true)
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.argString("contact")
            ?: return ToolResult(false, "Tell me who to call.")
        val target = resolveTarget(context, query)
            ?: return ToolResult(false, contactLookupFailure(query))

        val approved = context.confirmation.confirm(
            title = "Call ${target.name}?",
            detail = target.number,
        )
        if (!approved) {
            return ToolResult(false, "The owner declined the call to ${target.name}.")
        }

        val canCallDirectly = context.permissions.ensure(
            Manifest.permission.CALL_PHONE,
            "Jarvis needs the Phone permission to place the call without opening the dialler.",
        )
        val action = if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:" + Uri.encode(target.number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching { context.appContext.startActivity(intent) }.fold(
            onSuccess = {
                if (canCallDirectly) {
                    ToolResult(true, "Calling ${target.name} on ${target.number}.")
                } else {
                    ToolResult(
                        true,
                        "Opening the dialler for ${target.name} on ${target.number}. " +
                            "Tap the call button to start it.",
                    )
                }
            },
            onFailure = { error ->
                ToolResult(false, "Android would not start the call: ${error.message}.")
            },
        )
    }
}

class SendSmsTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "send_sms",
        description = "Send a text message to a contact. Always reads the message back and asks " +
            "the owner to confirm before sending.",
        parameters = objectSchema(
            required = listOf("contact", "message"),
            properties = mapOf(
                "contact" to stringProperty("Contact name, or a phone number."),
                "message" to stringProperty("The message to send."),
            ),
        ),
    )

    override val label = "Send message"
    override val category = ToolCategory.CONTACTS

    override fun availability(context: Context): ToolAvailability =
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ToolAvailability("Ready", true)
        } else {
            ToolAvailability("Needs contacts", true)
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.argString("contact")
            ?: return ToolResult(false, "Tell me who to message.")
        val message = arguments.argString("message")
            ?: return ToolResult(false, "Tell me what the message should say.")
        val target = resolveTarget(context, query)
            ?: return ToolResult(false, contactLookupFailure(query))

        val preview = if (message.length > 160) message.take(157) + "..." else message
        val approved = context.confirmation.confirm(
            title = "Message ${target.name}?",
            detail = "${target.number}\n\"$preview\"",
        )
        if (!approved) {
            return ToolResult(false, "The owner declined the message to ${target.name}.")
        }

        val granted = context.permissions.ensure(
            Manifest.permission.SEND_SMS,
            "Jarvis needs the SMS permission to send the message by itself.",
        )

        if (!granted) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(target.number)))
                .putExtra("sms_body", message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.appContext.startActivity(intent) }.fold(
                onSuccess = {
                    ToolResult(
                        true,
                        "Opening the message to ${target.name}; tap send to deliver it.",
                    )
                },
                onFailure = { error ->
                    ToolResult(false, "The messaging app would not open: ${error.message}.")
                },
            )
        }

        val smsManager = context.appContext.getSystemService(SmsManager::class.java)
            ?: return ToolResult(false, "This phone has no SMS service.")

        return runCatching {
            if (message.length > 160) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(target.number, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(target.number, null, message, null, null)
            }
        }.fold(
            onSuccess = { ToolResult(true, "Message sent to ${target.name}.") },
            onFailure = { error ->
                ToolResult(false, "Android would not send the message: ${error.message}.")
            },
        )
    }
}

private fun contactLookupFailure(query: String): String =
    "I could not find a contact called \"$query\". Allow contact access in Settings, " +
        "Permissions, or give me the number instead."

/** Resolves a name through contacts, or accepts a number spoken directly. */
internal suspend fun resolveTarget(context: ToolContext, query: String): ContactMatch? {
    val digits = query.filter { it.isDigit() || it == '+' }
    val digitCount = digits.count { it.isDigit() }
    if (digitCount >= 7) {
        return ContactMatch(name = "that number", number = digits)
    }

    val granted = context.permissions.ensure(
        Manifest.permission.READ_CONTACTS,
        "Jarvis needs to read your contacts to find the person you named.",
    )
    if (!granted) return null

    return withContext(Dispatchers.IO) {
        findContact(context.appContext, query)
    }
}

private fun findContact(context: Context, query: String): ContactMatch? {
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    val matches = mutableListOf<ContactMatch>()

    runCatching {
        context.contentResolver
            .query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                )
                val numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
                if (nameIndex < 0 || numberIndex < 0) return@use
                while (cursor.moveToNext() && matches.size < MAX_CONTACTS) {
                    val name = cursor.getString(nameIndex)?.trim().orEmpty()
                    val number = cursor.getString(numberIndex)?.trim().orEmpty()
                    if (name.isNotEmpty() && number.isNotEmpty()) {
                        matches += ContactMatch(name, number.replace(" ", ""))
                    }
                }
            }
    }

    return matches
        .map { it to matchScore(query, it.name) }
        .filter { it.second > 0 }
        .maxByOrNull { it.first.name.length * -1 + it.second * 100 }
        ?.first
}

private const val MAX_CONTACTS = 3_000
