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
}
