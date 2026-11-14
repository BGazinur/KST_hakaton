package com.arengineering.inspection.cv

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/** Pixel-edge geometry. Actual rounded resize scales are retained independently on X and Y. */
class LetterboxTransform private constructor(
    val rawWidth: Int, val rawHeight: Int, val rotationCw: Int, val inputSize: Int,
    val uprightWidth: Int, val uprightHeight: Int,
    val resizedWidth: Int, val resizedHeight: Int, val padLeft: Int, val padTop: Int
) {
    val gain: Double get() = min(inputSize.toDouble()/uprightWidth,inputSize.toDouble()/uprightHeight)
    val scaleX: Double get() = resizedWidth.toDouble()/uprightWidth
    val scaleY: Double get() = resizedHeight.toDouble()/uprightHeight
    val padRight: Int get() = inputSize-resizedWidth-padLeft
    val padBottom: Int get() = inputSize-resizedHeight-padTop

    /** RAW normalized input -> upright pixel edges -> model pixel edges. */
    fun rawToModel(point: Point2): Point2 {
        val upright = when(rotationCw) {
            0 -> Point2(point.x*rawWidth,point.y*rawHeight)
            90 -> Point2((1.0-point.y)*rawHeight,point.x*rawWidth)
            180 -> Point2((1.0-point.x)*rawWidth,(1.0-point.y)*rawHeight)
            else -> Point2(point.y*rawHeight,(1.0-point.x)*rawWidth)
        }
        return Point2(upright.x*scaleX+padLeft,upright.y*scaleY+padTop)
    }

    /** Inverse of rawToModel. Deliberately does not clamp padding to an image-border point. */
    fun modelToRaw(point: Point2): Point2 {
        val x = (point.x-padLeft)/resizedWidth
        val y = (point.y-padTop)/resizedHeight
        return when(rotationCw) {
            0 -> Point2(x,y)
            90 -> Point2(y,1.0-x)
            180 -> Point2(1.0-x,1.0-y)
            else -> Point2(1.0-y,x)
        }
    }

    fun modelBoxToRaw(box: Box2): Box2 {
        val corners = listOf(
            Point2(box.left,box.top), Point2(box.right,box.top),
            Point2(box.right,box.bottom), Point2(box.left,box.bottom)
        ).map { modelToRaw(it) }
        return Box2(corners.minOf { it.x }.coerceIn(0.0,1.0),corners.minOf { it.y }.coerceIn(0.0,1.0),
            corners.maxOf { it.x }.coerceIn(0.0,1.0),corners.maxOf { it.y }.coerceIn(0.0,1.0))
    }

    companion object {
        fun create(rawWidth: Int,rawHeight: Int,rotationCw: Int,inputSize: Int = 416): LetterboxTransform {
            require(rawWidth > 0 && rawHeight > 0 && inputSize > 0)
            require(rotationCw in listOf(0,90,180,270))
            val w = if (rotationCw%180 == 0) rawWidth else rawHeight
            val h = if (rotationCw%180 == 0) rawHeight else rawWidth
            val gain = min(inputSize.toDouble()/w,inputSize.toDouble()/h)
            // Ties-to-even, matching Python round used by Ultralytics LetterBox.
            val rw = round(w*gain).toInt().coerceIn(1,inputSize)
            val rh = round(h*gain).toInt().coerceIn(1,inputSize)
            return LetterboxTransform(rawWidth,rawHeight,rotationCw,inputSize,w,h,rw,rh,(inputSize-rw)/2,(inputSize-rh)/2)
        }
    }
}

object Geometry {
    fun intersectionOverUnion(a: Box2,b: Box2): Double {
        val intersection = max(0.0,min(a.right,b.right)-max(a.left,b.left))*max(0.0,min(a.bottom,b.bottom)-max(a.top,b.top))
        val union = a.area+b.area-intersection
        return if (union > 0) intersection/union else 0.0
    }

    /** Intrinsics use RAW pixels. Convert CV x-right/y-down/z-forward to ARCore OpenGL camera axes. */
    fun sourceWorldRay(snapshot: GeometrySnapshot, point: Point2): SourceWorldRay {
        require(point.x.isFinite() && point.y.isFinite() && point.x in 0.0..1.0 && point.y in 0.0..1.0)
        val k = snapshot.intrinsics
        val x = (point.x*snapshot.stamp.imageWidth-k.cx)/k.fx
        val y = -(point.y*snapshot.stamp.imageHeight-k.cy)/k.fy
        val z = -1.0
        val length = sqrt(x*x+y*y+z*z)
        val v = Point3(x/length,y/length,z/length)
        val p = snapshot.cameraPose
        // q*v*q^-1, using t=2*cross(q.xyz,v).
        val tx = 2.0*(p.qy*v.z-p.qz*v.y)
        val ty = 2.0*(p.qz*v.x-p.qx*v.z)
        val tz = 2.0*(p.qx*v.y-p.qy*v.x)
        val world = Point3(v.x+p.qw*tx+p.qy*tz-p.qz*ty,v.y+p.qw*ty+p.qz*tx-p.qx*tz,v.z+p.qw*tz+p.qx*ty-p.qy*tx)
        return SourceWorldRay(snapshot.stamp,Point3(p.tx,p.ty,p.tz),world)
    }
}
