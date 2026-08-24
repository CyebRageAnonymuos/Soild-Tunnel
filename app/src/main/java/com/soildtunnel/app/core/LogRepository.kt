package com.soildtunnel.app.core

/**
 * Compatibility shim for sources merged into SoildTunnel (their logger name).
 * Keeps the ported call sites byte-identical; forwards to [DiagnosticsLog].
 */
private const val DEFAULT_TAG = "bridge"

object LogRepository {
    fun i(msg: String, tag: String = DEFAULT_TAG) = DiagnosticsLog.i(tag, msg)
    fun w(msg: String, tag: String = DEFAULT_TAG) = DiagnosticsLog.w(tag, msg)
    fun e(msg: String, tag: String = DEFAULT_TAG) = DiagnosticsLog.e(tag, msg)
}
