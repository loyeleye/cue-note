package com.cuenote.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One teacher's settings for the student's suggested practice, as the cloud has them. */
@Serializable
data class TeacherPlanSetting(
    @SerialName("teacher_id") val teacherId: String,
    @SerialName("pinned_feedback_id") val pinnedFeedbackId: String? = null,
    val minutes: Int? = null,
    @SerialName("days_per_week") val daysPerWeek: Int? = null,
    @SerialName("max_minutes") val maxMinutes: Int? = null,
    /** yyyy-MM-dd */
    @SerialName("rest_until") val restUntil: String? = null,
    @SerialName("gentle_start") val gentleStart: String? = null,
    @SerialName("cool_down") val coolDown: String? = null,
)

/**
 * What the student's teachers set, taken together: every teacher's main
 * point, the strictest length cap, the latest rest date, and the first
 * gentle start and cool-down someone wrote.
 */
data class PlanSettings(
    val pinned: Set<String> = emptySet(),
    val maxMinutes: Int? = null,
    val restUntil: String? = null,
    val gentleStart: String? = null,
    val coolDown: String? = null,
) {
    /** Listening only today: a teacher set rest until today or later. */
    fun resting(today: String): Boolean = restUntil != null && restUntil >= today

    companion object {
        fun of(rows: List<TeacherPlanSetting>) = PlanSettings(
            pinned = rows.mapNotNull { it.pinnedFeedbackId }.toSet(),
            maxMinutes = rows.mapNotNull { it.maxMinutes }.minOrNull(),
            restUntil = rows.mapNotNull { it.restUntil }.maxOrNull(),
            gentleStart = rows.firstNotNullOfOrNull { it.gentleStart?.takeIf { s -> s.isNotBlank() } },
            coolDown = rows.firstNotNullOfOrNull { it.coolDown?.takeIf { s -> s.isNotBlank() } },
        )
    }
}

/** The teachers' settings, kept on the phone so the plan works offline. PracticeSyncWorker refreshes it. */
class PlanSettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("plan-settings", Context.MODE_PRIVATE)
    private val ser = ListSerializer(TeacherPlanSetting.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<PlanSettings> = _settings.asStateFlow()

    fun set(rows: List<TeacherPlanSetting>) {
        prefs.edit().putString(KEY, json.encodeToString(ser, rows)).apply()
        _settings.value = PlanSettings.of(rows)
    }

    private fun read(): PlanSettings = runCatching {
        PlanSettings.of(json.decodeFromString(ser, prefs.getString(KEY, null) ?: "[]"))
    }.getOrDefault(PlanSettings())

    companion object {
        private const val KEY = "rows"
        @Volatile private var instance: PlanSettingsStore? = null

        /** One store for the app and its sync worker, so a refresh shows at once. */
        fun get(context: Context): PlanSettingsStore = instance ?: synchronized(this) {
            instance ?: PlanSettingsStore(context.applicationContext).also { instance = it }
        }
    }
}
