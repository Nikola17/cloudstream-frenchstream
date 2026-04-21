package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FrenchStreamProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FrenchStreamProvider())
    }
}
