package com.drivevault.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class App : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private val autoLock = Runnable { if (Session.unlocked) Session.lock("auto-lock (app in background)") }

    override fun onCreate() {
        super.onCreate()
        Session.ctx = this
        Session.wipeWork()   // leftovers from a crash
        // Reopen the last drive. If it isn't ready yet (USB still mounting), the lock screen says so and offers Retry
        // instead of falling back to a list where an unreadable drive could look empty.
        Session.prefs.getString("base", null)?.let { id ->
            val d = try { Storage.driveFromId(this, id) } catch (e: Exception) { null } ?: return@let
            if (d.state() != DriveState.Empty) { Session.drive = d; Session.vault = com.drivevault.core.Vault(d.vaultFs()); Session.screen = Screen.Lock }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                var mins = Session.prefs.getInt("autolock_min", 2)
                if (Session.awayOk) mins = maxOf(mins, 10)        // user is in a file picker / share sheet we opened
                if (Session.jobs.isEmpty()) handler.postDelayed(autoLock, mins * 60_000L)
            }
            override fun onStart(owner: LifecycleOwner) { handler.removeCallbacks(autoLock); Session.awayOk = false }
        })
    }
}
