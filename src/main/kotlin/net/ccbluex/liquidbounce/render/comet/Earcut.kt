/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.render.comet

/**
 * Polygon triangulation by ear clipping, with hole support — a Kotlin port of Mapbox's `earcut`
 * (ISC licensed), minus the z-order hashing acceleration (glyph contours are tiny, so the plain
 * O(n²) ear test is plenty).
 *
 * Used to tessellate glyph outlines into triangles for GPU vector text rendering on CometRenderer.
 *
 * @see <a href="https://github.com/mapbox/earcut">mapbox/earcut</a>
 */
object Earcut {

    private class Node(val i: Int, val x: Double, val y: Double) {
        var prev: Node? = null
        var next: Node? = null
        var steiner = false
    }

    /**
     * Triangulates a polygon given as flat `[x0,y0, x1,y1, ...]` coordinates with [holeIndices]
     * pointing at the first vertex (in vertex units, not array units) of each hole ring.
     * Returns triangle vertex indices (into the vertex list), 3 per triangle.
     */
    fun earcut(data: DoubleArray, holeIndices: IntArray, dim: Int = 2): IntArray {
        val hasHoles = holeIndices.isNotEmpty()
        val outerLen = if (hasHoles) holeIndices[0] * dim else data.size

        var outerNode = linkedList(data, 0, outerLen, dim, true)
        val triangles = ArrayList<Int>()
        if (outerNode == null || outerNode.next === outerNode.prev) return IntArray(0)

        if (hasHoles) outerNode = eliminateHoles(data, holeIndices, outerNode!!, dim)

        earcutLinked(outerNode, triangles, dim, 0)
        return triangles.toIntArray()
    }

    private fun linkedList(data: DoubleArray, start: Int, end: Int, dim: Int, clockwise: Boolean): Node? {
        var last: Node? = null
        if (clockwise == (signedArea(data, start, end, dim) > 0)) {
            var i = start
            while (i < end) {
                last = insertNode(i, data[i], data[i + 1], last)
                i += dim
            }
        } else {
            var i = end - dim
            while (i >= start) {
                last = insertNode(i, data[i], data[i + 1], last)
                i -= dim
            }
        }
        if (last != null && equals(last, last.next!!)) {
            removeNode(last)
            last = last.next
        }
        return last
    }

    private fun filterPoints(start: Node?, endIn: Node? = null): Node? {
        if (start == null) return null
        var end: Node? = endIn ?: start
        var p: Node? = start
        var again: Boolean
        do {
            again = false
            if (!p!!.steiner && (equals(p, p.next!!) || area(p.prev!!, p, p.next!!) == 0.0)) {
                removeNode(p)
                p = p.prev
                end = p
                if (p === p!!.next) break
                again = true
            } else {
                p = p.next
            }
        } while (again || p !== end)
        return end
    }

    private fun earcutLinked(earIn: Node?, triangles: ArrayList<Int>, dim: Int, pass: Int) {
        if (earIn == null) return
        var ear = earIn
        var stop = ear
        var prev: Node
        var next: Node

        while (ear!!.prev !== ear.next) {
            prev = ear.prev!!
            next = ear.next!!

            if (isEar(ear)) {
                triangles.add(prev.i / dim)
                triangles.add(ear.i / dim)
                triangles.add(next.i / dim)

                removeNode(ear)
                ear = next.next
                stop = next.next
                continue
            }

            ear = next
            if (ear === stop) {
                when (pass) {
                    0 -> earcutLinked(filterPoints(ear), triangles, dim, 1)
                    1 -> {
                        ear = cureLocalIntersections(filterPoints(ear)!!, triangles, dim)
                        earcutLinked(ear, triangles, dim, 2)
                    }
                    2 -> splitEarcut(ear, triangles, dim)
                }
                break
            }
        }
    }

    private fun isEar(ear: Node): Boolean {
        val a = ear.prev!!
        val b = ear
        val c = ear.next!!

        if (area(a, b, c) >= 0) return false // reflex, can't be an ear

        val ax = a.x; val bx = b.x; val cx = c.x
        val ay = a.y; val by = b.y; val cy = c.y

        val x0 = minOf(ax, bx, cx)
        val y0 = minOf(ay, by, cy)
        val x1 = maxOf(ax, bx, cx)
        val y1 = maxOf(ay, by, cy)

        var p = c.next
        while (p !== a) {
            if (p!!.x in x0..x1 && p.y in y0..y1 &&
                pointInTriangle(ax, ay, bx, by, cx, cy, p.x, p.y) &&
                area(p.prev!!, p, p.next!!) >= 0
            ) {
                return false
            }
            p = p.next
        }
        return true
    }

