package com.example.hideseek

import android.app.Application
import android.content.Context
import com.example.hideseek.data.GameClient
import com.example.hideseek.data.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds this phone's profile and the game connection so they survive screen changes. */
class HideSeekApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var profile: Profile
        private set
    lateinit var game: GameClient
        private set

    override fun onCreate() {
        super.onCreate()
        profile = Profile(this)
        game = GameClient(this, profile, scope)
    }
}

val Context.app get() = applicationContext as HideSeekApp
