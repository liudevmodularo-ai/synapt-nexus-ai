package com.synapt.nexus

import android.app.Application
import timber.log.Timber

/**
 * 🤖 SynaptNexusApp — ARIA
 *
 * Application class. Inicializa logging e componentes globais.
 * Injeção de dependência manual (Hilt em Sprint 2).
 */
class SynaptNexusApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("🧠 Synapt Nexus AI starting | version=${BuildConfig.VERSION_NAME}")
    }
}
