package com.github.quiltservertools.ledger.commands.subcommands

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.commands.BuildableCommand
import com.github.quiltservertools.ledger.config.SearchSpec
import com.github.quiltservertools.ledger.config.config
import com.github.quiltservertools.ledger.database.DatabaseManager
import com.github.quiltservertools.ledger.utility.Context
import com.github.quiltservertools.ledger.utility.LiteralNode
import com.github.quiltservertools.ledger.utility.TextColorPallet
import com.github.quiltservertools.ledger.utility.literal
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component

/**
 * Reoptimization: /ledger exportlegacy
 *
 * Writes the dictionary-encoded values back into the legacy `block_state` /
 * `old_block_state` / `extra_data` TEXT columns, so that an unmodified upstream Ledger
 * build can read every row in this database.
 *
 * Why this exists rather than just storing both forms: keeping the redundant text on
 * every row would cost exactly the bytes the dictionary encoding saves, which is the
 * whole point of it. Materialising on demand makes the compatibility cost a one-time,
 * opt-in expense instead of a permanent one - see DatabaseManager.materialiseLegacyColumns.
 *
 * Run /ledger compact afterwards to reclaim the disk space with the restored text.
 *
 * Messages are built with `literal` instead of translation keys on purpose: it keeps
 * this command from touching the four lang JSON files, which are a merge-conflict
 * surface in their own right for no benefit here.
 */
object ExportLegacyCommand : BuildableCommand {
    private const val PROGRESS_INTERVAL_MS = 5_000L

    override fun build(): LiteralNode = literal("exportlegacy")
        .requires(Permissions.require("ledger.commands.purge", config[SearchSpec.purgePermissionLevel]))
        .executes { runExport(it) }
        .build()

    private fun runExport(ctx: Context): Int {
        val source = ctx.source
        source.sendSuccess(
            {
                Component.literal("Materialising legacy text columns - this can take a while.")
                    .setStyle(TextColorPallet.secondary)
            },
            true,
        )
        Ledger.launch {
            var lastReport = System.currentTimeMillis()
            val written = DatabaseManager.materialiseLegacyColumns(
                batchSize = 5000,
                onProgress = { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastReport = now
                        source.sendSuccess(
                            {
                                Component.literal("Restored $done / $total rows")
                                    .setStyle(TextColorPallet.secondary)
                            },
                            false,
                        )
                    }
                },
            )
            source.sendSuccess(
                {
                    Component.literal(
                        if (written == 0L) {
                            "Nothing to do - every row already carries its text columns."
                        } else {
                            "Restored $written rows. An unmodified upstream Ledger can now " +
                                "read this database. Run /ledger compact to reclaim the space."
                        },
                    ).setStyle(TextColorPallet.primary)
                },
                true,
            )
        }
        return 1
    }
}
