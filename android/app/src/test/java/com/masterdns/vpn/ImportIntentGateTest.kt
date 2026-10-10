package com.masterdns.vpn

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.util.ImportIntentGate
import org.junit.Test

class ImportIntentGateTest {
    @Test
    fun firstCreation_withNewUri_handles() {
        assertThat(ImportIntentGate.shouldHandle(true, "content://x/a.toml", null)).isTrue()
    }

    @Test
    fun recreation_withNewUri_neverHandles() {
        assertThat(ImportIntentGate.shouldHandle(false, "content://x/a.toml", null)).isFalse()
    }

    @Test
    fun recreation_withSameUri_neverHandles() {
        assertThat(ImportIntentGate.shouldHandle(false, "content://x/a.toml", "content://x/a.toml")).isFalse()
    }

    @Test
    fun sameUriAsLastHandled_neverHandles() {
        assertThat(ImportIntentGate.shouldHandle(true, "content://x/a.toml", "content://x/a.toml")).isFalse()
    }

    @Test
    fun nullUri_neverHandles() {
        assertThat(ImportIntentGate.shouldHandle(true, null, null)).isFalse()
    }

    @Test
    fun blankUri_neverHandles() {
        assertThat(ImportIntentGate.shouldHandle(true, "   ", null)).isFalse()
    }

    @Test
    fun isTomlLike_tomlMime_handles() {
        assertThat(ImportIntentGate.isTomlLike("application/toml", "content://x/profile")).isTrue()
    }

    @Test
    fun isTomlLike_nonTomlMimeWithTomlPath_handles() {
        assertThat(ImportIntentGate.isTomlLike("application/octet-stream", "content://x/profile.toml")).isTrue()
    }

    @Test
    fun isTomlLike_nonTomlMimeWithNonTomlPath_rejects() {
        assertThat(ImportIntentGate.isTomlLike("application/octet-stream", "content://x/profile.json")).isFalse()
    }

    @Test
    fun isTomlLike_nullMimeWithTomlPath_handles() {
        assertThat(ImportIntentGate.isTomlLike(null, "content://x/profile.toml")).isTrue()
    }

    @Test
    fun isTomlLike_nullUri_rejects() {
        assertThat(ImportIntentGate.isTomlLike("application/toml", null)).isFalse()
    }
}
