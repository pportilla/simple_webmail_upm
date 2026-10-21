package io.github.pportilla.upmmail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AvatarPalette = listOf(
    Color(0xFF003F87), // UPM blue
    Color(0xFF1E88E5),
    Color(0xFF00897B),
    Color(0xFF43A047),
    Color(0xFF7CB342),
    Color(0xFFF4511E),
    Color(0xFFE53935),
    Color(0xFFD81B60),
    Color(0xFF8E24AA),
    Color(0xFF5E35B1),
    Color(0xFF3949AB),
    Color(0xFF6D4C41),
    Color(0xFF546E7A),
    Color(0xFFC0CA33),
)

/** Stable colour for an address, so the same person always gets the same avatar colour. */
fun avatarColorFor(key: String): Color {
    val normalized = key.trim().lowercase()
    if (normalized.isEmpty()) return AvatarPalette.last()
    return AvatarPalette[Math.floorMod(normalized.hashCode(), AvatarPalette.size)]
}

/** First letter of the name (or address) in upper case, "?" when empty. */
fun avatarInitial(name: String?, email: String?): String {
    val source = name?.takeIf { it.isNotBlank() } ?: email.orEmpty()
    val letter = source.trim().trimStart('"', '\'', '<', '(').firstOrNull { it.isLetterOrDigit() }
    return letter?.uppercaseChar()?.toString() ?: "?"
}

/** Circular avatar with the sender's initial on a colour derived from the address. */
@Composable
fun SenderAvatar(
    name: String?,
    email: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColorFor(email ?: name.orEmpty())),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitial(name, email),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.42f).sp,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
