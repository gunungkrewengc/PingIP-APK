package com.pingip.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class PingAssistantService : VoiceInteractionService() {

    companion object {
        @Volatile var instance: PingAssistantService? = null

        fun isAssistantActive(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val pkg = context.packageName
                val a1 = Settings.Secure.getString(context.contentResolver, "assistant") ?: ""
                val a2 = Settings.Secure.getString(context.contentResolver, "voice_interaction_service") ?: ""
                a1.startsWith("$pkg/") || a2.startsWith("$pkg/")
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        if (instance === this) {
            instance = null
        }
        super.onShutdown()
    }

    fun triggerAirplaneMode(enable: Boolean): Boolean {
        return try {
            val args = Bundle().apply {
                putBoolean("airplane_mode_on", enable)
            }
            showSession(args, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

class PingAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return PingAssistantSession(this)
    }
}

class PingAssistantSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        try {
            setUiEnabled(false)
        } catch (_: Throwable) {}
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val enable = args?.getBoolean("airplane_mode_on", true) ?: true
        try {
            val intent = Intent("android.settings.VOICE_CONTROL_AIRPLANE_MODE").apply {
                putExtra("airplane_mode_enabled", enable)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startVoiceActivity(intent)
        } catch (_: Throwable) {
        } finally {
            try {
                finish()
            } catch (_: Throwable) {}
        }
    }
}
