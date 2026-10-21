package io.github.pportilla.upmmail

import android.app.Application
import io.github.pportilla.upmmail.data.DefaultAppContainer
import io.github.pportilla.upmmail.data.repository.AppContainer

class UpmMailApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        container.syncController.schedulePeriodicSync()
    }
}
