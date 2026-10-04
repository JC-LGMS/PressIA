package com.pressia.di

import android.content.Context
import com.pressia.data.PressRepository
import com.pressia.data.buildDatabase
import com.pressia.data.local.Prefs

/** Service locator simple (évite Hilt/KSP pour un build sans friction). */
object Locator {
    @Volatile private var repo: PressRepository? = null
    fun repo(ctx: Context): PressRepository {
        val app = ctx.applicationContext
        return repo ?: synchronized(this) {
            repo ?: PressRepository(app, buildDatabase(app), Prefs(app)).also { repo = it }
        }
    }
}
