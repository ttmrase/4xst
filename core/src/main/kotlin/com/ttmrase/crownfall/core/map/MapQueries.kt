package com.ttmrase.crownfall.core.map

import kotlin.math.abs

/** マップの照会（UI の可視範囲・行軍の対象判定用）。 */
object MapQueries {
    /** マンハッタン距離。 */
    fun distance(
        ax: Int,
        ay: Int,
        bx: Int,
        by: Int,
    ): Int = abs(ax - bx) + abs(ay - by)

    /** 座標 (x, y) のタイルの内容。範囲外は null。 */
    fun tileAt(
        world: WorldMap,
        map: MapState,
        x: Int,
        y: Int,
    ): Tile? {
        if (!world.inBounds(x, y)) return null
        return Tile(
            x = x,
            y = y,
            terrain = world.terrainAt(x, y),
            structure = world.structureAt(x, y),
            node = map.nodes.firstOrNull { it.x == x && it.y == y },
            barbarian = map.barbarians.firstOrNull { it.x == x && it.y == y },
        )
    }

    /** 上下左右の隣接座標（範囲内のみ）。 */
    fun neighbors(
        world: WorldMap,
        x: Int,
        y: Int,
    ): List<TileRef> =
        listOf(TileRef(x, y - 1), TileRef(x + 1, y), TileRef(x, y + 1), TileRef(x - 1, y))
            .filter { world.inBounds(it.x, it.y) }

    /** x0..x1 × y0..y1（両端含む）の矩形に入るタイル。範囲外はクリップする。 */
    fun region(
        world: WorldMap,
        map: MapState,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): List<Tile> {
        val nodes = map.nodes.associateBy { it.y * world.size + it.x }
        val barbs = map.barbarians.associateBy { it.y * world.size + it.x }
        val out = ArrayList<Tile>()
        for (y in maxOf(0, y0)..minOf(world.size - 1, y1)) {
            for (x in maxOf(0, x0)..minOf(world.size - 1, x1)) {
                val i = y * world.size + x
                out.add(Tile(x, y, world.terrainAt(x, y), world.structureAt(x, y), nodes[i], barbs[i]))
            }
        }
        return out
    }
}
