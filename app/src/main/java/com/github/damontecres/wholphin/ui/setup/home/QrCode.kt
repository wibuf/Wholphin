package com.github.damontecres.wholphin.ui.setup.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import timber.log.Timber

/** The QR code for [content] as a grid of modules, or null if it can't be encoded */
internal fun qrModules(content: String): BitMatrix? =
    try {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.MARGIN to 0,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            ),
        )
    } catch (ex: Exception) {
        Timber.w(ex, "Could not encode a QR code")
        null
    }

/** Draws [content] as a black-on-transparent QR code filling the given size */
@Composable
internal fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
) {
    val modules = remember(content) { qrModules(content) }
    Canvas(modifier) {
        val matrix = modules ?: return@Canvas
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        // A hair over one cell, so neighbouring modules don't show seams
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}
