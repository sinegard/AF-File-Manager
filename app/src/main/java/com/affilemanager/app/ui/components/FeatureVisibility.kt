package com.affilemanager.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.affilemanager.app.data.FeatureVisibility
import com.affilemanager.app.data.OptionalFeature

val LocalFeatureVisibility = staticCompositionLocalOf { FeatureVisibility() }

@Composable
fun featureVisible(feature: OptionalFeature): Boolean = LocalFeatureVisibility.current.isVisible(feature)