    private fun cureLocalIntersections(startIn: Node, triangles: ArrayList<Int>, dim: Int): Node {
        var start = startIn
        var p = start
        do {
            val a = p.prev!!
            val b = p.next!!.next!!

            if (!equals(a, b) && intersects(a, p, p.next!!, b) && locallyInside(a, b) && locallyInside(b, a)) {
                triangles.add(a.i / dim)
                triangles.add(p.i / dim)
                triangles.add(b.i / dim)

                removeNode(p)
                removeNode(p.next!!)

                p = b
                start = b
            }
            p = p.next!!
        } while (p !== start)

        return filterPoints(p)!!
    }

    private fun splitEarcut(start: Node, triangles: ArrayList<Int>, dim: Int) {
        var a = start
        do {
            var b = a.next!!.next
            while (b !== a.prev) {
                if (a.i != b!!.i && isValidDiagonal(a, b)) {
                    var c = splitPolygon(a, b)

                    a = filterPoints(a, a.next)!!
                    c = filterPoints(c, c.next)!!

                    earcutLinked(a, triangles, dim, 0)
                    earcutLinked(c, triangles, dim, 0)
                    return
                }
                b = b.next
            }
            a = a.next!!
        } while (a !== start)
    }

    private fun eliminateHoles(data: DoubleArray, holeIndices: IntArray, outerNodeIn: Node, dim: Int): Node? {
        val queue = ArrayList<Node>()
        val len = holeIndices.size
        for (i in 0 until len) {
            val start = holeIndices[i] * dim
            val end = if (i < len - 1) holeIndices[i + 1] * dim else data.size
            val list = linkedList(data, start, end, dim, false)
            if (list === list!!.next) list.steiner = true
            queue.add(getLeftmost(list))
        }

        queue.sortBy { it.x }

        var outerNode: Node? = outerNodeIn
        for (node in queue) {
            outerNode = eliminateHole(node, outerNode!!)
        }
        return outerNode
    }

    private fun eliminateHole(hole: Node, outerNode: Node): Node {
        val bridge = findHoleBridge(hole, outerNode) ?: return outerNode
        val bridgeReverse = splitPolygon(bridge, hole)
        filterPoints(bridgeReverse, bridgeReverse.next)
        return filterPoints(bridge, bridge.next)!!
    }

    private fun findHoleBridge(hole: Node, outerNode: Node): Node? {
        var p = outerNode
        val hx = hole.x
        val hy = hole.y
        var qx = Double.NEGATIVE_INFINITY
        var m: Node? = null

        do {
            if (hy <= p.y && hy >= p.next!!.y && p.next!!.y != p.y) {
                val x = p.x + (hy - p.y) * (p.next!!.x - p.x) / (p.next!!.y - p.y)
                if (x in qx..hx) {
                    qx = x
                    m = if (p.x < p.next!!.x) p else p.next
                }
            }
            p = p.next!!
        } while (p !== outerNode)

        if (m == null) return null
        if (hx == qx) return m

        val stop = m
        val mx = m.x
        val my = m.y
        var tanMin = Double.POSITIVE_INFINITY

        p = m
        do {
            if (hx >= p.x && p.x >= mx && hx != p.x &&
                pointInTriangle(if (hy < my) hx else qx, hy, mx, my, if (hy < my) qx else hx, hy, p.x, p.y)
            ) {
                val tan = Math.abs(hy - p.y) / (hx - p.x)
                if (locallyInside(p, hole) && (tan < tanMin || (tan == tanMin && (p.x > m!!.x || (p.x == m.x && sectorContainsSector(m, p)))))) {
                    m = p
                    tanMin = tan
                }
            }
            p = p.next!!
        } while (p !== stop)

        return m
    }

    private fun sectorContainsSector(m: Node, p: Node): Boolean =
        area(m.prev!!, m, p.prev!!) < 0 && area(p.next!!, m, m.next!!) < 0

    private fun getLeftmost(start: Node): Node {
        var p = start
        var leftmost = start
        do {
            if (p.x < leftmost.x || (p.x == leftmost.x && p.y < leftmost.y)) leftmost = p
            p = p.next!!
        } while (p !== start)
        return leftmost
    }

