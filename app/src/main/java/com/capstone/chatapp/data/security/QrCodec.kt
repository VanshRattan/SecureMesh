package com.capstone.chatapp.data.security

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** What a pairing QR code encodes: enough to add + verify a contact without any network round trip. */
data class PairingPayload(val uid: String, val name: String, val pubKeyBase64: String) {
    fun encode(): String = listOf(uid, name, pubKeyBase64).joinToString(SEPARATOR) { it.replace(SEPARATOR, " ") }

    companion object {
        private const val SEPARATOR = "|"
        const val PREFIX = "safesphere-pair:"

        fun parse(raw: String): PairingPayload? {
            if (!raw.startsWith(PREFIX)) return null
            val parts = raw.removePrefix(PREFIX).split(SEPARATOR)
            if (parts.size != 3) return null
            val (uid, name, pubKey) = parts
            if (uid.isBlank() || pubKey.isBlank()) return null
            return PairingPayload(uid, name, pubKey)
        }
    }
}

/** Encodes/decodes the pairing QR itself. Scanning is done with ZXing's [com.google.zxing.MultiFormatReader]
 *  directly against CameraX luminance frames in [com.capstone.chatapp.ui.pairing.PairingScreen]. */
object QrCodec {
    fun encode(payload: PairingPayload): String = PairingPayload.PREFIX + payload.encode()

    fun toBitmap(text: String, sizePx: Int = 800): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
