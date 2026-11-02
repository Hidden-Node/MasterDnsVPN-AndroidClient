package com.masterdns.vpn

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.masterdns.vpn.data.local.AppDatabase
import com.masterdns.vpn.data.local.ProfileDao
import com.masterdns.vpn.data.local.ProfileEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfileSelectionTransactionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var dao: ProfileDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.profileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun profile(name: String, createdAt: Long) = ProfileEntity(
        name = name,
        domains = "[]",
        createdAt = createdAt
    )

    @Test
    fun firstInsertIsAutoSelectedAndSecondInsertDoesNotStealSelection() = runTest {
        val first = dao.insertProfileAndSelectIfFirst(profile("a", 1))
        val second = dao.insertProfileAndSelectIfFirst(profile("b", 2))
        assertTrue(dao.getProfileById(first)!!.isSelected)
        assertFalse(dao.getProfileById(second)!!.isSelected)
        assertEquals(first, dao.getSelectedProfile()!!.id)
    }

    @Test
    fun deletingSelectedProfileReselectsNewestRemaining() = runTest {
        val a = dao.insertProfileAndSelectIfFirst(profile("a", 1))
        val b = dao.insertProfileAndSelectIfFirst(profile("b", 2))
        dao.setSelectedProfile(a)
        dao.deleteProfileAndReselect(dao.getProfileById(a)!!)
        assertEquals(b, dao.getSelectedProfile()!!.id)
        assertNull(dao.getProfileById(a))
    }

    @Test
    fun deletingNonSelectedProfileLeavesSelectionUntouched() = runTest {
        val a = dao.insertProfileAndSelectIfFirst(profile("a", 1))
        val b = dao.insertProfileAndSelectIfFirst(profile("b", 2))
        dao.setSelectedProfile(a)
        dao.deleteProfileAndReselect(dao.getProfileById(b)!!)
        assertEquals(a, dao.getSelectedProfile()!!.id)
    }

    @Test
    fun deletingOnlyProfileLeavesTableEmptyWithNoSelection() = runTest {
        val a = dao.insertProfileAndSelectIfFirst(profile("a", 1))
        dao.deleteProfileAndReselect(dao.getProfileById(a)!!)
        assertTrue(dao.getAllProfiles().first().isEmpty())
        assertNull(dao.getSelectedProfile())
    }

    @Test
    fun replaceProfilesForSubscription_preservesActiveProfileByName() = runTest {
        val p1 = ProfileEntity(name = "Node A", domains = "[\"a.com\"]", subscriptionId = 1L)
        val p2 = ProfileEntity(name = "Node B", domains = "[\"b.com\"]", subscriptionId = 1L)
        val id1 = dao.insertProfile(p1)
        val id2 = dao.insertProfile(p2)
        dao.setSelectedProfile(id2)
        assertEquals("Node B", dao.getSelectedProfile()!!.name)

        // Refresh subscription with new profiles
        val refreshedP1 = ProfileEntity(name = "Node A", domains = "[\"a.com\"]")
        val refreshedP2 = ProfileEntity(name = "Node B", domains = "[\"b.com\"]")
        val refreshedP3 = ProfileEntity(name = "Node C", domains = "[\"c.com\"]")
        dao.replaceProfilesForSubscription(1L, listOf(refreshedP1, refreshedP2, refreshedP3))

        // Selection should remain on Node B rather than jumping to Node C
        val currentSelected = dao.getSelectedProfile()
        assertEquals("Node B", currentSelected!!.name)
        assertEquals(1L, currentSelected.subscriptionId)
    }

    @Test
    fun replaceProfilesForSubscription_fallsBackToNewestIfSelectedNodeRemoved() = runTest {
        val p1 = ProfileEntity(name = "Node A", domains = "[\"a.com\"]", subscriptionId = 1L)
        val p2 = ProfileEntity(name = "Node B", domains = "[\"b.com\"]", subscriptionId = 1L)
        dao.insertProfile(p1)
        val id2 = dao.insertProfile(p2)
        dao.setSelectedProfile(id2)

        // Refresh with Node B removed
        val refreshedP1 = ProfileEntity(name = "Node A", domains = "[\"a.com\"]", createdAt = 100)
        val refreshedP3 = ProfileEntity(name = "Node C", domains = "[\"c.com\"]", createdAt = 200)
        dao.replaceProfilesForSubscription(1L, listOf(refreshedP1, refreshedP3))

        // Selection falls back to newest remaining
        val currentSelected = dao.getSelectedProfile()
        assertEquals("Node C", currentSelected!!.name)
    }
}
