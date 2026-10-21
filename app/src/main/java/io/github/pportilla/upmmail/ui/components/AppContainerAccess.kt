package io.github.pportilla.upmmail.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.pportilla.upmmail.UpmMailApp
import io.github.pportilla.upmmail.data.repository.AppContainer

/** The process-wide dependency container. */
val Context.appContainer: AppContainer
    get() = (applicationContext as UpmMailApp).container

/** The container from inside a composable, e.g. `viewModel { MyViewModel(appContainer()) }`. */
@Composable
fun appContainer(): AppContainer = LocalContext.current.appContainer
