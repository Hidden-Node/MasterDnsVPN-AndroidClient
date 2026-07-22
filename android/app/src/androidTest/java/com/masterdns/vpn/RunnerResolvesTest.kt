package com.masterdns.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunnerResolvesTest {
    @Test
    fun runnerClassResolves() {
        // The mere fact this test compiles and the runner loads proves
        // the androidTestImplementation dependencies are on the classpath.
        assertEquals(4, 2 + 2)
    }
}