    private fun isValidDiagonal(a: Node, b: Node): Boolean =
        a.next!!.i != b.i && a.prev!!.i != b.i && !intersectsPolygon(a, b) &&
            (locallyInside(a, b) && locallyInside(b, a) && middleInside(a, b) &&
                (area(a.prev!!, a, b.prev!!) != 0.0 || area(a, b.prev!!, b) != 0.0) ||
                equals(a, b) && area(a.prev!!, a, a.next!!) > 0 && area(b.prev!!, b, b.next!!) > 0)

    private fun area(p: Node, q: Node, r: Node): Double =
        (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y)

    private fun equals(p1: Node, p2: Node): Boolean = p1.x == p2.x && p1.y == p2.y

    private fun intersects(p1: Node, q1: Node, p2: Node, q2: Node): Boolean {
        val o1 = sign(area(p1, q1, p2))
        val o2 = sign(area(p1, q1, q2))
        val o3 = sign(area(p2, q2, p1))
        val o4 = sign(area(p2, q2, q1))

        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(p1, p2, q1)) return true
        if (o2 == 0 && onSegment(p1, q2, q1)) return true
        if (o3 == 0 && onSegment(p2, p1, q2)) return true
        if (o4 == 0 && onSegment(p2, q1, q2)) return true
        return false
    }

    private fun onSegment(p: Node, q: Node, r: Node): Boolean =
        q.x <= maxOf(p.x, r.x) && q.x >= minOf(p.x, r.x) && q.y <= maxOf(p.y, r.y) && q.y >= minOf(p.y, r.y)

    private fun sign(num: Double): Int = if (num > 0) 1 else if (num < 0) -1 else 0

    private fun intersectsPolygon(a: Node, b: Node): Boolean {
        var p = a
        do {
            if (p.i != a.i && p.next!!.i != a.i && p.i != b.i && p.next!!.i != b.i &&
                intersects(p, p.next!!, a, b)
            ) {
                return true
            }
            p = p.next!!
        } while (p !== a)
        return false
    }

    private fun locallyInside(a: Node, b: Node): Boolean =
        if (area(a.prev!!, a, a.next!!) < 0) {
            area(a, b, a.next!!) >= 0 && area(a, a.prev!!, b) >= 0
        } else {
            area(a, b, a.prev!!) < 0 || area(a, a.next!!, b) < 0
        }

    private fun middleInside(a: Node, b: Node): Boolean {
        var p = a
        var inside = false
        val px = (a.x + b.x) / 2
        val py = (a.y + b.y) / 2
        do {
            if (((p.y > py) != (p.next!!.y > py)) && p.next!!.y != p.y &&
                px < (p.next!!.x - p.x) * (py - p.y) / (p.next!!.y - p.y) + p.x
            ) {
                inside = !inside
            }
            p = p.next!!
        } while (p !== a)
        return inside
    }

    private fun splitPolygon(a: Node, b: Node): Node {
        val a2 = Node(a.i, a.x, a.y)
        val b2 = Node(b.i, b.x, b.y)
        val an = a.next!!
        val bp = b.prev!!

        a.next = b
        b.prev = a

        a2.next = an
        an.prev = a2

        b2.next = a2
        a2.prev = b2

        bp.next = b2
        b2.prev = bp

        return b2
    }

    private fun insertNode(i: Int, x: Double, y: Double, last: Node?): Node {
        val p = Node(i, x, y)
        if (last == null) {
            p.prev = p
            p.next = p
        } else {
            p.next = last.next
            p.prev = last
            last.next!!.prev = p
            last.next = p
        }
        return p
    }

    private fun removeNode(p: Node) {
        p.next!!.prev = p.prev
        p.prev!!.next = p.next
    }

    private fun pointInTriangle(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double
    ): Boolean =
        (cx - px) * (ay - py) - (ax - px) * (cy - py) >= 0 &&
            (ax - px) * (by - py) - (bx - px) * (ay - py) >= 0 &&
            (bx - px) * (cy - py) - (cx - px) * (by - py) >= 0

    private fun signedArea(data: DoubleArray, start: Int, end: Int, dim: Int): Double {
        var sum = 0.0
        var j = end - dim
        var i = start
        while (i < end) {
            sum += (data[j] - data[i]) * (data[i + 1] + data[j + 1])
            j = i
            i += dim
        }
        return sum
    }
}
