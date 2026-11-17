package com.emrg.app

import android.app.Application
import com.emrg.app.security.SecureTokenStore
import com.emrg.app.data.HostStore
import com.emrg.app.data.GatewayRepository

/** Application-wide singletons. One repository, one token store, one host store. */
class EmrgApplication : Application() {

    lateinit var repository: GatewayRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = GatewayRepository(applicationContext)
    }
}
