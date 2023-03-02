package io.ejekta.bountiful.content

import io.ejekta.bountiful.Bountiful
import io.ejekta.bountiful.bounty.BountyData
import io.ejekta.bountiful.bounty.BountyDataEntry
import io.ejekta.bountiful.config.BountifulIO
import io.ejekta.bountiful.data.Decree
import io.ejekta.bountiful.data.Pool
import io.ejekta.bountiful.data.PoolEntry
import io.ejekta.bountiful.util.randomSplit
import io.ejekta.bountiful.util.weightedRandomDblBy
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import kotlin.math.ceil

class BountyCreator private constructor(
    private val world: ServerWorld,
    private val pos: BlockPos,
    private val decrees: Set<Decree>,
    private val rep: Int,
    private val startTime: Long = 0L
) {

    var data = BountyData()

    fun create(): BountyData {
        data = BountyData()

        // Gen reward entries and max rarity
        val rewardEntries = genRewardEntries()

        if (rewardEntries.isEmpty()) {
            Bountiful.LOGGER.error("Rewards are empty, can only generate an empty reward")
            return data
        }

        data.rarity = rewardEntries.maxOf { it.rarity }

        // Gen rewards and total worth
        val rewards = genRewards(rewardEntries)
        val totalRewardWorth = rewards.sumOf { it.worth }
        data.rewards.addAll(rewards)

        // return early if we have no rewards :(
        if (rewards.isEmpty()) {
            return data
        }

        // Gen objectives
        val objectives = genObjectives(
            totalRewardWorth * (1 + (BountifulIO.configData.objectiveModifier * 0.01)),
            rewardEntries
        )
        data.objectives.addAll(objectives)

        data.timeStarted = startTime
        data.timeToComplete += 15000L + BountifulIO.configData.flatBonusTimePerBounty


        return data
    }

    private fun genRewards(entries: List<PoolEntry>): List<BountyDataEntry> {
        return entries.map { it.toEntry(world, pos) }
    }

    private fun genRewardEntries(): List<PoolEntry> {
        val rewards = getRewardsFor(decrees)

        if (rewards.isEmpty()) {
            return emptyList()
        }

        // Num rewards to give
        val numRewards = (1..BountifulIO.configData.maxNumRewards).random()
        val toReturn = mutableListOf<PoolEntry>()

        for (i in 0 until numRewards) {
            val totalRewards = rewards.filter {
                it.content !in toReturn.map { alreadyAdded -> alreadyAdded.content }
                        && rep >= it.repRequired
            }

            // Return if there's nothing to pick
            if (totalRewards.isEmpty()) {
                break
            }

            val picked = totalRewards.weightedRandomDblBy {
                weightMult * rarity.weightAdjustedFor(rep)
            }

            toReturn.add(picked)
        }
        return toReturn
    }

    private fun getAllPossibleObjectives(rewardPools: List<PoolEntry>): List<PoolEntry> {
        return getObjectivesFor(decrees).filter {
            it.content !in data.rewards.map { rew -> rew.content }
        }.filter { entry ->
            // obj entry can not be in any reward forbidlist
            // no rew entry can be in this obj entry's forbidlist either
            !entry.forbidsAny(world, rewardPools) && !rewardPools.any { it.forbids(world, entry) }
        }
    }

    private fun genObjectives(worth: Double, rewardPools: List<PoolEntry>): List<BountyDataEntry> {
        // -30 = 150% / 1.5x needed, 30 = 50% / 0.5x needed
        // 1 - (rep / 60.0)
        val objNeededMult = getDiscount(rep)
        val worthNeeded = worth * objNeededMult
        val numObjectives = (1..2).random()
        val toReturn = mutableListOf<BountyDataEntry>()

        val objs = getAllPossibleObjectives(rewardPools)

        val worthGroups = randomSplit(worthNeeded, numObjectives).toMutableList()

        while (worthGroups.isNotEmpty()) {
            val w = worthGroups.removeAt(0)

            val alreadyPicked = toReturn.map { it.content }
            val unpicked = objs.filter { it.content !in alreadyPicked }


            if (unpicked.isEmpty()) {
                //println("Ran out of objectives to pick from! Already picked: $alreadyPicked")
                break
            }

            val picked = pickObjective(unpicked, w)
            val entry = picked.toEntry(world, pos, w)

            // Add time based on entry
            data.timeToComplete += (picked.timeMult * entry.worth).toLong() * 7

            // Append on a new worth to add obj for
            // if we still haven't fulfilled it
            if (entry.worth < w * 0.5) {
                worthGroups.add(w - entry.worth)
            }

            toReturn.add(entry)
        }

        return toReturn
    }

    private fun pickObjective(objs: List<PoolEntry>, worth: Double): PoolEntry {
        val variance = 0.25
        val inVariance = getObjectivesWithinVariance(objs, worth, variance)

        // Picks a random pool within the variance. If none exist, get the objective with the closest worth distance.
        val picked = if (inVariance.isNotEmpty()) {
            inVariance.weightedRandomDblBy {
                weightMult * rarity.weightAdjustedFor(rep)
            }
        } else {
            objs.minByOrNull { it.worthDistanceFrom(worth) }!!
        }

        return picked
    }

    companion object {

        // cap rep discount at 30/75, or 40%
        fun getDiscount(rep: Int): Double {
            return 1 - (rep / 75.0)
        }

        fun create(world: ServerWorld, pos: BlockPos, decrees: Set<Decree>, rep: Int, startTime: Long = 0L): BountyData {
            return BountyCreator(world, pos, decrees, rep.coerceIn(-30..30), startTime).create()
        }

        private fun getObjectivePoolsFor(decrees: Set<Decree>): Set<Pool> {
            return decrees.map { it.objectivePools }.flatten().toSet()
        }

        private fun getRewardPoolsFor(decrees: Set<Decree>): Set<Pool> {
            return decrees.map { it.rewardPools }.flatten().toSet()
        }

        private fun getRewardsFor(decrees: Set<Decree>): Set<PoolEntry> {
            return getRewardPoolsFor(decrees).map { it.content }.flatten().filter { it.type.isReward }.toSet()
        }

        private fun getObjectivesFor(decrees: Set<Decree>): Set<PoolEntry> {
            return getObjectivePoolsFor(decrees).map { it.content }.flatten().filter { it.type.isObj }.toSet()
        }

        private fun getObjectivesWithinVariance(objs: List<PoolEntry>, worth: Double, variance: Double): List<PoolEntry> {
            val wRange = ceil(worth * variance)
            val objGroups = objs.groupBy { it.worthDistanceFrom(worth) }
            val groupsInRange = objGroups.filter { it.key <= wRange }.values
            return groupsInRange.flatten()
        }

    }

}