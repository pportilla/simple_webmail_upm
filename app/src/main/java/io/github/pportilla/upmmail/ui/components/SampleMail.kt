package io.github.pportilla.upmmail.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.Account

/** The trusted persisted account flag, never its address or display name, identifies sample mail. */
@Composable
fun accountAddressLabel(account: Account?): String = when {
    account == null -> ""
    account.isDemo -> stringResource(R.string.demo_account_label, account.email)
    else -> account.email
}

@Composable
fun SampleMailNotice(account: Account?, modifier: Modifier = Modifier) {
    if (account?.isDemo != true) return
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(stringResource(R.string.demo_notice), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}
