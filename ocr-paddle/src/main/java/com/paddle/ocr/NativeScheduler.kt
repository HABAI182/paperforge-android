package com.paddle.ocr

/**
 * PaperForge adaptation: serialize native stages while allowing interactive work between batches.
 */
interface NativeScheduler {
    suspend fun <T> run(action: () -> T): T

    companion object {
        val Direct =
            object : NativeScheduler {
                override suspend fun <T> run(action: () -> T): T = action()
            }
    }
}
