package `fun`.test.id

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SsaidHistoryRecord(
    val oldValue: String,
    val newValue: String,
    val timestamp: Long
)

class SsaidHistoryStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun records(packageName: String): List<SsaidHistoryRecord> {
        val raw = preferences.getString(packageName, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val oldValue = item.optString(OLD_VALUE_KEY)
                    val newValue = item.optString(NEW_VALUE_KEY)
                    val timestamp = item.optLong(TIMESTAMP_KEY)
                    if (oldValue.isNotBlank() && newValue.isNotBlank() && timestamp > 0) {
                        add(SsaidHistoryRecord(oldValue, newValue, timestamp))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun append(packageName: String, record: SsaidHistoryRecord) {
        val updated = (records(packageName) + record).takeLast(MAX_RECORDS)
        val array = JSONArray()
        updated.forEach { item ->
            array.put(
                JSONObject()
                    .put(OLD_VALUE_KEY, item.oldValue)
                    .put(NEW_VALUE_KEY, item.newValue)
                    .put(TIMESTAMP_KEY, item.timestamp)
            )
        }
        preferences.edit().putString(packageName, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "ssaid_history"
        const val OLD_VALUE_KEY = "old"
        const val NEW_VALUE_KEY = "new"
        const val TIMESTAMP_KEY = "timestamp"
        const val MAX_RECORDS = 100
    }
}
