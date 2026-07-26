package app.berilo.reader.sync

import app.berilo.reader.store.db.SyncStateDao
import app.berilo.reader.store.db.SyncStateEntity

/** In-memory [SyncStateDao] fake, keeping sync tests off the Robolectric runtime. */
class FakeSyncStateDao : SyncStateDao {
    private val states = mutableMapOf<String, SyncStateEntity>()

    override suspend fun get(entity: String): SyncStateEntity? = states[entity]

    override suspend fun getAll(): List<SyncStateEntity> = states.values.toList()

    override suspend fun upsert(state: SyncStateEntity) {
        states[state.entity] = state
    }

    override suspend fun clear() = states.clear()
}
