package com.arengineering.inspection.cv

/** Boundary rings in normalized raw-image coordinates, including holes. */
fun BinaryMaskRle.contours(maxSide: Int = 160): List<List<Point2>> {
    require(maxSide >= 2)
    val source = decode()
    val step = (maxOf(width, height) + maxSide - 1) / maxSide
    val w = (width + step - 1) / step
    val h = (height + step - 1) / step
    val cells = BooleanArray(w * h)
    source.forEachIndexed { index, filled ->
        if (filled) cells[(index / width / step) * w + index % width / step] = true
    }
    val stride = w + 1
    val edges = mutableMapOf<Int, MutableList<Int>>()
    fun edge(from: Int, to: Int) { edges.getOrPut(from) { mutableListOf() }.add(to) }
    for (y in 0 until h) for (x in 0 until w) {
        val cell = y * w + x
        if (!cells[cell]) continue
        val vertex = y * stride + x
        if (y == 0 || !cells[cell - w]) edge(vertex, vertex + 1)
        if (x == w - 1 || !cells[cell + 1]) edge(vertex + 1, vertex + stride + 1)
        if (y == h - 1 || !cells[cell + w]) edge(vertex + stride + 1, vertex + stride)
        if (x == 0 || !cells[cell - 1]) edge(vertex + stride, vertex)
    }
    fun direction(from: Int, to: Int) = when (to - from) {
        1 -> 0
        stride -> 1
        -1 -> 2
        else -> 3
    }
    return buildList {
        while (edges.isNotEmpty()) {
            val first = edges.keys.first()
            var current = first
            var previous = -1
            val ring = mutableListOf<Int>()
            do {
                ring.add(current)
                val nextEdges = edges.getValue(current)
                // At a diagonal contact keep the filled cells on the right of the path.
                val next = if (previous == -1) nextEdges.first() else {
                    val incoming = direction(previous, current)
                    nextEdges.minBy { (direction(current, it) - incoming + 3) % 4 }
                }
                nextEdges.remove(next)
                if (nextEdges.isEmpty()) edges.remove(current)
                previous = current
                current = next
            } while (current != first)

            if (ring.size >= 4) add(ring.indices.mapNotNull { i ->
                val before = ring[(i + ring.size - 1) % ring.size]
                val vertex = ring[i]
                val after = ring[(i + 1) % ring.size]
                if (direction(before, vertex) == direction(vertex, after)) null else Point2(
                    minOf(vertex % stride * step, width).toDouble() / width,
                    minOf(vertex / stride * step, height).toDouble() / height,
                )
            })
        }
    }
}
