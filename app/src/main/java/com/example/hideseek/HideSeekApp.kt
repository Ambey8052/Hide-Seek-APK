package com.example.hideseek

import android.app.Application
import android.content.Context
import com.example.hideseek.data.GameClient
import com.example.hideseek.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the app-wide session and game connection so they survive screen changes. */
class HideSeekApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var session: Session
        private set
    lateinit var game: GameClient
        private set

    override fun onCreate() {
        super.onCreate()
        session = Session(this)
        game = GameClient(session, scope)
    }
}

val Context.app get() = applicationContext as HideSeekApp
