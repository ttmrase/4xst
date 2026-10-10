package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.model.Boost
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.ok
import kotlin.test.Test

/** [SplitInvariance] 自体が M1〜M3 の状態で通ることを確認する（M4 の状態生成器を足すときの雛形）。 */
class SplitInvarianceHelperTest {
    private val data = TestSupport.data

    @Test
    fun helperHoldsForBuildQueueBoostsAndBuilder() {
        var s = TestSupport.advanced(3, castle = 4).copy(resources = RICH)
        s = Actions.grantSecondBuilder(s, 1500).ok()
        s = Actions.addBoost(s, Boost("p", BoostKind.PRODUCTION, 3000, s.nowEpochSec + 700)).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.FARM), data).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.WAREHOUSE), data).ok()
        SplitInvariance.assertInvariant(s, 30_000, data, java.util.Random(1), randomCases = 60)
    }
}
