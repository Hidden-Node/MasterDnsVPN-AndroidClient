package com.masterdns.vpn.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolverBalancingStrategiesTest {
    @Test fun validValueUsesIt() { assertEquals(5, normalizeResolverBalancingStrategy("5", 2)) }
    @Test fun zeroFallsBackTo2() { assertEquals(2, normalizeResolverBalancingStrategy("0", 3)) }
    @Test fun nonNumericFallsBack() { assertEquals(3, normalizeResolverBalancingStrategy("abc", 3)) }
    @Test fun outOfRangeFallsBack() { assertEquals(2, normalizeResolverBalancingStrategy("99", 2)) }
    @Test fun nullAndInvalidFallbackYields2() { assertEquals(2, normalizeResolverBalancingStrategy(null, 99)) }
}
