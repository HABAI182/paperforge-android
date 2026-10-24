package cn.paperforge.app.model

/** Physical page groups stay stable when direction changes; RTL reverses presentation only. */
object PageSpreads {
    fun groups(count: Int, facing: Boolean, coverAlone: Boolean): List<List<Int>> {
        if (count <= 0) return emptyList()
        if (!facing) return (0 until count).map { listOf(it) }
        val first = if (coverAlone) 1 else 0
        return (if (coverAlone) listOf(listOf(0)) else emptyList()) +
            (first until count step 2).map { start ->
                (start until minOf(start + 2, count)).toList()
            }
    }

    fun groupOf(groups: List<List<Int>>, page: Int) =
        groups.indexOfFirst { page in it }.coerceAtLeast(0)
}
