package com.github.quiltservertools.ledger.database

import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import net.minecraft.resources.Identifier
import java.util.*

object DatabaseCacheService {
    val actionIdentifierKeys: BiMap<String, Int> = HashBiMap.create()

    val worldIdentifierKeys: BiMap<Identifier, Int> = HashBiMap.create()

    val objectIdentifierKeys: BiMap<Identifier, Int> = HashBiMap.create()

    val sourceKeys: BiMap<String, Int> = HashBiMap.create()

    val playerKeys: BiMap<UUID, Int> = HashBiMap.create()

    val playernameKeys: BiMap<String, Int> = HashBiMap.create()

    /**
     * Reoptimization: dictionary-encoded block states (state string <-> block_states.id).
     * Keeps the actions table rows narrow (int ref instead of a full state string per row).
     */
    val blockStateKeys: BiMap<String, Int> = HashBiMap.create()

    /**
     * Reoptimization: dictionary-encoded `extra_data` values (value <-> extra_data_dict.id).
     * Bounded deliberately: values are large, so an unbounded map would keep every
     * distinct NBT blob in memory for the life of the server.
     */
    val extraDataKeys: BiMap<String, Int> = HashBiMap.create()

    /**
     * How many `extra_data` values to keep resident. Unlike block states, these are
     * hundreds of bytes each, so the map is capped and cleared wholesale when it fills
     * rather than growing without limit. Rebuilding it costs dictionary lookups, not
     * correctness: a miss simply re-reads the id from the table.
     */
    const val EXTRA_DATA_CACHE_LIMIT = 4096

    fun rememberExtraData(value: String, id: Int) {
        if (extraDataKeys.size >= EXTRA_DATA_CACHE_LIMIT) extraDataKeys.clear()
        extraDataKeys[value] = id
    }
}
