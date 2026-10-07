package com.github.quiltservertools.ledger.database

import net.minecraft.resources.Identifier
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

private const val MAX_PLAYER_NAME_LENGTH = 16
private const val MAX_ACTION_NAME_LENGTH = 16
private const val MAX_IDENTIFIER_LENGTH = 191
private const val MAX_SOURCE_NAME_LENGTH = 30
private const val MAX_BLOCK_STATE_LENGTH = 500

/**
 * Reoptimization: size of the content hash used as the dictionary key.
 *
 * 16 bytes = 128 bits. A 64-bit hash would collide with probability ~1 in 10^5 across
 * 20 million entries, which is too high to treat as impossible; 128 bits is ~10^-21.
 */
private const val EXTRA_DATA_HASH_LENGTH = 16

/**
 * Reoptimization: upper bound on a stored `extra_data` payload.
 *
 * 65535 is the ceiling for MySQL BLOB/TEXT, and Ledger supports MySQL via the
 * Ledger Databases extension, so a value larger than this is left inline rather than
 * failing the insert on that backend. gzip is applied before this cap is checked.
 */
private const val MAX_EXTRA_DATA_PAYLOAD_LENGTH = 65535

object Tables {
    object Players : IntIdTable("players") {
        val playerId = uuid("player_id").uniqueIndex()
        val playerName = varchar("player_name", MAX_PLAYER_NAME_LENGTH)
        val firstJoin = timestamp("first_join").clientDefault { Instant.now() }
        val lastJoin = timestamp("last_join").clientDefault { Instant.now() }
    }

    class Player(id: EntityID<Int>) : IntEntity(id) {
        var playerId by Players.playerId
        var playerName by Players.playerName
        var firstJoin by Players.firstJoin
        var lastJoin by Players.lastJoin

        companion object : IntEntityClass<Player>(Players)
    }

    object ActionIdentifiers : IntIdTable() {
        val actionIdentifier = varchar("action_identifier", MAX_ACTION_NAME_LENGTH).uniqueIndex()
    }

    class ActionIdentifier(id: EntityID<Int>) : IntEntity(id) {
        var identifier by ActionIdentifiers.actionIdentifier

        companion object : IntEntityClass<ActionIdentifier>(ActionIdentifiers)
    }

    object ObjectIdentifiers : IntIdTable() {
        val identifier = varchar("identifier", MAX_IDENTIFIER_LENGTH).uniqueIndex()
    }

    public val oldObjectTable = ObjectIdentifiers.alias("oldObjects")

    class ObjectIdentifier(id: EntityID<Int>) : IntEntity(id) {
        var identifier by ObjectIdentifiers.identifier.transform({ it.toString() }, { Identifier.tryParse(it)!! })

        companion object : IntEntityClass<ObjectIdentifier>(ObjectIdentifiers)
    }

    /**
     * Reoptimization: dictionary table for block state strings.
     * The actions table stores an int reference instead of repeating the full state
     * string on every row (CoreProtect-style dictionary encoding).
     */
    object BlockStates : IntIdTable("block_states") {
        val state = varchar("state", MAX_BLOCK_STATE_LENGTH).uniqueIndex()
    }

    class BlockState(id: EntityID<Int>) : IntEntity(id) {
        var state by BlockStates.state

        companion object : IntEntityClass<BlockState>(BlockStates)
    }

    /**
     * Reoptimization: content-addressed dictionary for long `extra_data` values.
     *
     * `extra_data` holds serialised NBT - container contents and entity kills - and on a
     * real server it is the single largest field, which is also what upstream PR #291
     * identified. Values are deduplicated by a 128-bit content hash rather than by
     * comparing the payload, so a lookup is one index probe and never reads the blob.
     *
     * 128 bits rather than 64: a 64-bit hash collides about 1 in 10^5 across 20 million
     * dictionary entries, which is too likely to treat as impossible; 128 bits puts it
     * around 10^-21. (PR #291 used a 32-bit Java hashCode plus a payload comparison.)
     *
     * `payload` is the value's UTF-8 bytes, optionally gzip-compressed, prefixed by one
     * header byte recording which. That avoids PR #291's separate `gzip` column - a
     * whole extra column per dictionary row to store one bit.
     */
    object ExtraDataDict : IntIdTable("extra_data_dict") {
        val hash = binary("content_hash", EXTRA_DATA_HASH_LENGTH).uniqueIndex()
        val payload = binary("payload", MAX_EXTRA_DATA_PAYLOAD_LENGTH)
    }

