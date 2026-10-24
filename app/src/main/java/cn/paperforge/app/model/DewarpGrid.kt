package cn.paperforge.app.model

import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.floor

/** Normalized output-to-input grid, with the same half-pixel resize convention as OpenCV. */
data class DewarpGrid(
    val width: Int,
    val height: Int,
    val strength: Float,
    val side: Int,
    val values: FloatArray,
) {
    init {
        require(
            width > 1 &&
                height > 1 &&
                side in 2..512 &&
                values.size == side * side * 2 &&
                values.all { it.isFinite() }
        )
    }

    fun inputPoint(output: PointN): PointN {
        val x = output.x * (width - 1)
        val y = output.y * (height - 1)
        val gx = ((x + .5f) * side / width - .5f).coerceIn(0f, side - 1f)
        val gy = ((y + .5f) * side / height - .5f).coerceIn(0f, side - 1f)
        val x0 = floor(gx).toInt()
        val y0 = floor(gy).toInt()
        val x1 = (x0 + 1).coerceAtMost(side - 1)
        val y1 = (y0 + 1).coerceAtMost(side - 1)
        fun axis(a: Int): Float {
            fun at(ix: Int, iy: Int) = values[2 * (iy * side + ix) + a]
            val top = at(x0, y0) * (1 - (gx - x0)) + at(x1, y0) * (gx - x0)
            val bottom = at(x0, y1) * (1 - (gx - x0)) + at(x1, y1) * (gx - x0)
            return top * (1 - (gy - y0)) + bottom * (gy - y0)
        }
        return PointN(
            output.x + (axis(0) - output.x) * strength,
            output.y + (axis(1) - output.y) * strength,
        )
    }

    fun write(file: File) {
        check(!file.exists()) { "曲面网格是不可变资源，请另存新文件" }
        val temp = File(file.path + ".part")
        try {
            DataOutputStream(GZIPOutputStream(temp.outputStream().buffered())).use { out ->
                out.writeUTF("PaperForge-grid")
                out.writeInt(1)
                out.writeInt(width)
                out.writeInt(height)
                out.writeFloat(strength)
                out.writeInt(side)
                values.forEach(out::writeFloat)
            }
            check(temp.renameTo(file)) { "无法保存曲面网格" }
        } finally {
            temp.delete()
        }
    }

    companion object {
        fun read(file: File): DewarpGrid =
            DataInputStream(GZIPInputStream(file.inputStream().buffered())).use { input ->
                require(input.readUTF() == "PaperForge-grid" && input.readInt() == 1) { "不支持的曲面网格" }
                val width = input.readInt()
                val height = input.readInt()
                val strength = input.readFloat()
                val side = input.readInt()
                require(side in 2..512 && strength.isFinite() && strength in 0f..1f)
                DewarpGrid(
                    width,
                    height,
                    strength,
                    side,
                    FloatArray(side * side * 2) { input.readFloat() },
                )
            }
    }
}
