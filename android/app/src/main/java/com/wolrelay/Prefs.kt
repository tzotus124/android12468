package com.wolrelay

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("wol", 0) }
    private fun get(k: String) = sp.getString(k, "") ?: ""
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    var server: String get() = get("server"); set(v) = put("server", v)
    var room: String get() = get("room"); set(v) = put("room", v)
    var priv: String get() = get("priv"); set(v) = put("priv", v)
    var pub: String get() = get("pub"); set(v) = put("pub", v)
    var pcPub: String get() = get("pcPub"); set(v) = put("pcPub", v)
    val paired get() = room.isNotEmpty() && pcPub.isNotEmpty()
    fun clearPairing() = sp.edit().remove("room").remove("priv").remove("pub").remove("pcPub").apply()
}
