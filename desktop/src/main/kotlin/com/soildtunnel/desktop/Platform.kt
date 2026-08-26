package com.soildtunnel.desktop

import java.io.File
import java.util.Properties

object DesktopBuild {
    const val VERSION_NAME = "1.0.3"
    const val VERSION_CODE = 4
    const val CORE_VERSION = "1.0.0"
    const val RELEASES_URL = "https://github.com/CyebRageAnonymuos/Soild-Tunnel/releases/latest"
}

/** App data root: ~/.local/share/SoildTunnel */
object Paths {
    val dataDir: File by lazy {
        val d = File(System.getProperty("user.home"), ".local/share/SoildTunnel")
        d.mkdirs()
        d
    }
    val workDir: File by lazy {
        val d = File(dataDir, "engine")
        d.mkdirs()
        d
    }
    fun engineBinary(): File = File(workDir, "soildtunnel-core")
}

/**
 * File-backed key/value store replacing Android SharedPreferences so the
 * core singletons port without touching their call sites.
 */
class DesktopPrefs(name: String) {
    private val file = File(Paths.dataDir, "$name.properties")
    private val props = Properties()

    init {
        try {
            if (file.exists()) file.inputStream().use { props.load(it) }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun getString(key: String, def: String?): String? = props.getProperty(key, def)

    @Synchronized
    fun getLong(key: String, def: Long): Long =
        props.getProperty(key)?.toLongOrNull() ?: def

    @Synchronized
    fun edit(block: (Editor) -> Unit) {
        val e = Editor(props)
        block(e)
        if (e.cleared) props.clear()
        e.removed.forEach { props.remove(it) }
        e.writes.forEach { (k, v) -> props.setProperty(k, v) }
        try {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, null) }
        } catch (_: Exception) {
        }
    }

    class Editor(private val props: Properties) {
        internal val writes = mutableMapOf<String, String>()
        internal val removed = mutableListOf<String>()
        internal var cleared = false

        fun putString(key: String, value: String?) {
            if (value == null) removed.add(key) else writes[key] = value
        }

        fun putLong(key: String, value: Long) {
            writes[key] = value.toString()
        }

        fun remove(key: String) {
            removed.add(key)
        }

        fun clear() {
            cleared = true
        }
    }
}

/** Opens a URL in the default browser. */
fun browseUrl(url: String) {
    try {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    } catch (_: Exception) {
    }
}
