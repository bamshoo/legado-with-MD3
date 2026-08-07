package io.legado.app.data.repository

import io.legado.app.data.dao.BookGroupDao
import io.legado.app.data.entities.BookGroup
import io.legado.app.fanqie.FanqieConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookGroupRepository(private val bookGroupDao: BookGroupDao) {

    fun flowAll(): Flow<List<BookGroup>> {
        return bookGroupDao.flowAll()
    }

    fun flowSelect(): Flow<List<BookGroup>> {
        val fanqieGroupId = FanqieConfig.groupId
        return bookGroupDao.flowSelect().map { groups ->
            if (fanqieGroupId > 0) groups.filterNot { it.groupId == fanqieGroupId } else groups
        }
    }

    fun flowShow(): Flow<List<BookGroup>> {
        return bookGroupDao.flowShow()
    }

    suspend fun update(vararg bookGroup: BookGroup) {
        bookGroupDao.update(*bookGroup)
    }

    suspend fun insert(vararg bookGroup: BookGroup) {
        bookGroupDao.insert(*bookGroup)
    }

    suspend fun delete(vararg bookGroup: BookGroup) {
        bookGroupDao.delete(*bookGroup)
    }

    suspend fun getUnusedId(): Long {
        return bookGroupDao.getUnusedId()
    }

    fun getMaxOrder(): Int {
        return bookGroupDao.maxOrder
    }

    suspend fun getByID(id: Long): BookGroup? {
        return bookGroupDao.getByID(id)
    }

    suspend fun getIdsSum(): Long {
        return bookGroupDao.idsSum
    }

    suspend fun getGroupNames(id: Long): List<String> {
        return bookGroupDao.getGroupNames(id)
    }

    suspend fun clearCover(groupId: Long) {
        bookGroupDao.clearCover(groupId)
    }
}
