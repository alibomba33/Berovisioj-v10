package com.vrbox.arhands

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Yerel koordinat: x sağ, y parmak yönü, birim = avuç uzunluğu. */
class Poly(val pts: FloatArray, val color: Int)
class Item(val name: String, val parts: List<Poly>, val tip: Float, val flash: Int)
class Category(val name: String, val color: Int, val items: List<Item>)

private fun c(v: Long): Int = v.toInt()

private val STEEL = c(0xFFC9D1D9)
private val DARK = c(0xFF2B2F36)
private val BROWN = c(0xFF7A4B25)
private val GOLD = c(0xFFE0B040)
private val CYAN = c(0xFF30E0FF)
private val GLOW = c(0x6630E0FF)
private val RED = c(0xFFE04040)
private val OLIVE = c(0xFF55633A)
private val ORANGE = c(0xFFFF8C1A)
private val ORANGE_GLOW = c(0x66FF8C1A)
private val YELLOW = c(0xFFFFE066)
private val PURPLE = c(0xFFB070FF)
private val WHITE = c(0xFFF4F8FF)
private val GRAY = c(0xFF8A929C)

private fun rect(cx: Float, cy: Float, w: Float, h: Float, col: Int) = Poly(
    floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy - h / 2, cx + w / 2, cy + h / 2, cx - w / 2, cy + h / 2), col
)

private fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, col: Int) =
    Poly(floatArrayOf(x1, y1, x2, y2, x3, y3), col)

private fun quad(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float, col: Int) =
    Poly(floatArrayOf(x1, y1, x2, y2, x3, y3, x4, y4), col)

private fun circle(cx: Float, cy: Float, r: Float, col: Int): Poly {
    val a = FloatArray(32)
    for (i in 0 until 16) {
        val t = (i * 2.0 * PI / 16.0)
        a[2 * i] = cx + (cos(t) * r).toFloat()
        a[2 * i + 1] = cy + (sin(t) * r).toFloat()
    }
    return Poly(a, col)
}

val CATEGORIES: List<Category> = listOf(
    Category("Silahlar", c(0xFFE05050), listOf(
        Item("Tabanca", listOf(rect(0f, 0.95f, 0.14f, 0.6f, DARK), rect(0f, 0.5f, 0.22f, 0.5f, STEEL), rect(0f, -0.05f, 0.17f, 0.6f, BROWN)), 1.3f, YELLOW),
        Item("Tüfek", listOf(rect(0f, 1.55f, 0.08f, 1.0f, DARK), rect(0f, 0.6f, 0.2f, 1.0f, OLIVE), rect(0f, -0.3f, 0.18f, 0.6f, BROWN), rect(0.14f, 0.3f, 0.1f, 0.35f, DARK)), 2.1f, YELLOW),
        Item("Lazer", listOf(rect(0f, 0.55f, 0.26f, 0.8f, WHITE), rect(0f, 1.15f, 0.1f, 0.5f, CYAN), circle(0f, 0.7f, 0.07f, CYAN), rect(0f, -0.05f, 0.16f, 0.55f, DARK)), 1.4f, CYAN),
        Item("Roketatar", listOf(rect(0f, 0.9f, 0.3f, 1.8f, OLIVE), tri(-0.15f, 1.8f, 0.15f, 1.8f, 0f, 2.2f, RED), rect(0f, -0.2f, 0.18f, 0.5f, DARK)), 2.2f, ORANGE)
    )),
    Category("Kılıçlar", c(0xFF50A0FF), listOf(
        Item("Kılıç", listOf(tri(-0.09f, 1.9f, 0.09f, 1.9f, 0f, 2.2f, STEEL), rect(0f, 1.15f, 0.18f, 1.5f, STEEL), rect(0f, 0.35f, 0.7f, 0.1f, GOLD), rect(0f, 0f, 0.1f, 0.55f, BROWN), circle(0f, -0.3f, 0.07f, GOLD)), 2.2f, WHITE),
        Item("Işın Kılıcı", listOf(rect(0f, 1.25f, 0.28f, 1.9f, GLOW), rect(0f, 1.25f, 0.1f, 1.9f, WHITE), rect(0f, 0.15f, 0.12f, 0.5f, DARK), rect(0f, 0.4f, 0.16f, 0.06f, STEEL)), 2.2f, CYAN),
        Item("Balta", listOf(rect(0f, 0.7f, 0.1f, 2.0f, BROWN), quad(0.05f, 1.65f, 0.6f, 1.85f, 0.6f, 0.85f, 0.05f, 1.05f, STEEL), quad(-0.05f, 1.55f, -0.3f, 1.65f, -0.3f, 1.15f, -0.05f, 1.25f, GRAY)), 1.8f, WHITE),
        Item("Hançer", listOf(tri(-0.08f, 1.1f, 0.08f, 1.1f, 0f, 1.5f, STEEL), rect(0f, 0.8f, 0.16f, 0.6f, STEEL), rect(0f, 0.45f, 0.4f, 0.08f, GOLD), rect(0f, 0.1f, 0.09f, 0.5f, BROWN)), 1.5f, WHITE)
    )),
    Category("Aletler", c(0xFFFFB020), listOf(
        Item("Çekiç", listOf(rect(0f, 0.5f, 0.1f, 1.7f, BROWN), rect(0f, 1.4f, 0.55f, 0.35f, GRAY)), 1.6f, WHITE),
        Item("Kalkan", listOf(circle(0f, 0.9f, 0.7f, STEEL), circle(0f, 0.9f, 0.5f, RED), circle(0f, 0.9f, 0.22f, GOLD)), 1.6f, WHITE),
        Item("Fener", listOf(rect(0f, 0.5f, 0.14f, 1.2f, BROWN), tri(-0.25f, 1.1f, 0.25f, 1.1f, 0f, 2.0f, ORANGE), tri(-0.14f, 1.1f, 0.14f, 1.1f, 0f, 1.65f, YELLOW)), 2.0f, ORANGE),
        Item("Anahtar", listOf(rect(0f, 0.6f, 0.12f, 1.6f, STEEL), circle(0f, 1.5f, 0.26f, STEEL), circle(0f, 0f, 0.14f, STEEL)), 1.8f, WHITE)
    )),
    Category("Fantezi", c(0xFFB070FF), listOf(
        Item("Değnek", listOf(rect(0f, 0.8f, 0.07f, 1.8f, DARK), tri(-0.15f, 1.85f, 0.15f, 1.85f, 0f, 2.2f, GOLD), tri(-0.15f, 2.07f, 0.15f, 2.07f, 0f, 1.75f, GOLD)), 2.2f, PURPLE),
        Item("Asa", listOf(rect(0f, 0.9f, 0.09f, 2.2f, BROWN), circle(0f, 2.0f, 0.2f, PURPLE), circle(0f, 2.0f, 0.1f, WHITE)), 2.2f, PURPLE),
        Item("Ateş Topu", listOf(circle(0f, 1.0f, 0.6f, ORANGE_GLOW), circle(0f, 1.0f, 0.4f, ORANGE), circle(0f, 1.0f, 0.2f, YELLOW)), 1.6f, ORANGE),
        Item("Kristal", listOf(quad(0f, 1.9f, 0.35f, 1.2f, 0f, 0.5f, -0.35f, 1.2f, CYAN), quad(0f, 1.9f, 0.12f, 1.2f, 0f, 0.5f, -0.12f, 1.2f, WHITE)), 1.9f, CYAN)
    ))
)
