package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.db.entity.MemoryEntity

/**
 * 测试用 Fake MemoryDAO 接口。
 * 仅用于 MemoryRepositoryOwnershipTest 中的 mock 行为验证。
 * 实际生产使用 MemoryDAO 接口（在 dao/MemoryDAO.kt 中定义）。
 */
// This file is intentionally minimal - it's only used for test structure reference.
// The actual FakeMemoryDAO is defined inline in the test class.
