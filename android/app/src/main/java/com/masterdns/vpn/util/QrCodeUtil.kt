package com.masterdns.vpn.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeUtil {

    fun encodeAsBitmap(
        content: String,
        sizePx: Int = 512,
        margin: Int = 1
    ): Bitmap {
        require(content.isNotBlank()) { "QR content must not be blank" }
        require(sizePx > 0) { "sizePx must be positive" }

        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin
        )

        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    fun decodeFromBitmap(bitmap: Bitmap): Result<String> = runCatching {
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
        val text = try {
            val width = safeBitmap.width
            val height = safeBitmap.height
            val pixels = IntArray(width * height)
            safeBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8",
                DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE
            )

            val reader = MultiFormatReader()
            runCatching {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            }.recoverCatching {
                reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints).text
            }.getOrThrow()?.trim().orEmpty()
        } finally {
            if (safeBitmap !== bitmap) {
                safeBitmap.recycle()
            }
        }

        require(text.isNotBlank()) { "Decoded QR code is empty" }
        text
    }
}
