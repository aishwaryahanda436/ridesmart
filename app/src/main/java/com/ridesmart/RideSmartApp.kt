package com.ridesmart

import android.app.Application
import io.github.jan_tennert.supabase.SupabaseClient
import io.github.jan_tennert.supabase.createSupabaseClient
import io.github.jan_tennert.supabase.auth.Auth
import io.github.jan_tennert.supabase.postgrest.Postgrest
import io.github.jan_tennert.supabase.realtime.Realtime
import io.github.jan_tennert.supabase.storage.Storage

class RideSmartApp : Application() {

    companion object {
        lateinit var supabase: SupabaseClient
    }

    override fun onCreate() {
        super.onCreate()

        supabase = createSupabaseClient(
            supabaseUrl = "https://iwlzgybvcwizawnqwntx.supabase.co",
            supabaseKey = BuildConfig.SUPABASE_KEY // Use BuildConfig for secret
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }
}
