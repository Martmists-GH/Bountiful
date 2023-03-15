package ejektaflex.bountiful.data.structure

import com.google.gson.annotations.Expose
import ejektaflex.bountiful.util.IIdentifiable
import ejektaflex.bountiful.util.IMerge
import net.minecraft.nbt.CompoundTag
import net.minecraftforge.common.util.INBTSerializable

data class Decree(
        @Expose var spawnsInBoard: Boolean = false,
        @Expose var objectivePools: MutableList<String> = mutableListOf(),
        @Expose var rewardPools: MutableList<String> = mutableListOf()
) : INBTSerializable<CompoundTag>, IMerge<Decree>, IIdentifiable {

    override var id: String = "UNKNOWN_DECREE_ID"

    override fun serializeNBT(): CompoundTag {
        return CompoundTag().apply {
            putString("id", this@Decree.id)
        }
    }

    override val canLoad: Boolean
        get() = true

    override fun deserializeNBT(nbt: CompoundTag?) {
        id = nbt!!.getString("id")
    }

    override fun merge(other: Decree): Decree {
        objectivePools = mutableSetOf(*(objectivePools + other.objectivePools).toTypedArray()).toMutableList()
        rewardPools = mutableSetOf(*(rewardPools + other.rewardPools).toTypedArray()).toMutableList()
        return this
    }

    companion object {
        val INVALID = Decree(true).apply {
            id = "invalid_decree"
        }
    }

}