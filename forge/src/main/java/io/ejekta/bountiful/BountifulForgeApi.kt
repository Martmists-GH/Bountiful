package io.ejekta.bountiful

import io.ejekta.bountiful.bridge.BountifulSharedApi
import net.minecraftforge.fml.ModList

class BountifulForgeApi : BountifulSharedApi {
    override fun isModLoaded(id: String): Boolean {
        return ModList.get().isLoaded(id)
    }
}