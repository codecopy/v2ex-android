package com.czbix.v2ex.util

import com.czbix.v2ex.AppCtx

import java.io.File

object IoUtils {
    private lateinit var cacheDir: File

    init {
        cacheDir = AppCtx.getInstance().cacheDir
    }

    @JvmStatic
    val webCachePath: File by lazy {
        File(cacheDir, "webCache")
    }
}