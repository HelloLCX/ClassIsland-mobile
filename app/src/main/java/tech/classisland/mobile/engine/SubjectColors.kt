package tech.classisland.mobile.engine

/**
 * 科目配色。颜色为本地个性化设置（ClassIsland 官方档案本身不含科目颜色），
 * 不写入导出的档案，保证与桌面端档案格式兼容。
 */
object SubjectColors {

    /** 柔和底色（与深色文字搭配） */
    val PALETTE = intArrayOf(
        0xFFFDE2E2.toInt(), // 浅红
        0xFFFFEFD9.toInt(), // 浅橙
        0xFFFCF6C8.toInt(), // 浅黄
        0xFFDFF5E1.toInt(), // 浅绿
        0xFFD6F0EE.toInt(), // 浅青
        0xFFDEE9FB.toInt(), // 浅蓝
        0xFFEAE2FB.toInt(), // 浅紫
        0xFFFCE0F0.toInt(), // 浅粉
        0xFFEDE3D8.toInt(), // 浅棕
        0xFFE5E7EB.toInt()  // 浅灰
    )

    /** 深色描边/文字色，与底色一一对应 */
    val PALETTE_DEEP = intArrayOf(
        0xFFE06C75.toInt(),
        0xFFE8924A.toInt(),
        0xFFD4B53F.toInt(),
        0xFF5CB867.toInt(),
        0xFF3FAE9E.toInt(),
        0xFF5B8DEF.toInt(),
        0xFF9277E0.toInt(),
        0xFFE06CA8.toInt(),
        0xFFA07B58.toInt(),
        0xFF8A94A6.toInt()
    )

    /** 自动配色：按名称稳定哈希选色 */
    fun autoColor(name: String): Int {
        val idx = (name.hashCode() % PALETTE.size + PALETTE.size) % PALETTE.size
        return PALETTE[idx]
    }

    fun autoColorDeep(name: String): Int {
        val idx = (name.hashCode() % PALETTE_DEEP.size + PALETTE_DEEP.size) % PALETTE_DEEP.size
        return PALETTE_DEEP[idx]
    }

    /** 科目实际底色：自定义优先，否则按名称自动 */
    fun bg(subjectId: String?, name: String): Int {
        if (subjectId.isNullOrEmpty()) return 0xFFF1F5F9.toInt()
        Store.subjectColor(subjectId)?.let { return it }
        return autoColor(name)
    }

    fun deep(subjectId: String?, name: String): Int {
        if (subjectId.isNullOrEmpty()) return 0xFF94A3B8.toInt()
        Store.subjectColor(subjectId)?.let { saved ->
            val idx = PALETTE.indexOf(saved)
            if (idx >= 0) return PALETTE_DEEP[idx]
        }
        return autoColorDeep(name)
    }
}
