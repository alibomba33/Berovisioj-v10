package com.vrbox.arhands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal class Hand(val p: FloatArray) {
    val px: Float
    val py: Float
    val dx: Float
    val dy: Float
    val len: Float

    init {
        px = (x(0) + x(5) + x(9) + x(13) + x(17)) / 5f
        py = (y(0) + y(5) + y(9) + y(13) + y(17)) / 5f
        val vx = x(9) - x(0)
        val vy = y(9) - y(0)
        val l = max(hypot(vx, vy), 1e-4f)
        dx = vx / l
        dy = vy / l
        len = l
    }

    fun x(i: Int): Float = p[2 * i]
    fun y(i: Int): Float = p[2 * i + 1]
}

internal class Btn {
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
    fun set(a: Float, b: Float, c: Float, d: Float) { x = a; y = b; w = c; h = d }
    fun hit(a: Float, b: Float): Boolean = a >= x && a <= x + w && b >= y && b <= y + h
}

class ArRenderer(private val act: MainActivity) : GLSurfaceView.Renderer {
    val tracker = HandTracker(act.applicationContext)

    private var w = 1
    private var h = 1
    private var ew = 1
    private var aspect = 1f          // bir göz görüntüsünün genişlik/yükseklik oranı
    private var ox = 0f               // yatay paralaks kayması (göz birimi)
    private var geomDirty = true
    private var texSet = false

    private var bgTex = 0
    private var bgProg = 0
    private var bgPos = 0
    private var bgUv = 0
    private var bgSamp = 0
    private var solProg = 0
    private var solPos = 0
    private var solCol = 0
    private var texProg = 0
    private var tPos = 0
    private var tUv = 0
    private var tCol = 0
    private var tSamp = 0

    private val quad = fbuf(8)
    private val uv = fbuf(8)
    private val vb = fbuf(256)
    private val tvb = fbuf(8)
    private val tuv = fbuf(8)
    private val sc = FloatArray(64)
    private val texCache = HashMap<String, Pair<Int, Float>>()

    private val hands = ArrayList<Hand>()
    private val tabs = Array(4) { Btn() }
    private val cards = Array(4) { Btn() }
    private var panelX = 0f
    private var panelW = 0f

    private var cat = 0
    private var held: Item? = null
    private var pinching = false
    private var flashUntil = 0L
    private var hasCursor = false
    private var curX = 0f
    private var curY = 0f

    @Volatile private var tapPending = false
    @Volatile private var tapX = 0f
    @Volatile private var tapY = 0f

    fun onTap(x: Float, y: Float) {
        val half = max(ew, 1).toFloat()
        tapX = (x % half) / max(h, 1)
        tapY = y / max(h, 1)
        tapPending = true
    }

