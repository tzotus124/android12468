package com.wolrelay

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("wol", 0) }
    private fun get(k: String) = sp.getString(k, "") ?: ""
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    var room: String get() = get("room"); set(v) = put("room", v)
    var phoneIp: String get() = get("phoneIp"); set(v) = put("phoneIp", v)
    var pcIp: String get() = get("pcIp"); set(v) = put("pcIp", v)
    var tsBatteryAsked: Boolean get() = get("tsBat") == "1"; set(v) = put("tsBat", if (v) "1" else "")
    val paired get() = room.isNotEmpty() && pcIp.isNotEmpty()
    fun clearPairing() = sp.edit().remove("room").remove("phoneIp").remove("pcIp").apply()
}