    /**
     * Reoptimization: reference into [ExtraDataDict] for rows whose `extra_data` is long
     * enough to be worth deduplicating, see [Actions.extraDataRef].
     */
    object Actions : IntIdTable("actions") {
        val actionIdentifier = reference("action_id", ActionIdentifiers.id).index()
        val timestamp = timestamp("time")
        val x = integer("x")
        val y = integer("y")
        val z = integer("z")
        val world = reference("world_id", Worlds.id)
        val objectId = reference("object_id", ObjectIdentifiers.id).index()
        val oldObjectId = reference("old_object_id", ObjectIdentifiers.id).index()

        /**
         * Reoptimization: the same instant as [timestamp], stored as epoch milliseconds.
         *
         * This exists purely for indexing. The legacy `time` column holds TEXT
         * ("2026-10-05 13:22:21.702", ~23 bytes), and an index keyed on it costs
         * ~35 bytes per row - 20% of the whole database on a 13,500-row workload.
         * An INTEGER key costs ~15 bytes per row, so all time filtering is done on
         * this column while `time` is kept in sync for display and compatibility.
         */
        val timeMs = long("time_ms").index("actions_time_ms")

        // Reoptimization: nullable legacy text columns kept for backwards compatibility.
        // New writes store the dictionary id in the *_ref columns and null here.
        val blockState = text("block_state").nullable()
        val oldBlockState = text("old_block_state").nullable()
        val blockStateRef = integer("block_state_ref").nullable()
        val oldBlockStateRef = integer("old_block_state_ref").nullable()

        val sourceName = reference("source", Sources.id).index()
        val sourcePlayer = optReference("player_id", Players.id).index()
        val extraData = text("extra_data").nullable()
        val extraDataRef = integer("extra_data_ref").nullable()
        val rolledBack = bool("rolled_back").clientDefault { false }

        init {
            // Reoptimization note: the composite (dimension, time) indexes that an
            // earlier revision of this branch introduced were reverted. Ledger stored
            // the timestamp as TEXT, so every additional index column carried ~23
            // bytes per row; measured on a 13,500-row workload they inflated the
            // index footprint from ~1.45 MB to ~3.29 MB (+78% total file size) with
            // no measurable lookup win.
            //
            // The time column is the one place where that cost was worth removing
            // rather than avoiding: `time_ms` (INTEGER) now carries the index, which
            // is ~20 bytes per row cheaper than indexing the TEXT form.
            index("actions_by_location", false, x, y, z, world)
        }
    }

    class Action(id: EntityID<Int>) : IntEntity(id) {
        var actionIdentifier by ActionIdentifier referencedOn Actions.actionIdentifier
        var timestamp by Actions.timestamp
        var timeMs by Actions.timeMs
        var x by Actions.x
        var y by Actions.y
        var z by Actions.z
        var world by World referencedOn Actions.world
        var objectId by ObjectIdentifier referencedOn Actions.objectId
        var oldObjectId by ObjectIdentifier referencedOn Actions.oldObjectId
        var blockState by Actions.blockState
        var oldBlockState by Actions.oldBlockState
        var sourceName by Source referencedOn Actions.sourceName
        var sourcePlayer by Player optionalReferencedOn Actions.sourcePlayer
        var extraData by Actions.extraData
        var rolledBack by Actions.rolledBack

        companion object : IntEntityClass<Action>(Actions)
    }

    object Sources : IntIdTable("sources") {
        val name = varchar("name", MAX_SOURCE_NAME_LENGTH).uniqueIndex()
    }

    class Source(id: EntityID<Int>) : IntEntity(id) {
        var name by Sources.name

        companion object : IntEntityClass<Source>(Sources)
    }

    object Worlds : IntIdTable("worlds") {
        val identifier = varchar("identifier", MAX_IDENTIFIER_LENGTH).uniqueIndex()
    }

    class World(id: EntityID<Int>) : IntEntity(id) {
        var identifier by Worlds.identifier.transform({ it.toString() }, { Identifier.tryParse(it)!! })

        companion object : IntEntityClass<World>(Worlds)
    }
}
