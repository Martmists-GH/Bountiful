package ejektaflex.bountiful

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.string
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import ejektaflex.bountiful.data.bounty.BountyData
import ejektaflex.bountiful.data.bounty.BountyEntryItem
import ejektaflex.bountiful.data.json.JsonAdapter
import ejektaflex.bountiful.data.registry.DecreeRegistry
import ejektaflex.bountiful.data.registry.PoolRegistry
import ejektaflex.bountiful.ext.sendErrorMsg
import ejektaflex.bountiful.ext.sendMessage
import ejektaflex.bountiful.ext.supposedlyNotNull
import ejektaflex.bountiful.item.ItemDecree
import ejektaflex.bountiful.network.BountifulNetwork
import ejektaflex.bountiful.network.MessageClipboardCopy
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraftforge.items.ItemHandlerHelper
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.registries.ForgeRegistries
import kotlin.system.measureTimeMillis


object BountifulCommand {

    lateinit var genManager: IResourceManager

    fun generateCommand(dispatcher: CommandDispatcher<CommandSource>) {
        dispatcher.register(
                literal("bo")

                        .then(
                                literal("test")
                                        .requires(::hasPermission)
                                        .executes(dump(true))
                        )

                        .then(
                                literal("decree")
                                        .requires(::hasPermission)
                                        .then(
                                                argument("decType", string())
                                                        .suggests { c, b ->
                                                            for (dec in DecreeRegistry.content) {
                                                                b.suggest(dec.id)
                                                            }
                                                            b.buildFuture()
                                                        }
                                                        .executes { c ->

                                                            val decId = getString(c, "decType")
                                                            val stack = ItemDecree.makeStack(decId)

                                                            if (stack != null) {

                                                                ItemHandlerHelper.giveItemToPlayer(
                                                                        c.source.asPlayer(),
                                                                        stack,
                                                                        c.source.asPlayer().inventory.currentItem
                                                                )

                                                            } else {
                                                                c.source.sendMessage("Decree ID $decId not found")
                                                            }


                                                            1
                                                        }
                                        )
                        )

                        .then(
                                literal("sample")
                                        .requires(::hasPermission)
                                        .then(
                                                argument("decType", string())
                                                        .suggests { c, b ->
                                                            for (dec in DecreeRegistry.content) {
                                                                b.suggest(dec.id)
                                                            }
                                                            b.buildFuture()
                                                        }
                                                        .executes(
                                                                sample(1)
                                                        )
                                                        .then(
                                                                argument("safety", integer())
                                                                        .suggests { c, b ->
                                                                            b.suggest(1)
                                                                            b.suggest(2)
                                                                            b.buildFuture()
                                                                        }
                                                                        .executes(
                                                                                sample(-1)
                                                                        )
                                                        )
                                        )
                        )

                        .then(

                                literal("hand")
                                        .requires(::hasPermission)
                                        .executes(hand())
                        )

                        .then(
                                literal("entities")
                                        .requires(::hasPermission)
                                        .executes(entities())
                        )

        )
    }

    fun hasPermission(c: CommandSourceStack): Boolean {
        return c.hasPermission(2) ||
                (c.entity is Player && (c.entity as Player).isCreative)
    }


    private fun entities() = Command<CommandSourceStack> { ctx ->

        ctx.source.sendSystemMessage(
            Component.literal("Dumping list of entities to ").withStyle(ChatFormatting.GOLD).append(
                Component.literal("/logs/bountiful.log...").withStyle(ChatFormatting.GREEN)
            )
        )

        val time = measureTimeMillis {
            BountifulMod.logFile.appendText("### Entities in Registry: ###")
            for ((eKey, eType) in ForgeRegistries.ENTITY_TYPES.entries) {
                BountifulMod.logFile.appendText("$eKey\n")
            }
        }

        ctx.source.sendSystemMessage(
            Component.literal("Dump complete! Took: ${time}ms").withStyle(ChatFormatting.GOLD)
        )

        1
    }


