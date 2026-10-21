package io.github.pportilla.upmmail.data.security

import java.util.concurrent.ConcurrentHashMap

/** Account passwords. Implementations must be thread-safe; calls may block briefly. */
interface PasswordStore {
    /** The stored password, or null if missing or no longer decryptable (ask the user again). */
    fun get(accountId: Long): String?

    /** Stores [password]; throws if it cannot be stored securely. */
    fun set(accountId: Long, password: String)

    fun remove(accountId: Long)
}

/** Volatile store for tests. */
class InMemoryPasswordStore : PasswordStore {
    private val passwords = ConcurrentHashMap<Long, String>()

    override fun get(accountId: Long): String? = passwords[accountId]

    override fun set(accountId: Long, password: String) {
        passwords[accountId] = password
    }

    override fun remove(accountId: Long) {
        passwords.remove(accountId)
    }
}
