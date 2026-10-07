package com.github.quiltservertools.ledger.commands

import com.github.quiltservertools.ledger.api.ExtensionManager
import com.github.quiltservertools.ledger.commands.subcommands.CompactCommand
import com.github.quiltservertools.ledger.commands.subcommands.ExportLegacyCommand
import com.github.quiltservertools.ledger.commands.subcommands.HelpCommand
import com.github.quiltservertools.ledger.commands.subcommands.InspectCommand
import com.github.quiltservertools.ledger.commands.subcommands.PageCommand
import com.github.quiltservertools.ledger.commands.subcommands.PlayerCommand
import com.github.quiltservertools.ledger.commands.subcommands.PreviewCommand
import com.github.quiltservertools.ledger.commands.subcommands.PurgeCommand
import com.github.quiltservertools.ledger.commands.subcommands.RestoreCommand
import com.github.quiltservertools.ledger.commands.subcommands.RollbackCommand
import com.github.quiltservertools.ledger.commands.subcommands.SearchCommand
import com.github.quiltservertools.ledger.commands.subcommands.StatusCommand
import com.github.quiltservertools.ledger.commands.subcommands.TeleportCommand
import com.github.quiltservertools.ledger.utility.BrigadierUtils
import com.github.quiltservertools.ledger.utility.Dispatcher
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.Commands.literal

fun registerCommands(dispatcher: Dispatcher) {
    val rootNode =
        literal("ledger").requires(Permissions.require("ledger.commands.root", CommandConsts.PERMISSION_LEVEL))
            // Reoptimization: the root used to have no executor, so `/ledger` on its own
            // answered with "Unknown or incomplete command" and there was no help command
            // to fall back on. Bare `/ledger` now prints the command list instead.
            .executes {
                HelpCommand.sendOverview(it.source)
                1
            }
            .build()

    dispatcher.root.addChild(rootNode)
    dispatcher.root.addChild(
        literal("lg")
            .requires(Permissions.require("ledger.commands.root", CommandConsts.PERMISSION_LEVEL)).redirect(rootNode)
            .build(),
    )

    rootNode.addChild(InspectCommand.build())
    rootNode.addChild(BrigadierUtils.buildRedirect("i", InspectCommand.build()))

    rootNode.addChild(SearchCommand.build())
    rootNode.addChild(BrigadierUtils.buildRedirect("s", SearchCommand.build()))

    rootNode.addChild(PageCommand.build())
    rootNode.addChild(BrigadierUtils.buildRedirect("pg", PageCommand.build()))

    rootNode.addChild(RollbackCommand.build())
    rootNode.addChild(BrigadierUtils.buildRedirect("rb", RollbackCommand.build()))

    rootNode.addChild(PreviewCommand.build())
    rootNode.addChild(BrigadierUtils.buildRedirect("pv", PreviewCommand.build()))

    rootNode.addChild(RestoreCommand.build())

    rootNode.addChild(StatusCommand.build())

    rootNode.addChild(TeleportCommand.build())

    rootNode.addChild(PurgeCommand.build())

    // Reoptimization: migrate legacy text block states to dictionary encoding + vacuum
    rootNode.addChild(CompactCommand.build())

    // Reoptimization: the reverse direction - materialise the legacy text columns so an
    // unmodified upstream build can read this database (see ExportLegacyCommand).
    rootNode.addChild(ExportLegacyCommand.build())

    // Reoptimization: in-game command reference (the mod previously shipped none).
    rootNode.addChild(HelpCommand.build())

    rootNode.addChild(PlayerCommand.build())

    ExtensionManager.commands.forEach {
        it.registerSubcommands().forEach { command ->
            rootNode.addChild(command.build())
        }
    }
}
