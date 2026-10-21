package io.github.pportilla.upmmail.ui.navigation

import io.github.pportilla.upmmail.model.ComposeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ComposeArgsCodecTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun roundTrip(args: ComposeArgs, spill: TextSpill?): Pair<ComposeArgs?, Map<String, Any?>> {
        val state = HashMap<String, Any?>()
        ComposeArgsCodec.write(args, spill) { key, value -> state[key] = value }
        return ComposeArgsCodec.read(spill) { state[it] } to state
    }

    @Test
    fun `args survive a round trip`() {
        val args = ComposeArgs(
            accountId = 3,
            mode = ComposeMode.FORWARD,
            sourceMessageId = 42,
            to = "a@upm.es",
            subject = "Hola",
            body = "Texto",
            attachmentUris = listOf("content://media/1", "content://media/2"),
        )
        assertEquals(args, roundTrip(args, null).first)
        assertEquals(ComposeArgs(), roundTrip(ComposeArgs(), null).first)
    }

    @Test
    fun `nothing stored reads as null`() {
        assertNull(ComposeArgsCodec.read(null) { null })
    }

    @Test
    fun `a long body is kept out of the saved state`() {
        val spill = TextSpill(temp.newFolder("spill"))
        val body = "línea\n".repeat(ComposeArgsCodec.MAX_BODY_IN_STATE)
        val args = ComposeArgs(body = body)
        val (read, state) = roundTrip(args, spill)
        assertEquals(args, read)
        assertTrue(state.values.none { it is String && it.length > ComposeArgsCodec.MAX_BODY_IN_STATE })
        // Saving the same state again reuses the file.
        roundTrip(args, spill)
        assertEquals(1, File(temp.root, "spill").listFiles()!!.size)
    }

    @Test
    fun `a long body stays in the state when it cannot be spilled`() {
        val body = "x".repeat(ComposeArgsCodec.MAX_BODY_IN_STATE + 1)
        assertEquals(body, roundTrip(ComposeArgs(body = body), null).first?.body)
    }

    @Test
    fun `only spill names are read and old files are pruned`() {
        val dir = temp.newFolder("spill")
        var now = 1_000_000_000_000L
        val spill = TextSpill(dir, maxAgeMillis = 1_000, clock = { now })
        File(temp.root, "secret").writeText("private")
        assertNull(spill.get("../secret"))
        assertNull(spill.get("secret"))

        val first = assertNotNullName(spill.put("one"))
        assertEquals("one", spill.get(first))
        File(dir, first).setLastModified(now - 5_000)
        now += 10_000
        val second = assertNotNullName(spill.put("two"))
        assertNull(spill.get(first))
        assertEquals("two", spill.get(second))
    }

    @Test
    fun `navigation arguments with our keys are recognised as forged`() {
        val state = HashMap<String, Any?>()
        ComposeArgsCodec.write(ComposeArgs(to = "x@evil.example"), null) { key, value -> state[key] = value }
        assertTrue(ComposeArgsCodec.containsOwnKeys(state.keys))
        assertFalse(ComposeArgsCodec.containsOwnKeys(listOf("key", "android-support-nav:controller:deepLinkIntent")))
    }

    @Test
    fun `own provider authorities are recognised with a user prefix too`() {
        val own = "io.github.pportilla.upmmail.debug"
        assertTrue(ContentAuthorities.isOwn("io.github.pportilla.upmmail.debug.attachments", own))
        assertTrue(ContentAuthorities.isOwn("0@io.github.pportilla.upmmail.debug.attachments", own))
        assertTrue(ContentAuthorities.isOwn("10@IO.GITHUB.PPORTILLA.UPMMAIL.DEBUG.attachments", own))
        assertFalse(ContentAuthorities.isOwn("com.android.providers.media.documents", own))
        // Another user's profile sharing through its own provider stays acceptable.
        assertFalse(ContentAuthorities.isOwn("10@com.android.providers.media", own))
    }

    private fun assertNotNullName(name: String?): String {
        assertNotNull(name)
        return name!!
    }
}
