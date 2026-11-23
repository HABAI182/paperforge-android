package cn.paperforge.app.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The size-bounded LRU behind [ThumbnailCache]. Values are byte arrays sized by their length. */
class BoundedLruTest {
    private fun lru(maximum: Long) = BoundedLru<String, ByteArray>(maximum) { it.size.toLong() }

    @Test
    fun keepsWhatFits() {
        val cache = lru(10)
        val a = ByteArray(4)
        assertSame(a, cache.putIfAbsent("a", a))
        assertSame(a, cache.get("a"))
        assertEquals(4L, cache.retained)
    }

    @Test
    fun dropsTheLeastRecentlyUsedFirst() {
        val cache = lru(10)
        cache.putIfAbsent("a", ByteArray(4))
        cache.putIfAbsent("b", ByteArray(4))
        cache.get("a") // a is now the most recent
        cache.putIfAbsent("c", ByteArray(4))
        assertNull(cache.get("b"))
        assertEquals(2, cache.size())
        assertEquals(8L, cache.retained)
    }

    @Test
    fun theFirstValueForAKeyWins() {
        val cache = lru(10)
        val first = ByteArray(2)
        cache.putIfAbsent("k", first)
        assertSame(first, cache.putIfAbsent("k", ByteArray(2)))
        assertEquals(2L, cache.retained)
    }

    @Test
    fun aValueLargerThanTheWholeCacheIsReturnedButNotKept() {
        val cache = lru(10)
        cache.putIfAbsent("small", ByteArray(3))
        val big = ByteArray(11)
        assertSame(big, cache.putIfAbsent("big", big))
        assertNull(cache.get("big"))
        assertEquals(3L, cache.retained)
    }

    @Test
    fun clearEmptiesIt() {
        val cache = lru(10)
        cache.putIfAbsent("a", ByteArray(5))
        cache.clear()
        assertEquals(0, cache.size())
        assertEquals(0L, cache.retained)
    }
}