    private fun hand() = Command<CommandSourceStack> {


        val player = it.source.player

        val holding = player.mainHandItem

        val newEntry = BountyEntryItem().apply {
            content = holding.item.registryName.toString()
            amount = holding.count
            if (holding.hasTag()) {
                jsonNBT = JsonAdapter.parse(holding.tag.toString())
            }
            unitWorth = 1000
        }

        val asText = JsonAdapter.toJson(newEntry)


        BountifulNetwork.channel.send(PacketDistributor.PLAYER.with {
            it.source as ServerPlayer
        }, MessageClipboardCopy(asText))

        val msg = Component.literal("§aItem: §9${holding.item.registryName}§r, §aBounty Entry Copied To Clipboard!§r: §6[hover for preview]§r").apply {
            style.withHoverEvent(
                HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("§6Bounty Entry (Copied to Clipboard):\n").append(
                    Component.literal(asText).withStyle(ChatFormatting.DARK_PURPLE)
                ))
            )
        }

        it.source.sendSystemMessage(msg)

        1
    }

    private fun sample(inSafety: Int) = Command<CommandSourceStack> {

        val safety = if (inSafety < 0) {
            getInteger(it, "safety")
        } else {
            inSafety
        }

        val decreeName = getString(it, "decType")

        it.source.sendSystemMessage(Component.literal("§6Sampling..."))

        val decree = DecreeRegistry.getDecree(decreeName)
        val log = BountifulMod.logger

        if (decree == null) {
            it.source.sendSystemMessage(Component.literal("Decree '$decreeName' does not exist!"))
            return@Command 1
        }

        val objs = DecreeRegistry.getObjectives(listOf(decree))

        if (objs.isEmpty()) {
            it.source.sendSystemMessage(Component.literal("Cannot sample decree '$decreeName' as it has no valid objectives!"))
        }

        val rewards = DecreeRegistry.getRewards(listOf(decree))

        if (rewards.isEmpty()) {
            it.source.sendSystemMessage(Component.literal("Cannot sample decree '$decreeName', as there are no valid rewards for it!"))
        }

        for (reward in rewards) {

            // Since we can have at most 2 objectives, lets assume that worst case all 3 had this value
            val worthToMatch = reward.maxWorth * safety

            val within = BountyData.getObjectivesWithinVariance(
                    DecreeRegistry.getObjectives(listOf(decree)),
                    worthToMatch,
                    0.2
            )

            val nearest = BountyData.pickObjective(supposedlyNotNull(objs), worthToMatch).pick(worthToMatch)

            if (within.isEmpty()) {
                it.source.sendSystemMessage(Component.literal("§cDecree can't handle theoretical bounty of $safety of §4${reward.amountRange.max}x[${reward.content}]§c, next closest obj was: §4${nearest.amount}x[${nearest.content}]§c"))
                it.source.sendSystemMessage(Component.literal("- * §cNeeded: §4$worthToMatch§c, had: §4${nearest.calculatedWorth}§c"))
            } else {
                it.source.sendSystemMessage(Component.literal("§2Matched: 2x[${reward.content}] with ${within.size} objectives!"))
                it.source.sendSystemMessage(Component.literal("- * §5${within.joinToString("§f, §5") { thing -> thing.content }}"))
            }


        }



        1
    }



    // TODO If test is true, warn on invalid pool entries
    private fun dump(test: Boolean = false) = Command<CommandSourceStack> {

        it.source.sendSystemMessage(Component.literal("Dumping Decrees to console"))
        for (decree in DecreeRegistry.content) {
            BountifulMod.logger.info("* $decree")
        }
        it.source.sendSystemMessage(Component.literal("Decrees dumped."))

        it.source.sendSystemMessage(Component.literal("Dumping Pools to console..."))
        for (pool in PoolRegistry.content) {

            val invalid = SetupLifecycle.validatePool(pool, it.source, true)

            if (invalid.isNotEmpty()) {
                it.source.sendSystemMessage(Component.literal("Some items are invalid. Invalid entries have been printed in the log."))

                for (item in invalid) {
                    BountifulMod.logger.warn("Invalid item from pool '${pool.id}': $item")
                }

            }

        }
        it.source.sendSystemMessage(Component.literal("Pools dumped."))

        1
    }


}