package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.model.ResourceType

/** 寄付の種類（資源 1 種、またはジェム）。 */
enum class DonationKind(val resource: ResourceType?) {
    FOOD(ResourceType.FOOD),
    WOOD(ResourceType.WOOD),
    STONE(ResourceType.STONE),
    GOLD(ResourceType.GOLD),
    GEMS(null),
}