    private fun fbuf(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            return id
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        bgTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        bgProg = program(
            "attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vUv = aUv; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv; uniform samplerExternalOES uTex; void main(){ gl_FragColor = texture2D(uTex, vUv); }"
        )
        bgPos = GLES20.glGetAttribLocation(bgProg, "aPos")
        bgUv = GLES20.glGetAttribLocation(bgProg, "aUv")
        bgSamp = GLES20.glGetUniformLocation(bgProg, "uTex")

        solProg = program(
            "attribute vec2 aPos; void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }",
            "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor = uColor; }"
        )
        solPos = GLES20.glGetAttribLocation(solProg, "aPos")
        solCol = GLES20.glGetUniformLocation(solProg, "uColor")

        texProg = program(
            "attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vUv = aUv; }",
            "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; uniform vec4 uColor; void main(){ gl_FragColor = vec4(uColor.rgb, uColor.a * texture2D(uTex, vUv).a); }"
        )
        tPos = GLES20.glGetAttribLocation(texProg, "aPos")
        tUv = GLES20.glGetAttribLocation(texProg, "aUv")
        tCol = GLES20.glGetUniformLocation(texProg, "uColor")
        tSamp = GLES20.glGetUniformLocation(texProg, "uTex")

        quad.clear()
        quad.put(floatArrayOf(-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f))
        quad.position(0)
        uv.clear()
        uv.put(floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        uv.position(0)

        texSet = false
        geomDirty = true
        texCache.clear()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = max(width, 2)
        h = max(height, 1)
        ew = w / 2
        aspect = ew.toFloat() / h.toFloat()
        geomDirty = true
    }

    @Suppress("DEPRECATION")
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = act.session ?: return
        if (!texSet) {
            s.setCameraTextureName(bgTex)
            texSet = true
        }
        if (geomDirty) {
            val rot = act.windowManager.defaultDisplay.rotation
            s.setDisplayGeometry(rot, ew, h)
            geomDirty = false
        }
        val frame: Frame = try {
            s.update()
        } catch (e: Exception) {
            return
        }
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0)
            uv.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, uv
            )
            quad.position(0)
            uv.position(0)
        }

        feedTracker(frame)
        updateLogic(frame)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        for (eye in 0..1) {
            GLES20.glViewport(eye * ew, 0, ew, h)
            drawBackground()
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            ox = 0f
            drawHands()
            ox = if (eye == 0) 0.012f else -0.012f
            drawMenu()
            ox = 0f
            drawCursor()
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun feedTracker(frame: Frame) {
        if (!tracker.idle()) return
        var img: android.media.Image? = null
        try {
            val got: android.media.Image = frame.acquireCameraImage()
            img = got
            val bmp = yuvToBitmap(got)
            if (bmp != null) tracker.submit(bmp)
        } catch (e: Exception) {
            // kare henüz hazır değil
        } finally {
            img?.close()
        }
    }

    private fun updateLogic(frame: Frame) {
        layout()
        hands.clear()
        if (SystemClock.uptimeMillis() - tracker.lastMs < 400L) {
            for (r in tracker.hands) {
                if (r.size < 42) continue
                val o = FloatArray(42)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, r, Coordinates2d.VIEW_NORMALIZED, o)
                for (i in 0 until 21) o[2 * i] = o[2 * i] * aspect
                hands.add(Hand(o))
            }
        }
        val prim = hands.firstOrNull()
        var click = false
        var clickX = 0f
        var clickY = 0f
        if (prim != null) {
            val ratio = hypot(prim.x(4) - prim.x(8), prim.y(4) - prim.y(8)) / prim.len
            if (!pinching && ratio < 0.35f) {
                pinching = true
                click = true
            } else if (pinching && ratio > 0.55f) {
                pinching = false
            }
            curX = prim.x(8)
            curY = prim.y(8)
            hasCursor = true
            clickX = curX
            clickY = curY
        } else {
            pinching = false
            hasCursor = false
        }
        if (tapPending) {
            tapPending = false
            click = true
            clickX = tapX
            clickY = tapY
        }
        if (click) onClick(clickX, clickY)
    }

    private fun layout() {
        val pw = min(aspect * 0.94f, 1.2f)
        val x0 = (aspect - pw) / 2f
        val gap = 0.02f
        val cw = (pw - 3 * gap) / 4f
        for (i in 0 until 4) {
            tabs[i].set(x0 + i * (cw + gap), 0.60f, cw, 0.09f)
            cards[i].set(x0 + i * (cw + gap), 0.705f, cw, 0.255f)
        }
        panelX = x0 - 0.02f
        panelW = pw + 0.04f
    }

    private fun onClick(x: Float, y: Float) {
        for (i in tabs.indices) {
            if (tabs[i].hit(x, y)) { cat = i; return }
        }
        val items = CATEGORIES[cat].items
        for (i in cards.indices) {
            if (i < items.size && cards[i].hit(x, y)) {
                val chosen = items[i]
                held = if (held === chosen) null else chosen
                return
            }
        }
        if (held != null) flashUntil = SystemClock.uptimeMillis() + 160L
    }

    // ---------- çizim yardımcıları ----------

    private fun setColor(loc: Int, col: Int) {
        GLES20.glUniform4f(
            loc,
            ((col shr 16) and 255) / 255f,
            ((col shr 8) and 255) / 255f,
            (col and 255) / 255f,
            ((col ushr 24) and 255) / 255f
        )
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(bgProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex)
        GLES20.glUniform1i(bgSamp, 0)
        quad.position(0)
        uv.position(0)
        GLES20.glVertexAttribPointer(bgPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(bgUv, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(bgPos)
        GLES20.glEnableVertexAttribArray(bgUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(bgPos)
        GLES20.glDisableVertexAttribArray(bgUv)
    }

    private fun drawPts(p: FloatArray, n: Int, col: Int) {
        vb.clear()
        for (i in 0 until n) {
            vb.put((p[2 * i] + ox) / aspect * 2f - 1f)
            vb.put(1f - 2f * p[2 * i + 1])
        }
        vb.position(0)
        GLES20.glUseProgram(solProg)
        setColor(solCol, col)
        GLES20.glEnableVertexAttribArray(solPos)
        GLES20.glVertexAttribPointer(solPos, 2, GLES20.GL_FLOAT, false, 0, vb)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, n)
        GLES20.glDisableVertexAttribArray(solPos)
    }

    private fun fill(x: Float, y: Float, wd: Float, ht: Float, col: Int) {
        sc[0] = x; sc[1] = y
        sc[2] = x + wd; sc[3] = y
        sc[4] = x + wd; sc[5] = y + ht
        sc[6] = x; sc[7] = y + ht
        drawPts(sc, 4, col)
    }

    private fun disc(cx: Float, cy: Float, r: Float, col: Int) {
        for (i in 0 until 16) {
            val a = (i * 2.0 * PI / 16.0).toFloat()
            sc[2 * i] = cx + cos(a) * r
            sc[2 * i + 1] = cy + sin(a) * r
        }
        drawPts(sc, 16, col)
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, th: Float, col: Int) {
        val dx = x2 - x1
        val dy = y2 - y1
        val l = hypot(dx, dy)
        if (l < 1e-5f) return
        val nx = -dy / l * th / 2f
        val ny = dx / l * th / 2f
        sc[0] = x1 + nx; sc[1] = y1 + ny
        sc[2] = x2 + nx; sc[3] = y2 + ny
        sc[4] = x2 - nx; sc[5] = y2 - ny
        sc[6] = x1 - nx; sc[7] = y1 - ny
        drawPts(sc, 4, col)
    }

    private fun makeText(s: String): Pair<Int, Float> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.textSize = 44f
        val wpx = ceil(paint.measureText(s)).toInt() + 8
        val bmp = Bitmap.createBitmap(wpx, 60, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(s, 4f, 44f, paint)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return Pair(t[0], wpx / 60f)
    }

    private fun drawText(s: String, cx: Float, cy: Float, hgt: Float, maxW: Float, col: Int) {
        var e = texCache[s]
        if (e == null) {
            e = makeText(s)
            texCache[s] = e
        }
        val ht = min(hgt, maxW / e.second)
        val wd = ht * e.second
        val x0 = cx - wd / 2f
        val y0 = cy - ht / 2f
        tvb.clear()
        val xs = floatArrayOf(x0, x0 + wd, x0, x0 + wd)
        val ys = floatArrayOf(y0, y0, y0 + ht, y0 + ht)
        for (i in 0 until 4) {
            tvb.put((xs[i] + ox) / aspect * 2f - 1f)
            tvb.put(1f - 2f * ys[i])
        }
        tvb.position(0)
        tuv.clear()
        tuv.put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        tuv.position(0)
        GLES20.glUseProgram(texProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, e.first)
        GLES20.glUniform1i(tSamp, 0)
        setColor(tCol, col)
        GLES20.glVertexAttribPointer(tPos, 2, GLES20.GL_FLOAT, false, 0, tvb)
        GLES20.glVertexAttribPointer(tUv, 2, GLES20.GL_FLOAT, false, 0, tuv)
        GLES20.glEnableVertexAttribArray(tPos)
        GLES20.glEnableVertexAttribArray(tUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(tPos)
        GLES20.glDisableVertexAttribArray(tUv)
    }

    private fun drawItem(item: Item, px: Float, py: Float, dx: Float, dy: Float, scale: Float) {
        for (part in item.parts) {
            val n = part.pts.size / 2
            for (i in 0 until n) {
                val lx = part.pts[2 * i]
                val ly = part.pts[2 * i + 1]
                sc[2 * i] = px + (-dy * lx + dx * ly) * scale
                sc[2 * i + 1] = py + (dx * lx + dy * ly) * scale
            }
            drawPts(sc, n, part.color)
        }
    }

    private val bones = intArrayOf(
        0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12,
        9, 13, 13, 14, 14, 15, 15, 16, 13, 17, 17, 18, 18, 19, 19, 20, 0, 17
    )

    private fun drawHands() {
        for (hd in hands) {
            var i = 0
            while (i < bones.size) {
                val a = bones[i]
                val b = bones[i + 1]
                line(hd.x(a), hd.y(a), hd.x(b), hd.y(b), 0.006f, 0x8800E5FF.toInt())
                i += 2
            }
            for (j in 0 until 21) disc(hd.x(j), hd.y(j), 0.008f, 0xFFFFFFFF.toInt())
        }
        val prim = hands.firstOrNull() ?: return
        val item = held ?: return
        val scale = prim.len * 1.6f
        drawItem(item, prim.px, prim.py, prim.dx, prim.dy, scale)
        if (SystemClock.uptimeMillis() < flashUntil) {
            val tx = prim.px + prim.dx * item.tip * scale
            val ty = prim.py + prim.dy * item.tip * scale
            disc(tx, ty, 0.07f, (item.flash and 0x00FFFFFF) or 0x66000000)
            disc(tx, ty, 0.04f, item.flash)
            disc(tx, ty, 0.02f, 0xFFFFFFFF.toInt())
        }
    }

    private fun drawMenu() {
        val ci = CATEGORIES[cat]
        // cam gibi yarı saydam panel
        fill(panelX, 0.575f, panelW, 0.40f, 0xB0141420.toInt())
        fill(panelX, 0.575f, panelW, 0.004f, 0x66FFFFFF)

        // durum satırı (üstte, sabit)
        val status = when {
            tracker.error != null -> tracker.error!!
            held != null -> "Elinde: " + held!!.name + " - çimdikle: kullan"
            hands.isEmpty() -> "Elini kameraya göster"
            else -> "İşaret parmağı = imleç, çimdik = seç"
        }
        fill(aspect / 2f - 0.42f, 0.03f, 0.84f, 0.06f, 0x88101018.toInt())
        drawText(status, aspect / 2f, 0.06f, 0.04f, 0.8f, 0xFFFFFFFF.toInt())

        for (i in tabs.indices) {
            val b = tabs[i]
            val cc = CATEGORIES[i]
            val sel = i == cat
            val hover = hasCursor && b.hit(curX, curY)
            val base = (cc.color and 0x00FFFFFF)
            val alpha = if (sel) 0xE0 else if (hover) 0xA0 else 0x50
            fill(b.x, b.y, b.w, b.h, (alpha shl 24) or base)
            drawText(cc.name, b.x + b.w / 2f, b.y + b.h / 2f, 0.045f, b.w * 0.92f, 0xFFFFFFFF.toInt())
        }
        for (i in cards.indices) {
            if (i >= ci.items.size) continue
            val b = cards[i]
            val item = ci.items[i]
            val isHeld = held === item
            val hover = hasCursor && b.hit(curX, curY)
            if (isHeld) fill(b.x - 0.008f, b.y - 0.008f, b.w + 0.016f, b.h + 0.016f, 0xFF4CD964.toInt())
            fill(b.x, b.y, b.w, b.h, if (hover) 0xE03A3A52.toInt() else 0xC0222233.toInt())
            val l = 0.063f
            drawItem(item, b.x + b.w / 2f, b.y + 0.01f + 0.17f - 0.5f * l, 0f, -1f, l)
            drawText(item.name, b.x + b.w / 2f, b.y + b.h - 0.04f, 0.038f, b.w * 0.92f, 0xFFFFFFFF.toInt())
        }
    }

    private fun drawCursor() {
        if (!hasCursor) return
        disc(curX, curY, 0.024f, 0xFFFFFFFF.toInt())
        disc(curX, curY, 0.015f, if (pinching) 0xFFFFD60A.toInt() else 0xFF0A84FF.toInt())
    }
}
