package io.ejekta.bountiful.chaos

import io.ejekta.kambrik.ext.identifier
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.recipe.RecipeEntry
import net.minecraft.recipe.RecipeManager
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import kotlin.jvm.optionals.getOrNull

class DepthSolver(val server: MinecraftServer, val data: BountifulChaosData, val info: BountifulChaosInfo) {

    private val recipeManager: RecipeManager = server.recipeManager
    private val regManager = server.registryManager

    private val terminators = mutableSetOf<Identifier>()
    private val deps = mutableMapOf<Identifier, MutableSet<Identifier>>()

    // Final cost map
    private val costMap = mutableMapOf<Item, Double>()
    private val matchCosts = mutableMapOf<Item, Double>()

    fun costOf(item: Item): Double? {
        return costMap[item] ?: matchCosts[item]
    }

    // Populate cost map from config
    init {
        println("Init solve system..")

        for (item in server.registryManager.get(Registries.ITEM.key)) {
            val matchCost = data.matching.matchCost(item, server)
            if (matchCost != null && !data.matching.isIgnored(item)) {
                println("Adding match cost: ${item.identifier}")
                matchCosts[item] = matchCost
            }
        }

        for (itemId in data.required.filter { it.value != null }.keys) {
            val item = server.registryManager.get(Registries.ITEM.key).getOrEmpty(itemId).getOrNull()
            item?.let {
                if (it !in matchCosts) {
                    costMap[it] = data.required[itemId]!!
                }
            }
        }
    }

    private val ItemStack.recipes: List<RecipeEntry<*>>
        get() = stackLookup[this.item] ?: emptyList()

    private val RecipeEntry<*>.inItemSets: List<Set<ItemStack>>
        get() = this.value.ingredients.map { it.matchingStacks.toSet() }

    private val stackLookup = recipeManager.values().toList().groupBy { it.value.getResult(regManager).item }

    // Attempts to solve for stack cost.
    fun solveFor(stack: ItemStack, path: List<ItemStack>): Double? {
        val padding = (path.size + 1) * 2
        //println("Solving: $stack".padStart(padding))
        val recipes = stack.recipes

        // If no recipe exists, it is a terminator.
        if (recipes.isEmpty()) {
            terminators.add(stack.identifier)
            // Add dependency on said item
            for (pathItem in path) {
                deps.getOrPut(stack.identifier) { mutableSetOf() }.add(pathItem.identifier)
            }
            return null
        }

        val recipeCosts = mutableListOf<Double>()

        for (recipe in recipes) {
            val inputCounts = recipe.inItemSets.flatten().groupBy { it.item }.map { it.key to it.value.sumOf { stack -> stack.count } }.toMap()

            var ingredientRunningCost = 0.0

            val inputSets = recipe.inItemSets

            var numUnsolvedIngredients = 0

            for (optionSet in inputSets) {
                // Currently grabs the first solved and adds the cost; This assumes tag ingredients all have the same cost;
                // It might be useful to average them and not just grab the first one in the future!
                for (option in optionSet) {
                    if (option.item in path.map { it.item }) { // Cyclic dependency!
                        continue
                    }
                    if (path.size > 24) { // Avoid too much recursion
                        continue
                    }
                    if (option.item in costMap) { // Already calculated cost, simple O(1) lookup for worth
                        // Add the cost of the item times the amount needed (in that slot)
                        ingredientRunningCost += costMap[option.item]!! * option.count
                        numUnsolvedIngredients -= 1
                        break
                    } else if (option.item in matchCosts) {
                        ingredientRunningCost += matchCosts[option.item]!! * option.count
                        numUnsolvedIngredients -= 1
                        break
                    } else {
                        // No calculated cost. Can we iteratively find one?
                        val solvedCost = solveFor(option, path + stack)
                        if (solvedCost != null) {
                            ingredientRunningCost += solvedCost * option.count
                            numUnsolvedIngredients -= 1
                            break
                        }
                    }
                }
            }

            // If a recipe makes 3 of something, the actual cost is only 1/3 as much
            ingredientRunningCost /= recipe.value.getResult(regManager).count

            if (numUnsolvedIngredients > 0) {
                //println("Could not solve for ${stack.identifier}".padStart(padding))
            } else {
                //println("Resolved all ingredients for: ${stack.identifier}".padStart(padding))
                recipeCosts.add(ingredientRunningCost)
                //println("Cost was: $ingredientRunningCost".padStart(padding))
            }

        }

        val finalCost = recipeCosts.minOrNull()

        // Of all calculated recipe costs, find the minimum
        finalCost?.let {
            costMap[stack.item] = it
        }

        return finalCost
    }

    fun solveRequiredRecipes() {
        var unsolved = 0
        for (item in regManager.get(Registries.ITEM.key)) {
            // If there exists a recipe for it, solve it
            if (item in stackLookup.keys) {
                val didSolve = solveFor(ItemStack(item), emptyList())
                if (didSolve == null && data.required[item.identifier] == null) {
                    unsolved += 1
                }
            } else {
                unsolved += 1
            }
        }
        info.unsolved = unsolved
    }

    fun syncConfig() {

        println("SYNCING!")
        println("MATCH COSTS: ${matchCosts.map { it.key.identifier }}")

        info.redundant = matchCosts.keys.map { it.identifier }.sorted().toMutableList()

        // Remove redundant required items
        for ((id, amt) in data.required.toList()) {
            if (id in info.redundant) {
                data.required.remove(id)
            }
        }

        println("Terminators:")
        // Insert terminators into required prop
        for (line in terminators.sorted() - info.redundant.toSet()) {
            data.required[line] = costMap[regManager.get(Registries.ITEM.key).getOrEmpty(line).getOrNull()]
            println(line)
        }
        // Reset JSON file ordering (this is a bit hacky)
        data.required = data.required.toList().sortedBy { it.first.toString() }.toMap().toMutableMap()
        // Update dependency numbering
        info.deps = deps.map { it.key to it.value.size }.sortedBy { -it.second }.toMap().toMutableMap()

        for (item in regManager.get(Registries.ITEM.key).sortedBy { it.identifier }) {
            if (costOf(item) == null && item.identifier !in info.redundant && !data.matching.isIgnored(item)) {
                data.optional[item.identifier] = null
            } else {
                data.optional.remove(item.identifier)
            }
        }

    }

    fun showResults() {
        for (item in regManager.get(Registries.ITEM.key).sortedBy { it.identifier }) {
            println("Item: ${item.identifier.toString().padEnd(50)} - ${costMap[item]}")
        }
    }

}