package com.masterdns.vpn

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.data.local.Migration1To2
import com.masterdns.vpn.data.local.ProfileMigrations
import org.junit.Test

class ProfileMigrationsTest {

    @Test
    fun `registers exactly migration 1 to 2`() {
        val ranges = ProfileMigrations.ALL.map { it.startVersion to it.endVersion }
        assertThat(ranges).containsExactly(1 to 2)
    }

    @Test
    fun `top-level Migration1To2 delegates to the hub`() {
        assertThat(Migration1To2).isSameInstanceAs(ProfileMigrations.MIGRATION_1_2)
    }

    @Test
    fun `MIGRATION_1_2 has correct versions`() {
        assertThat(ProfileMigrations.MIGRATION_1_2.startVersion).isEqualTo(1)
        assertThat(ProfileMigrations.MIGRATION_1_2.endVersion).isEqualTo(2)
    }
}
