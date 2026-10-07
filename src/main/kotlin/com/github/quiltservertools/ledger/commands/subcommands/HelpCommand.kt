package com.github.quiltservertools.ledger.commands.subcommands

import com.github.quiltservertools.ledger.commands.BuildableCommand
import com.github.quiltservertools.ledger.commands.CommandConsts
import com.github.quiltservertools.ledger.utility.Context
import com.github.quiltservertools.ledger.utility.LiteralNode
import com.github.quiltservertools.ledger.utility.TextColorPallet
import com.github.quiltservertools.ledger.utility.literal
import com.mojang.brigadier.arguments.StringArgumentType
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

/**
 * Reoptimization: `/ledger help` - the in-game command reference.
 *
 * The mod shipped no help of any kind. The root command had no executor, so typing
 * `/ledger` on its own replied with a Brigadier syntax error, and there was no `help`
 * subcommand to fall back on. The only way to find out what a command did was to read
 * the wiki in a browser.
 *
 * This adds the reference in game:
 *   /ledger               -> command list (the bare command now works instead of erroring)
 *   /ledger help          -> same list
 *   /ledger help <cmd>    -> one command in detail: what it does, its syntax, aliases,
 *                            and the permission node it checks
 *
 * Descriptions live in the language files under `text.ledger.help.*`, so they follow the
 * player's language. Only en_us and zh_cn carry the strings and the rest fall back to
 * en_us, which is how the mod's other messages already work; that keeps this from
 * touching the nine other language files for no benefit.
 */
object HelpCommand : BuildableCommand {

    /**
     * The command table.
     *
     * `permission` is the node actually checked in the command's own source - the list is
     * a transcription, not a second source of truth, so it cannot drift silently without
     * someone editing two places at once. `configurable` marks the nodes whose fallback
     * level comes from the config rather than [CommandConsts.PERMISSION_LEVEL].
     */
    private data class Entry(
        val name: String,
        val aliases: List<String>,
        val permission: String,
        val configurable: Boolean = false,
    )

    private val entries = listOf(
        Entry("inspect", listOf("i"), "ledger.commands.inspect"),
        Entry("search", listOf("s"), "ledger.commands.search"),
        Entry("page", listOf("pg"), "ledger.commands.page"),
        Entry("rollback", listOf("rb"), "ledger.commands.rollback"),
        Entry("restore", emptyList(), "ledger.commands.rollback"),
        Entry("preview", listOf("pv"), "ledger.commands.preview"),
        Entry("status", emptyList(), "ledger.commands.status"),
        Entry("tp", emptyList(), "ledger.commands.tp"),
        Entry("player", emptyList(), "ledger.commands.player"),
        Entry("purge", emptyList(), "ledger.commands.purge", configurable = true),
        // Both of these are this fork's additions, listed last so the upstream commands
        // read as the primary set.
        Entry("compact", emptyList(), "ledger.commands.purge", configurable = true),
        Entry("exportlegacy", emptyList(), "ledger.commands.purge", configurable = true),
    )

    private fun detailKey(entry: Entry, field: String) = "text.ledger.help.cmd.${entry.name}.$field"

    override fun build(): LiteralNode = literal("help")
        .requires(Permissions.require("ledger.commands.root", CommandConsts.PERMISSION_LEVEL))
        .executes {
            sendOverview(it.source)
            1
        }
        .then(
            argument("command", StringArgumentType.word())
                .suggests { _, builder ->
                    SharedSuggestionProvider.suggest(entries.map { it.name }, builder)
                }
                .executes {
                    sendDetail(it, StringArgumentType.getString(it, "command"))
                },
        )
        .build()

    /**
     * Reoptimization: also wired to the root node, so `/ledger` on its own prints this
     * instead of `Unknown or incomplete command`.
     */
    fun sendOverview(source: CommandSourceStack) {
        source.sendSuccess(
            { Component.translatable("text.ledger.help.title").setStyle(TextColorPallet.primary) },
            false,
        )
        for (entry in entries) {
            val alias = if (entry.aliases.isEmpty()) "" else " (${entry.aliases.joinToString(", ")})"
            source.sendSuccess(
                {
                    Component.literal("  /ledger ${entry.name}$alias ")
                        .setStyle(TextColorPallet.secondary)
                        .append(
                            Component.translatable(detailKey(entry, "desc"))
                                .setStyle(TextColorPallet.primaryVariant),
                        )
                },
                false,
            )
        }
        source.sendSuccess(
            { Component.translatable("text.ledger.help.params").setStyle(TextColorPallet.secondary) },
            false,
        )
        source.sendSuccess(
            { Component.translatable("text.ledger.help.hint").setStyle(TextColorPallet.light) },
            false,
        )
    }

    private fun sendDetail(ctx: Context, name: String): Int {
        val source = ctx.source

        // `help help` is not worth a table row, but answering it with "unknown command"
        // after the user just typed the word would be silly.
        if (name.equals("help", ignoreCase = true)) {
            sendOverview(source)
            return 1
        }

        val entry = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: entries.firstOrNull { e -> e.aliases.any { it.equals(name, ignoreCase = true) } }

        if (entry == null) {
            source.sendFailure(
                Component.translatable("text.ledger.help.unknown", name).setStyle(TextColorPallet.secondary),
            )
            return 0
        }

        source.sendSuccess(
            {
                Component.translatable("text.ledger.help.detail_title", entry.name)
                    .setStyle(TextColorPallet.primary)
            },
            false,
        )
        source.sendSuccess(
            { Component.translatable(detailKey(entry, "desc")).setStyle(TextColorPallet.secondary) },
            false,
        )
        source.sendSuccess(
            {
                Component.translatable("text.ledger.help.usage_label")
                    .setStyle(TextColorPallet.light)
                    .append(
                        Component.translatable(detailKey(entry, "usage"))
                            .setStyle(TextColorPallet.primaryVariant),
                    )
            },
            false,
        )
        if (entry.aliases.isNotEmpty()) {
            source.sendSuccess(
                {
                    Component.translatable("text.ledger.help.aliases_label")
                        .setStyle(TextColorPallet.light)
                        .append(
                            Component.literal(
                                entry.aliases.joinToString(", "),
                            ).setStyle(TextColorPallet.primaryVariant),
                        )
                },
                false,
            )
        }
        source.sendSuccess(
            {
                val permission = if (entry.configurable) {
                    Component.translatable("text.ledger.help.permission_configurable", entry.permission)
                } else {
                    Component.translatable(
                        "text.ledger.help.permission",
                        entry.permission,
                        CommandConsts.PERMISSION_LEVEL,
                    )
                }
                Component.translatable("text.ledger.help.permission_label")
                    .setStyle(TextColorPallet.light)
                    .append(permission.setStyle(TextColorPallet.primaryVariant))
            },
            false,
        )
        // Only the filtering commands take the shared parameter set, so the pointer is
        // shown where it is relevant rather than on every page.
        if (entry.name in setOf("search", "rollback", "restore", "purge", "preview")) {
            source.sendSuccess(
                { Component.translatable("text.ledger.help.params").setStyle(TextColorPallet.secondary) },
                false,
            )
        }
        return 1
    }
}
