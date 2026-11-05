package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.model.ServerSettings

/** Explicit test seam for the local GreenMail/FakeImapServer fixtures; never accepts a remote host. */
internal fun allowLoopbackTransport(settings: ServerSettings) {
    require(settings.host == "127.0.0.1") { "Mock mail servers must use the loopback interface" }
}
