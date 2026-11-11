package com.szdict.app.feature.ocr

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.szdict.recognition.OcrImage
import com.szdict.recognition.Recognizers
import com.szdict.recognition.TextRecognizer
import com.szdict.recognition.toOcrImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android side of the OCR ports (CAM-110): bundled recogniser, decoding, MediaStore, image clipboard. */
class AndroidOcrPlatform(context: Context) : OcrPlatform {
    private val context = context.applicationContext

    override fun createRecognizer(): TextRecognizer = Recognizers.text(context, alternatives = 8)

    /** CAM-026, CAM-041: upright (EXIF resolved), longest side at most 2560 px, software ARGB_8888. */
    override suspend fun decode(ref: ImageRef): WorkingImage = withContext(Dispatchers.Default) {
        fun open(): InputStream = when (ref) {
            is ImageRef.Encoded -> ByteArrayInputStream(ref.bytes)
            is ImageRef.Uri -> context.contentResolver.openInputStream(Uri.parse(ref.uri)) ?: error("Cannot open image")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not an image" }
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= WorkingImage.MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = open().use { BitmapFactory.decodeStream(it, null, options) } ?: error("Cannot decode image")
        val exif = runCatching { open().use { ExifInterface(it) } }.getOrNull()
        val matrix = Matrix()
        val decodedLongest = max(raw.width, raw.height)
        if (decodedLongest > WorkingImage.MAX_SIDE) {
            val scale = WorkingImage.MAX_SIDE.toFloat() / decodedLongest
            matrix.postScale(scale, scale)
        }
        if (exif != null) {
            if (exif.isFlipped) matrix.postScale(-1f, 1f)
            if (exif.rotationDegrees != 0) matrix.postRotate(exif.rotationDegrees.toFloat())
        }
        val upright = if (matrix.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        val software = if (upright.config == Bitmap.Config.ARGB_8888) upright else upright.copy(Bitmap.Config.ARGB_8888, false)
        BitmapWorkingImage(software)
    }

    /** CAM-075: `Pictures/SZ Dict/` through MediaStore with IS_PENDING; the legacy path on API 26–28. */
    override suspend fun savePhoto(jpeg: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val name = "SZDict_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
                put(MediaStore.Images.Media.DATE_TAKEN, now)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return@withContext false
            try {
                (resolver.openOutputStream(uri) ?: error("No output stream")).use { it.write(jpeg) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                true
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
                if (!directory.isDirectory && !directory.mkdirs()) return@withContext false
                val file = File(directory, name)
                file.outputStream().use { it.write(jpeg) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun clipboardImage(): String? = try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        if (clip == null || !clip.description.hasMimeType("image/*")) null
        else (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri?.toString() }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val ALBUM = "SZ Dict"
    }
}

private class BitmapWorkingImage(bitmap: Bitmap) : WorkingImage {
    private var pixels: Bitmap? = bitmap
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height
    override val bitmap: ImageBitmap? = bitmap.asImageBitmap()

    override fun toOcrImage(): OcrImage = (pixels ?: error("Image released")).toOcrImage()

    // Not recycled explicitly: a frame still drawing it must never see a recycled bitmap. Dropping
    // the reference lets the collector free the pixels once the composition lets go.
    override fun release() {
        pixels = null
    }
}
