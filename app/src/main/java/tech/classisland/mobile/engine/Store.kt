package tech.classisland.mobile.engine

import android.content.Context
import tech.classisland.mobile.model.Profile
import tech.classisland.mobile.model.ProfileParser
import tech.classisland.mobile.model.ProfileWriter
import java.time.LocalDate

object Store {
    private const val PREFS = "classisland_mobile"
    private const val PROFILE_FILE = "profile.json"
    private const val KEY_FIRST_WEEK = "first_week_date"
    private const val KEY_ALERTS = "alerts_enabled"
    private const val KEY_PREP_MINUTES = "prep_minutes"
    private const val KEY_ISLAND = "island_enabled"
    private const val KEY_TEMP = "temp_plans"
    private const val KEY_SUBJECT_COLORS = "subject_colors"
    private const val KEY_FIRED = "alerts_fired"
    private const val KEY_STATUS_NOTIFY = "status_notify"
    private const val KEY_BREAK_LABEL = "break_label"
    private const val KEY_ISLAND_CORNER = "island_corner_dp"
    private const val KEY_ISLAND_SCALE = "island_scale"
    private const val KEY_PROFILES = "profiles_index"

    private lateinit var app: Context

    /**
     * 档案 JSON 缓存：悬浮窗与今日页每秒都会读取档案，
     * 原实现每次都读文件，在低端机上造成主线程 I/O 卡顿。
     * 这里只缓存字符串，loadProfile() 仍返回新解析的对象，
     * 避免调用方（编辑页）在未保存时污染缓存。
     */
    @Volatile
    private var cachedJson: String? = null

