package com.soildtunnel.desktop.ui.common

import androidx.compose.runtime.Composable
import com.soildtunnel.desktop.Strings

@Composable
fun tr(key: String): String = Strings[key]

@Composable
fun trF(key: String, vararg args: Any?): String = Strings.format(key, *args)
