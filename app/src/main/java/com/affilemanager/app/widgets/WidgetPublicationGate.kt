package com.affilemanager.app.widgets

/** Orders host publications, not thumbnail loading or other expensive work. */
internal object WidgetPublicationGate {
    private val monitor = Any()
    fun <T> serialize(action: () -> T): T = synchronized(monitor, action)
}
