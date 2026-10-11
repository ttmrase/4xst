package com.ttmrase.crownfall.core.data

/** titles.json と personas.json の `title` の整合検証（違反は [GameDataException]）。 */
internal object TitleValidator {
    fun validate(data: GameData) {
        val ids = data.titles.titles.map { it.id }
        if (ids.isEmpty() || ids.toSet().size != ids.size) throw GameDataException("titles.json: ids must be unique")
        data.titles.titles.forEach { t -> t.cond?.let { QuestValidator.validateCond("titles.json ${t.id}", it) } }
        if (data.titles.titles.first().cond == null) {
            throw GameDataException(
                "titles.json: first title must be earnable",
            )
        }
        for (p in data.personas.personas) {
            if (p.title.isNotEmpty() && p.title !in ids) throw GameDataException("personas.json: ${p.id} unknown title")
        }
    }
}
