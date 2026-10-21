package io.github.pportilla.upmmail.ui.navigation

/** Checks on content URI authorities, kept Android-free so they can be unit-tested. */
internal object ContentAuthorities {
    /**
     * True when [authority] (decoded, as Uri.getAuthority returns it) names one of the providers
     * of [ownPackage]. The content resolver ignores a "<userId>@" prefix, so "0@<our provider>"
     * reaches our provider too.
     */
    fun isOwn(authority: String, ownPackage: String): Boolean =
        authority.substringAfterLast('@').startsWith(ownPackage, ignoreCase = true)
}
