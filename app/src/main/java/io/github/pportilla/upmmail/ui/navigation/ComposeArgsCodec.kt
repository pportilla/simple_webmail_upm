package io.github.pportilla.upmmail.ui.navigation

import io.github.pportilla.upmmail.model.ComposeMode
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * ComposeArgs as flat key/value pairs of bundle-friendly types (Long, String, Boolean and
 * ArrayList<String>), for the compose screen's saved state and the share request MainActivity
 * keeps until the first sign-in. Saved state must stay well below the binder transaction limit,
 * so a long body is kept in a file of a [TextSpill] instead.
 */
internal object ComposeArgsCodec {
    private const val PREFIX = "upmmail.compose."
    private const val SAVED = PREFIX + "saved"
    private const val ACCOUNT = PREFIX + "accountId"
    private const val MODE = PREFIX + "mode"
    private const val SOURCE = PREFIX + "sourceMessageId"
    private const val DRAFT = PREFIX + "draftId"
    private const val SERVER_DRAFT = PREFIX + "serverDraftMessageId"
    private const val TO = PREFIX + "to"
    private const val CC = PREFIX + "cc"
    private const val BCC = PREFIX + "bcc"
    private const val SUBJECT = PREFIX + "subject"
    private const val BODY = PREFIX + "body"
    private const val BODY_FILE = PREFIX + "bodyFile"
    private const val ATTACHMENTS = PREFIX + "attachments"

    /** Longer bodies (in characters) are kept in a file. */
    const val MAX_BODY_IN_STATE = 16 * 1024

    fun write(args: ComposeArgs, spill: TextSpill?, put: (key: String, value: Any?) -> Unit) {
        val body = args.body
        val bodyFile = if (body != null && body.length > MAX_BODY_IN_STATE) spill?.put(body) else null
        put(ACCOUNT, args.accountId)
        put(MODE, args.mode.name)
        put(SOURCE, args.sourceMessageId)
        put(DRAFT, args.draftId)
        put(SERVER_DRAFT, args.serverDraftMessageId)
        put(TO, args.to)
        put(CC, args.cc)
        put(BCC, args.bcc)
        put(SUBJECT, args.subject)
        put(BODY, if (bodyFile == null) body else null)
        put(BODY_FILE, bodyFile)
        put(ATTACHMENTS, ArrayList(args.attachmentUris))
        put(SAVED, true)
    }

    /** The args stored by [write], null when there are none. */
    fun read(spill: TextSpill?, get: (key: String) -> Any?): ComposeArgs? {
        if (get(SAVED) != true) return null
        val defaults = ComposeArgs()
        return ComposeArgs(
            accountId = get(ACCOUNT) as? Long ?: defaults.accountId,
            mode = (get(MODE) as? String)?.let { name -> ComposeMode.entries.firstOrNull { it.name == name } }
                ?: defaults.mode,
            sourceMessageId = get(SOURCE) as? Long ?: defaults.sourceMessageId,
            draftId = get(DRAFT) as? Long ?: defaults.draftId,
            serverDraftMessageId = get(SERVER_DRAFT) as? Long ?: defaults.serverDraftMessageId,
            to = get(TO) as? String,
            cc = get(CC) as? String,
            bcc = get(BCC) as? String,
            subject = get(SUBJECT) as? String,
            body = get(BODY) as? String ?: (get(BODY_FILE) as? String)?.let { spill?.get(it) },
            attachmentUris = (get(ATTACHMENTS) as? List<*>)?.filterIsInstance<String>() ?: defaults.attachmentUris,
        )
    }

    /**
     * True when [keys] (of navigation arguments) include ours. The app never passes them that
     * way, so they come from a deep link forged by another app.
     */
    fun containsOwnKeys(keys: Collection<String>): Boolean = keys.any { it.startsWith(PREFIX) }
}

/**
 * Keeps texts too long for saved state as files in [dir]. A text is stored under a name derived
 * from its content, so saving the same state again reuses the file; files older than
 * [maxAgeMillis] are removed when a new one is written.
 */
internal class TextSpill(
    private val dir: File,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Stores [text] and returns its name, or null if it could not be written. */
    fun put(text: String): String? = try {
        if (dir.isDirectory || dir.mkdirs()) {
            prune()
            val name = UUID.nameUUIDFromBytes(text.toByteArray(Charsets.UTF_8)).toString()
            File(dir, name).writeText(text, Charsets.UTF_8)
            name
        } else {
            null
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /** The text stored as [name]; null if it is gone or [name] is not one of ours. */
    fun get(name: String): String? {
        if (!NAME.matches(name)) return null
        return try {
            File(dir, name).readText(Charsets.UTF_8)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun prune() {
        val limit = clock() - maxAgeMillis
        dir.listFiles()?.forEach { file -> if (file.lastModified() < limit) file.delete() }
    }

    private companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** Names are UUIDs: nothing outside [dir] can be read through a crafted name. */
        val NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
