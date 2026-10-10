package com.ttmrase.crownfall.core.data

/** クラスパス（jar のリソース）から data ディレクトリの JSON を読む。java.io.File は使わない（Android でも動く）。 */
object ClasspathDataSource {
    private val names =
        listOf(
            GameDataLoader.BUILDINGS,
            GameDataLoader.RULES,
            GameDataLoader.ITEMS,
            GameDataLoader.SPEEDUP,
            GameDataLoader.TROOPS,
            GameDataLoader.RESEARCH,
            GameDataLoader.HEROES,
            GameDataLoader.MAP_RULES,
            GameDataLoader.PERSONAS,
            GameDataLoader.ALLIANCE_RULES,
            GameDataLoader.TEMPLATES,
            GameDataLoader.OFFERS,
            GameDataLoader.SHOP_RULES,
            GameDataLoader.VIP,
        )

    /** 全データファイルを「ファイル名 → 本文」で読む。 */
    fun readAll(loader: ClassLoader = ClasspathDataSource::class.java.classLoader): Map<String, String> =
        names.associateWith { name ->
            val stream =
                loader.getResourceAsStream("data/$name")
                    ?: throw GameDataException("resource not found: data/$name")
            stream.use { String(it.readBytes(), Charsets.UTF_8) }
        }

    /** 同梱データから [GameData] を読み込む。 */
    fun load(loader: ClassLoader = ClasspathDataSource::class.java.classLoader): GameData =
        GameDataLoader.parse(readAll(loader))
}
