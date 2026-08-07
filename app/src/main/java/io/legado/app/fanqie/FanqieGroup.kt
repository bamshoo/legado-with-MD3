package io.legado.app.fanqie

import androidx.room.withTransaction
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup

object FanqieGroup {

    private const val ID_FALLBACK = 1L shl 40

    fun isFanqieGroup(groupId: Long): Boolean {
        val current = FanqieConfig.groupId
        return current > 0 && groupId == current
    }

    suspend fun ensureGroup(): Long {
        FanqieConfig.groupId.takeIf { it > 0 }?.let { id ->
            appDb.bookGroupDao.getByID(id)?.let { group ->
                ensurePrivate(group)
                return id
            }
        }
        // 恢复备份后组可能以其它 id 存在（备份自包含番茄组），按名称找回，避免重复建组
        appDb.bookGroupDao.getByName(FanqieConstants.GROUP_NAME)?.let { group ->
            if (group.groupId > 0) {
                ensurePrivate(group)
                FanqieConfig.groupId = group.groupId
                return group.groupId
            }
        }
        return appDb.withTransaction {
            val groupDao = appDb.bookGroupDao
            val groupId = groupDao.getUnusedId().let { if (it <= 0) ID_FALLBACK else it }
            if (groupDao.getByID(groupId) == null) {
                appDb.bookDao.removeGroup(groupId)
            }
            groupDao.insert(
                BookGroup(
                    groupId = groupId,
                    groupName = FanqieConstants.GROUP_NAME,
                    order = groupDao.maxOrder.plus(1),
                    isPrivate = true,
                )
            )
            FanqieConfig.groupId = groupId
            groupId
        }
    }

    private suspend fun ensurePrivate(group: BookGroup) {
        if (!group.isPrivate) {
            appDb.bookGroupDao.update(group.copy(isPrivate = true))
        }
    }

    /**
     * 备份恢复会把分组表整个 replace 掉。若备份中没有番茄组（旧备份/他机备份），
     * 恢复后立即重建，优先复用原 groupId（保持书籍分组位有效），并按名称去重。
     */
    suspend fun afterRestore() {
        val groupDao = appDb.bookGroupDao
        val groupId = FanqieConfig.groupId
        if (groupId > 0) {
            groupDao.getByID(groupId)?.let { group ->
                if (group.groupName == FanqieConstants.GROUP_NAME) {
                    ensurePrivate(group)
                    return
                }
            }
        }
        val byName = groupDao.getByName(FanqieConstants.GROUP_NAME)
        val newId = when {
            byName != null && byName.groupId > 0 -> byName.groupId
            groupId > 0 && groupDao.getByID(groupId) == null -> groupId
            else -> groupDao.getUnusedId().let { if (it <= 0) ID_FALLBACK else it }
        }
        if (newId <= 0) return
        if (groupDao.getByID(newId) == null) {
            groupDao.insert(
                BookGroup(
                    groupId = newId,
                    groupName = FanqieConstants.GROUP_NAME,
                    order = groupDao.maxOrder.plus(1),
                    isPrivate = true,
                )
            )
        } else {
            groupDao.getByID(newId)?.let { ensurePrivate(it) }
        }
        FanqieConfig.groupId = newId
    }
}
