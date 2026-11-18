package com.vrbox.arhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** MediaPipe el takibi. Sonuçlar kamera görüntüsüne göre normalize (0..1) x,y çiftleridir (21 nokta = 42 float). */
class HandTracker(context: Context) {
    @Volatile var hands: List<FloatArray> = emptyList()
    @Volatile var lastMs: Long = 0L
    @Volatile var error: String? = null

    private val busy = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null

    init {
        try {
            val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .build()
            landmarker = HandLandmarker.createFromOptions(context, opts)
        } catch (t: Throwable) {
            error = "El modeli yüklenemedi"
        }
    }

    fun idle(): Boolean = landmarker != null && !busy.get()

    fun submit(bmp: Bitmap) {
        val lm = landmarker
        if (lm == null || !busy.compareAndSet(false, true)) {
            bmp.recycle()
            return
        }
        exec.execute {
            try {
                val res = lm.detect(BitmapImageBuilder(bmp).build())
                val out = ArrayList<FloatArray>()
                for (hand in res.landmarks()) {
                    if (hand.size < 21) continue
                    val a = FloatArray(42)
                    for (i in 0 until 21) {
                        a[2 * i] = hand[i].x()
                        a[2 * i + 1] = hand[i].y()
                    }
                    out.add(a)
                }
                hands = out
                if (out.isNotEmpty()) lastMs = SystemClock.uptimeMillis()
            } catch (t: Throwable) {
                error = "El takibi hatası"
            } finally {
                bmp.recycle()
                busy.set(false)
            }
        }
    }

    fun close() {
        exec.shutdown()
        try { landmarker?.close() } catch (t: Throwable) { }
    }
}

/** ARCore YUV_420_888 kamera karesini Bitmap'e çevirir. */
fun yuvToBitmap(image: Image): Bitmap? {
    val w = image.width
    val h = image.height
    val planes = image.planes
    val yBuf = planes[0].buffer
    val uBuf = planes[1].buffer
    val vBuf = planes[2].buffer
    val yRow = planes[0].rowStride
    val uvRow = planes[2].rowStride
    val uvPix = planes[2].pixelStride
    val nv21 = ByteArray(w * h * 3 / 2)
    for (r in 0 until h) {
        yBuf.position(r * yRow)
        yBuf.get(nv21, r * w, w)
    }
    var o = w * h
    for (r in 0 until h / 2) {
        for (c in 0 until w / 2) {
            val i = r * uvRow + c * uvPix
            nv21[o++] = vBuf.get(i)
            nv21[o++] = uBuf.get(i)
        }
    }
    val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
    val bos = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, w, h), 80, bos)
    val bytes = bos.toByteArray()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
