@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.media.editing

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.DefaultVideoFrameProcessor

/** Use the reported ES2 capability directly, without a failed ES3 attempt terminating its display. */
internal fun mediaFrameProcessor(context: Context): DefaultVideoFrameProcessor.Factory {
    return DefaultVideoFrameProcessor.Factory.Builder().setGlObjectsProvider(mediaGlObjects(context)).build()
}

internal fun mediaGlObjects(context: Context): GlObjectsProvider {
    val delegate = DefaultGlObjectsProvider()
    val legacy = context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion in 1 until 0x30000
    val provider = object : GlObjectsProvider by delegate {
        override fun createEglContext(display: EGLDisplay, version: Int, attributes: IntArray): EGLContext {
            if (!legacy) return delegate.createEglContext(display, version, attributes)
            // SDR remains RGBA8888. Never silently reduce HDR precision to fit ES2.
            require(attributes.contentEquals(GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)) { "Įrenginys nepalaiko pasirinktų nustatymų" }
            return delegate.createEglContext(display, 2, attributes)
        }
    }
    return provider
}