    private fun rawJson(): String? {
        if (!hasProfile()) return null
        cachedJson?.let { return it }
        val text = runCatching {
            app.openFileInput(PROFILE_FILE).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
        if (text != null) cachedJson = text
        return text
    }

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun hasProfile(): Boolean = app.getFileStreamPath(PROFILE_FILE).exists()

    fun loadProfile(): Profile? {
        val json = rawJson() ?: return null
        return runCatching { ProfileParser.parse(json) }.getOrNull()
    }

    fun saveProfile(json: String) {
        app.openFileOutput(PROFILE_FILE, Context.MODE_PRIVATE).use { it.write(json.toByteArray()) }
        cachedJson = json
    }

    fun saveProfileBytes(bytes: ByteArray) {
        // 先解析校验（支持 zip 档案与纯 JSON），再统一归一化为 JSON 落盘
        val profile = ProfileParser.parseAny(bytes)
        val json = ProfileWriter.toJson(profile)
        app.openFileOutput(PROFILE_FILE, Context.MODE_PRIVATE).use {
            it.write(json.toByteArray(Charsets.UTF_8))
        }
        cachedJson = json
    }

    /** 第 1 周基准日期，默认为本周一 */
    var firstWeekDate: LocalDate
        get() {
            val s = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_FIRST_WEEK, null)
            return runCatching { LocalDate.parse(s!!) }.getOrNull()
                ?: LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        }
        set(value) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_FIRST_WEEK, value.toString()).apply()
        }

    var alertsEnabled: Boolean
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALERTS, true)
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ALERTS, v).apply()

    /** 预备铃提前分钟数 */
    var prepMinutes: Int
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PREP_MINUTES, 2)
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PREP_MINUTES, v.coerceIn(0, 30)).apply()

    /** 灵动岛悬浮窗开关 */
    var islandEnabled: Boolean
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ISLAND, false)
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ISLAND, v).apply()

    /** 悬浮窗位置，-1 表示未设置（默认顶部居中） */
    var islandX: Int
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("island_x", -1)
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("island_x", v).apply()

    var islandY: Int
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("island_y", -1)
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("island_y", v).apply()

    /** 放学提醒开关 */
    /**
     * 课间时显示的文案（仿官方：课间不显示下节课名，而显示可自定义的状态词）。
     * 默认「课间休息」，可在设置页修改。
     */
    var breakLabel: String
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BREAK_LABEL, null)?.takeIf { it.isNotBlank() } ?: "课间休息"
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BREAK_LABEL, v.trim()).apply()

    /** 灵动岛悬浮窗圆角（dp），0 = 直角，40 = 接近半圆胶囊 */
    var islandCornerDp: Int
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ISLAND_CORNER, 26)
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ISLAND_CORNER, v.coerceIn(0, 40)).apply()

    /** 悬浮窗整体大小倍率（0.7~1.6，步长 0.1），同步缩放字号、内边距、进度条与圆角 */
    var islandScale: Float
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_ISLAND_SCALE, 1f)
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_ISLAND_SCALE, v.coerceIn(0.7f, 1.6f)).apply()

    /** 上课状态常驻通知（无需悬浮窗权限即可看到当前科目与倒计时） */
    var statusNotify: Boolean
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STATUS_NOTIFY, true)
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_STATUS_NOTIFY, v).apply()

    var afterSchoolNotify: Boolean
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("after_school_notify", true)
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("after_school_notify", v).apply()

    /** 自定义通知铃声 URI，空串表示系统默认通知音 */
    var ringtoneUri: String
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ringtone_uri", "") ?: ""
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("ringtone_uri", v).apply()

    /** 通知震动开关 */
    var vibrate: Boolean
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("vibrate", true)
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("vibrate", v).apply()

    /** 上一次使用的通知渠道 id（用于更换铃声后清理旧渠道） */
    var lastAlertChannel: String
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_alert_channel", "") ?: ""
        set(v) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("last_alert_channel", v).apply()

    /** 临时调课：日期(ISO) -> 课表 id；空串表示当天停课；无记录返回 null */
    fun tempPlan(date: java.time.LocalDate): String? {
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TEMP, "{}") ?: "{}"
        return runCatching {
            val obj = org.json.JSONObject(raw)
            if (obj.has(date.toString())) obj.getString(date.toString()) else null
        }.getOrNull()
    }

    fun setTempPlan(date: java.time.LocalDate, planId: String?) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = org.json.JSONObject(prefs.getString(KEY_TEMP, "{}") ?: "{}")
        if (planId == null) obj.remove(date.toString()) else obj.put(date.toString(), planId)
        prefs.edit().putString(KEY_TEMP, obj.toString()).apply()
    }

    fun allTempPlans(): Map<String, String> {
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TEMP, "{}") ?: "{}"
        return runCatching {
            val obj = org.json.JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrDefault(emptyMap())
    }

    /**
     * 提醒去重：闹钟（AlertScheduler）与悬浮窗（OverlayService）都会在临近
     * 上课时尝试发送通知，这里用同一张表去重，保证同一节课只响一次。
     * @return true 表示本次允许发送
     */
    fun tryFireAlert(key: String): Boolean {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()
        val raw = prefs.getString(KEY_FIRED, "{}") ?: "{}"
        val parsed = runCatching { org.json.JSONObject(raw) }.getOrNull()
        val obj: org.json.JSONObject =
            if (parsed != null && parsed.optString("date") == today) parsed
            else org.json.JSONObject().put("date", today)
        val arr = obj.optJSONArray("keys")
            ?: org.json.JSONArray().also { obj.put("keys", it) }
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == key) return false
        }
        arr.put(key)
        prefs.edit().putString(KEY_FIRED, obj.toString()).apply()
        return true
    }

    // ================= 多档案管理（移植官方「档案」功能） =================
    /**
     * 每个档案存为独立文件 profile_<id>.json；profile.json 始终保持为「当前档案」的内容，
     * 因此 loadProfile() 等既有读取逻辑无需任何改动。
     */
    data class ProfileEntry(val id: String, val name: String)

    private fun indexRaw(): org.json.JSONObject =
        runCatching {
            org.json.JSONObject(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROFILES, "{}") ?: "{}")
        }.getOrNull() ?: org.json.JSONObject()

    private fun saveIndex(o: org.json.JSONObject) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, o.toString()).apply()
    }

    private fun indexItems(o: org.json.JSONObject): org.json.JSONArray =
        o.optJSONArray("items") ?: org.json.JSONArray().also { o.put("items", it) }

    private fun fileOf(id: String) = "profile_$id.json"

    /** 首次使用时把已有 profile.json 注册为第一个档案（平滑迁移，不丢数据） */
    fun ensureProfileIndex() {
        if (!hasProfile()) return
        val o = indexRaw()
        if (indexItems(o).length() > 0) return
        val name = runCatching { ProfileParser.parse(rawJson() ?: "{}").name }
            .getOrNull() ?: "我的课表"
        val id = "p" + System.currentTimeMillis().toString(36)
        runCatching {
            val bytes = app.openFileInput(PROFILE_FILE).use { it.readBytes() }
            app.openFileOutput(fileOf(id), Context.MODE_PRIVATE).use { it.write(bytes) }
        }
        indexItems(o).put(org.json.JSONObject().put("id", id).put("name", name))
        o.put("current", id)
        saveIndex(o)
    }

    /** 全部档案（创建顺序） */
    fun profileList(): List<ProfileEntry> {
        ensureProfileIndex()
        val arr = indexItems(indexRaw())
        return (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            ProfileEntry(it.optString("id"), it.optString("name", "未命名"))
        }
    }

    fun currentProfileId(): String {
        ensureProfileIndex()
        return indexRaw().optString("current", "")
    }

    fun currentProfileName(): String =
        profileList().firstOrNull { it.id == currentProfileId() }?.name ?: "我的课表"

    /** 切换档案：把目标档案内容写入 profile.json 并清缓存 */
    fun switchProfile(id: String): Boolean {
        val items = indexItems(indexRaw()).let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
        if (items.none { it.optString("id") == id }) return false
        if (!app.getFileStreamPath(fileOf(id)).exists()) return false
        val bytes = app.openFileInput(fileOf(id)).use { it.readBytes() }
        app.openFileOutput(PROFILE_FILE, Context.MODE_PRIVATE).use { it.write(bytes) }
        cachedJson = null
        val o = indexRaw(); o.put("current", id); saveIndex(o)
        return true
    }

    /** 以当前档案为模板新建并切换 */
    fun duplicateCurrentProfile(newName: String): ProfileEntry? {
        ensureProfileIndex()
        val json = rawJson() ?: return null
        val text = runCatching {
            val pr = ProfileParser.parse(json)
            pr.name = newName
            ProfileWriter.toJson(pr)
        }.getOrElse { json }
        val id = "p" + System.currentTimeMillis().toString(36)
        app.openFileOutput(fileOf(id), Context.MODE_PRIVATE).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
        app.openFileOutput(PROFILE_FILE, Context.MODE_PRIVATE).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
        cachedJson = null
        val o = indexRaw()
        indexItems(o).put(org.json.JSONObject().put("id", id).put("name", newName))
        o.put("current", id); saveIndex(o)
        return ProfileEntry(id, newName)
    }

    /** 重命名当前档案（同时写入官方档案的 Name 字段） */
    fun renameCurrentProfile(newName: String) {
        val id = currentProfileId()
        val o = indexRaw()
        val arr = indexItems(o)
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") == id) { it.put("name", newName); break }
        }
        saveIndex(o)
        runCatching {
            val pr = ProfileParser.parse(rawJson() ?: return@runCatching)
            pr.name = newName
            saveProfile(ProfileWriter.toJson(pr))
        }
    }

    /** 重命名非当前档案（只改索引条目） */
    fun renameProfileName(id: String, newName: String) {
        val o = indexRaw()
        val arr = indexItems(o)
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") == id) { it.put("name", newName); break }
        }
        saveIndex(o)
    }

    /** 编辑保存后调用：把当前档案名同步到索引与独立文件 */
    fun syncCurrentProfileName(name: String) {
        val n = name.trim().ifEmpty { "我的课表" }
        val id = currentProfileId()
        val o = indexRaw()
        val arr = indexItems(o)
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") == id) { it.put("name", n); break }
        }
        saveIndex(o)
        val cur = rawJson() ?: return
        runCatching {
            val pr = ProfileParser.parse(cur)
            pr.name = n
            val text = ProfileWriter.toJson(pr)
            saveProfile(text)
            app.openFileOutput(fileOf(id), Context.MODE_PRIVATE).use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** 删除档案（至少保留一个；删除当前档案时自动切到第一份） */
    fun deleteProfile(id: String): Boolean {
        val o = indexRaw()
        val arr = indexItems(o)
        if (arr.length() <= 1) return false
        val keep = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") != id) keep.put(it)
        }
        if (keep.length() == arr.length()) return false
        o.put("items", keep)
        app.deleteFile(fileOf(id))
        saveIndex(o)
        if (o.optString("current") == id) {
            val first = keep.optJSONObject(0)?.optString("id") ?: ""
            if (first.isNotEmpty()) switchProfile(first)
        }
        return true
    }

    /** 科目自定义底色，未设置返回 null */
    fun subjectColor(id: String): Int? {
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SUBJECT_COLORS, "{}") ?: "{}"
        return runCatching {
            val obj = org.json.JSONObject(raw)
            if (obj.has(id)) obj.getInt(id) else null
        }.getOrNull()
    }

    fun setSubjectColor(id: String, color: Int?) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = org.json.JSONObject(prefs.getString(KEY_SUBJECT_COLORS, "{}") ?: "{}")
        if (color == null) obj.remove(id) else obj.put(id, color)
        prefs.edit().putString(KEY_SUBJECT_COLORS, obj.toString()).apply()
    }
}
