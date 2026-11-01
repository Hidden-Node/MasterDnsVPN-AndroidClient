package com.masterdns.vpn.util

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QrCodeUtilTest {

    @Test
    fun encode_and_decode_roundtrip_succeeds() {
        val url = "masterdns://eyJzY2hlbWEiOiJtYXN0ZXJkbnMucHJvZmlsZSIsInZlcnNpb24iOjF9"
        val bitmap = QrCodeUtil.encodeAsBitmap(url, sizePx = 256)

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(256)
        assertThat(bitmap.height).isEqualTo(256)

        val decodedResult = QrCodeUtil.decodeFromBitmap(bitmap)
        assertThat(decodedResult.isSuccess).isTrue()
        assertThat(decodedResult.getOrThrow()).isEqualTo(url)
    }

    @Test
    fun decode_blank_bitmap_returns_failure() {
        val blankBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = QrCodeUtil.decodeFromBitmap(blankBitmap)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun encode_blank_content_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            QrCodeUtil.encodeAsBitmap("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrCodeUtil.encodeAsBitmap("   ")
        }
    }

    @Test
    fun encode_invalid_size_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            QrCodeUtil.encodeAsBitmap("test", sizePx = 0)
        }
    }
}
