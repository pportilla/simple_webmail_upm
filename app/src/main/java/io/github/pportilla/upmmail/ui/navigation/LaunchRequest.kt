package io.github.pportilla.upmmail.ui.navigation

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.util.Log
import io.github.pportilla.upmmail.mail.HtmlText

/** Where an incoming intent (notification tap, mailto: link, share) asks the app to go. */
sealed interface LaunchRequest {
    /** Write a new message, prefilled from a mailto: link or a share. */
    data class Compose(val args: ComposeArgs) : LaunchRequest

    /** Open one message; [accountId] is the account it belongs to, if known. */
    data class OpenMessage(val messageId: Long, val accountId: Long?) : LaunchRequest

    /** Show the INBOX of [accountId] (current account when null), or its outbox. */
    data class OpenMailbox(val accountId: Long?, val outbox: Boolean) : LaunchRequest

    companion object {
        private const val TAG = "LaunchRequest"

        /**
         * Reads [intent]. Returns null for intents that do not ask for any navigation (e.g. the
         * launcher). [ownPackage] is used to refuse attachments pointing into our own private
         * files, which another app could otherwise make us send.
         */
        fun fromIntent(intent: Intent?, ownPackage: String): LaunchRequest? {
            if (intent == null) return null
            // The activity is exported: a malformed extras bundle must not crash the app.
            return try {
                parse(intent, ownPackage)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Ignoring unreadable intent", e)
                null
            }
        }

        private fun parse(intent: Intent, ownPackage: String): LaunchRequest? {
            val accountId = intent.getLongExtra(DeepLinks.EXTRA_ACCOUNT_ID, -1L).takeIf { it >= 0 }
            val messageId = intent.getLongExtra(DeepLinks.EXTRA_MESSAGE_ID, -1L)
            if (messageId >= 0) return OpenMessage(messageId, accountId)
            val outbox = intent.getBooleanExtra(DeepLinks.EXTRA_OPEN_OUTBOX, false)
            if (outbox || accountId != null) return OpenMailbox(accountId, outbox)

            return when (intent.action) {
                Intent.ACTION_VIEW, Intent.ACTION_SENDTO -> {
                    val data = intent.dataString ?: return null
                    if (!MailtoParser.isMailto(data)) return null
                    Compose(composeArgs(intent, ownPackage, multiple = false))
                }
                Intent.ACTION_SEND -> Compose(composeArgs(intent, ownPackage, multiple = false))
                Intent.ACTION_SEND_MULTIPLE -> Compose(composeArgs(intent, ownPackage, multiple = true))
                else -> null
            }
        }

        /** Merges the mailto: data (if any) with the standard e-mail extras of share intents. */
        private fun composeArgs(intent: Intent, ownPackage: String, multiple: Boolean): ComposeArgs {
            val mailto = intent.dataString?.let(MailtoParser::parse) ?: MailtoData()
            val subject = mailto.subject
                ?: intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val body = mailto.body ?: sharedText(intent, multiple)
            return ComposeArgs(
                to = MailtoParser.joinAddresses(mailto.to, addressExtra(intent, Intent.EXTRA_EMAIL)),
                cc = MailtoParser.joinAddresses(mailto.cc, addressExtra(intent, Intent.EXTRA_CC)),
                bcc = MailtoParser.joinAddresses(mailto.bcc, addressExtra(intent, Intent.EXTRA_BCC)),
                subject = subject,
                body = body,
                attachmentUris = sharedStreams(intent, multiple)
                    .filter { isAcceptableAttachment(it, ownPackage) }
                    .map(Uri::toString)
                    .distinct(),
            )
        }

        /** EXTRA_EMAIL and friends are String[] by contract, but some apps send a single String. */
        private fun addressExtra(intent: Intent, name: String): List<String> {
            val value = intent.extras?.let {
                @Suppress("DEPRECATION")
                it.get(name)
            } ?: return emptyList()
            return when (value) {
                is Array<*> -> value.mapNotNull { (it as? CharSequence)?.toString() }
                is Collection<*> -> value.mapNotNull { (it as? CharSequence)?.toString() }
                is CharSequence -> listOf(value.toString())
                else -> emptyList()
            }
        }

        private fun sharedText(intent: Intent, multiple: Boolean): String? {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: if (multiple) {
                    intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
                        ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                        ?.joinToString("\n\n")
                } else {
                    null
                }
            val plain = text ?: intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let(HtmlText::toPlainText)
            return plain?.replace("\r\n", "\n")?.takeIf { it.isNotBlank() }
        }

        private fun sharedStreams(intent: Intent, multiple: Boolean): List<Uri> {
            val fromExtras: List<Uri> = if (multiple) {
                parcelableListExtra(intent, Intent.EXTRA_STREAM)
            } else {
                listOfNotNull(parcelableExtra(intent, Intent.EXTRA_STREAM))
            }
            if (fromExtras.isNotEmpty()) return fromExtras
            // Since Android 4.1 the framework mirrors EXTRA_STREAM into ClipData (with the grant).
            val clip = intent.clipData ?: return emptyList()
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
        }

        private fun parcelableExtra(intent: Intent, name: String): Uri? =
            // The typed getter is unreliable on Android 13 (fixed in 14).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                intent.getParcelableExtra(name, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Parcelable>(name) as? Uri
            }

        private fun parcelableListExtra(intent: Intent, name: String): List<Uri> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                intent.getParcelableArrayListExtra(name, Uri::class.java).orEmpty().filterNotNull()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Parcelable>(name).orEmpty().mapNotNull { it as? Uri }
            }

        /**
         * Only content:// URIs are attached. file:// URIs could point into our private storage
         * (and other apps cannot share them since Android 7 anyway), and URIs of our own
         * attachment provider would let another app make us send the user's cached mail.
         */
        internal fun isAcceptableAttachment(uri: Uri, ownPackage: String): Boolean {
            if (!uri.scheme.equals("content", ignoreCase = true)) return false
            val authority = uri.authority ?: return false
            return !ContentAuthorities.isOwn(authority, ownPackage)
        }
    }
}
