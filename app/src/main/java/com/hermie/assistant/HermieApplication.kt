package com.hermie.assistant

import android.app.Application
import com.hermie.assistant.service.HermieNotificationHelper

class HermieApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HermieNotificationHelper.initialize(this)
    }
}
