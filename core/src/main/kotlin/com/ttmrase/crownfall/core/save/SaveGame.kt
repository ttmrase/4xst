package com.ttmrase.crownfall.core.save

import com.ttmrase.crownfall.core.model.GameState
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** セーブデータ。[version] を上げる変更は Director の承認が必要。 */
@Serializable
data class SaveGame(
    val version: Int = CURRENT_VERSION,
    val savedAtEpochSec: Long,
    val seed: Long,
    val state: GameState,
) {
    companion object {
        /** 現行のセーブ形式バージョン。 */
        const val CURRENT_VERSION = 1
    }
}

/** デコード結果。 */
sealed interface DecodeResult {
    data class Success(val save: SaveGame) : DecodeResult

    data class Corrupt(val message: String) : DecodeResult

    data class TooNew(val version: Int) : DecodeResult
}

/** 古い版のセーブを現行版へ前方変換するチェーン。 */
object Migrations {
    /** [json] を [fromVersion] から現行版まで変換する。v1 は no-op。 */
    fun migrate(
        json: JsonObject,
        fromVersion: Int,
    ): JsonObject {
        var current = json
        var v = fromVersion
        while (v < SaveGame.CURRENT_VERSION) {
            current = step(current, v)
            v++
        }
        return current
    }

    // 版 v → v+1 の変換。現状は v1 が最新なので到達しない（将来ここに when を足す）。
    private fun step(
        json: JsonObject,
        from: Int,
    ): JsonObject = JsonObject(json + ("version" to JsonPrimitive(from + 1)))
}

/** セーブの JSON 変換。adb pull でそのまま読めるよう整形はしないが素の JSON を保つ。 */
object SaveCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** [save] を JSON 文字列にする。 */
    fun encode(save: SaveGame): String = json.encodeToString(SaveGame.serializer(), save)

    /** JSON 文字列から読み込む。壊れていれば Corrupt、新しすぎる版は TooNew。 */
    fun decode(text: String): DecodeResult =
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            val version = obj["version"]?.jsonPrimitive?.int ?: return DecodeResult.Corrupt("missing version")
            when {
                version > SaveGame.CURRENT_VERSION -> DecodeResult.TooNew(version)
                version < 1 -> DecodeResult.Corrupt("invalid version: $version")
                else ->
                    DecodeResult.Success(
                        json.decodeFromJsonElement(SaveGame.serializer(), Migrations.migrate(obj, version)),
                    )
            }
        } catch (e: SerializationException) {
            DecodeResult.Corrupt(e.message ?: "serialization error")
        } catch (e: IllegalArgumentException) {
            DecodeResult.Corrupt(e.message ?: "illegal argument")
        }
}
