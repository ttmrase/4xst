package com.ttmrase.crownfall.core.save

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.model.Boost
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SaveTest {
    private val data = TestSupport.data

    private fun roundTrip(save: SaveGame): SaveGame {
        val result = SaveCodec.decode(SaveCodec.encode(save))
        assertIs<DecodeResult.Success>(result)
        return result.save
    }

    @Test
    fun newGameRoundTrips() {
        val save = SaveGame(savedAtEpochSec = T0, seed = 42, state = TestSupport.newGame())
        assertEquals(save, roundTrip(save))
        assertEquals(1, save.version)
    }

    @Test
    fun midGameStateRoundTrips() {
        var s = TestSupport.advanced().copy(resources = RICH)
        s = Actions.startUpgrade(s, 1, data).ok()
        s = Actions.addBoost(s, Boost("p", BoostKind.PRODUCTION, 500, T0 + 999)).ok()
        s = Simulation.tick(s, 1, data).state
        val save = SaveGame(savedAtEpochSec = s.nowEpochSec, seed = 7, state = s)
        assertEquals(save, roundTrip(save))
    }

    @Test
    fun unknownFieldsAreIgnoredAndDefaultsFilled() {
        val json = SaveCodec.encode(SaveGame(savedAtEpochSec = 1, seed = 2, state = TestSupport.newGame()))
        val withExtra = json.replaceFirst("{", "{\"futureField\":123,")
        assertIs<DecodeResult.Success>(SaveCodec.decode(withExtra))
        // 省略可能フィールドを落としても読める（R8: デフォルト値付き追加は版を上げない）
        val trimmed = json.replace("\"secondBuilderUntilEpochSec\":0,", "")
        assertIs<DecodeResult.Success>(SaveCodec.decode(trimmed))
    }

    @Test
    fun corruptAndTooNewAreReported() {
        assertIs<DecodeResult.Corrupt>(SaveCodec.decode("{ not json"))
        assertIs<DecodeResult.Corrupt>(SaveCodec.decode("[]"))
        assertIs<DecodeResult.Corrupt>(SaveCodec.decode("{}"))
        assertIs<DecodeResult.Corrupt>(SaveCodec.decode("""{"version":0,"savedAtEpochSec":1,"seed":1}"""))
        assertIs<DecodeResult.Corrupt>(SaveCodec.decode("""{"version":1,"savedAtEpochSec":1,"seed":1}"""))
        val tooNew = SaveCodec.decode("""{"version":2,"savedAtEpochSec":1,"seed":1,"state":{}}""")
        assertEquals(DecodeResult.TooNew(2), tooNew)
    }

    @Test
    fun migrateV1IsIdentity() {
        val obj: JsonObject =
            buildJsonObject {
                put("version", 1)
                put("seed", 9)
                put("nested", JsonPrimitive("x"))
            }
        assertEquals(obj, Migrations.migrate(obj, 1))
    }
}
