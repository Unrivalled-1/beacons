package com.example.beacons

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local-only persistence of beacons. No network, no backup (allowBackup is off). */
class BeaconStore(context: Context) {
    private val prefs = context.getSharedPreferences("beacons", Context.MODE_PRIVATE)

    class Snapshot(val beacons: List<Beacon>, val natoI: Int, val dropN: Int, val nextId: Int)

    fun load(): Snapshot {
        val raw = prefs.getString("data", null) ?: return Snapshot(emptyList(), 0, 0, 1)
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("beacons")
            val list = ArrayList<Beacon>()
            var maxId = 0
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val bc = Beacon(b.getInt("id"), b.getString("name"), b.getInt("ci"), 0.0, 0.0)
                bc.lat = b.getDouble("lat"); bc.lon = b.getDouble("lon")
                list.add(bc); maxId = maxOf(maxId, bc.id)
            }
            Snapshot(list, o.optInt("natoI", 0), o.optInt("dropN", 0), maxId + 1)
        } catch (e: Exception) {
            Snapshot(emptyList(), 0, 0, 1)
        }
    }

    fun save(beacons: List<Beacon>, natoI: Int, dropN: Int) {
        val arr = JSONArray()
        for (b in beacons) {
            arr.put(JSONObject().put("id", b.id).put("name", b.name).put("ci", b.ci).put("lat", b.lat).put("lon", b.lon))
        }
        prefs.edit().putString("data", JSONObject().put("beacons", arr).put("natoI", natoI).put("dropN", dropN).toString()).apply()
    }
}
