package com.aivpn.connect.vpn

import org.json.JSONArray
import org.webrtc.PeerConnection

data class IceServerSpec(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
) {
    fun toWebRtc(): PeerConnection.IceServer {
        val b = PeerConnection.IceServer.builder(urls)
        if (!username.isNullOrEmpty() && !credential.isNullOrEmpty()) {
            b.setUsername(username).setPassword(credential)
        }
        return b.createIceServer()
    }

    companion object {
        fun parseJsonArray(arr: JSONArray): List<IceServerSpec> {
            val out = ArrayList<IceServerSpec>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val urls = mutableListOf<String>()
                when (val raw = obj.opt("urls")) {
                    is String -> urls += raw
                    is JSONArray -> for (j in 0 until raw.length()) urls += raw.getString(j)
                }
                out += IceServerSpec(
                    urls = urls,
                    username = obj.optString("username").ifEmpty { null },
                    credential = obj.optString("credential").ifEmpty { null },
                )
            }
            return out
        }
    }
}
