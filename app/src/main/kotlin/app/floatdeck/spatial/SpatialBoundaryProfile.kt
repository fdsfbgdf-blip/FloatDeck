package app.floatdeck.spatial

import kotlin.math.*

/** Actual source-depth envelopes, retained as O(width+height) prefixes, not extra textures. */
class SpatialBoundaryProfile private constructor(
    val width: Int, val height: Int,
    private val left: FloatArray, private val right: FloatArray,
    private val top: FloatArray, private val bottom: FloatArray,
) {
    data class Edges(val left: Float, val right: Float, val top: Float, val bottom: Float)
    fun edges(cover: FloatArray): Edges {
        val nx = (((1f-cover[0])*width/2f).toInt()+2).coerceIn(1,width)
        val ny = (((1f-cover[1])*height/2f).toInt()+2).coerceIn(1,height)
        return Edges(left[nx-1],right[nx-1],top[ny-1],bottom[ny-1])
    }
    companion object {
        fun create(pixels: IntArray, width: Int, height: Int, min: Int, max: Int): SpatialBoundaryProfile {
            require(width > 0 && height > 0 && width.toLong()*height == pixels.size.toLong())
            val cols=FloatArray(width); val rows=FloatArray(height)
            for (y in 0 until height) for (x in 0 until width) {
                val z=SpatialMath.normalizedDepth(((pixels[y*width+x] ushr 16) and 255)/255f,min/255f,max/255f)
                cols[x]=maxOf(cols[x],z); rows[y]=maxOf(rows[y],z)
            }
            fun prefix(values: FloatArray, reverse: Boolean): FloatArray {
                var v=0f
                return FloatArray(values.size) { i -> v=maxOf(v,values[if(reverse) values.lastIndex-i else i]); v }
            }
            return SpatialBoundaryProfile(width,height,prefix(cols,false),prefix(cols,true),prefix(rows,false),prefix(rows,true))
        }
    }
}

/** All offsets below use visible viewport-width units, including the Y component. */
object SpatialBoundaryPlanner {
    data class Plan(
        val cover: FloatArray, val requestedX: Float, val requestedY: Float,
        val appliedX: Float, val appliedY: Float, val limitX: Float, val limitY: Float,
        val gain: Float, val extraZoom: Float, val edges: SpatialBoundaryProfile.Edges, val reason: String,
    )
    fun plan(base: FloatArray, profile: SpatialBoundaryProfile?, viewportWidth: Int, viewportHeight: Int,
             imageWidth: Int, imageHeight: Int, amplitude: Float, x: Float, y: Float): Plan {
        val aspect=viewportWidth.toFloat()/viewportHeight.coerceAtLeast(1)
        fun edges(c:FloatArray)=profile?.edges(c) ?: SpatialBoundaryProfile.Edges(1f,1f,1f,1f)
        var cover=base.copyOf(); var edge=edges(cover); var zoom=1f
        // Last resort only: at most 4% extra crop, and never cut a newly detected near region.
        if (amplitude>0f && maxOf(edge.left,edge.right,edge.top,edge.bottom)<=0.35f) {
            val sx=(0.5f-1f/imageWidth.coerceAtLeast(2))/(0.5f+amplitude*(SpatialMath.CAMERA_SHARE+maxOf(edge.left,edge.right)))
            val sy=(0.5f-1f/imageHeight.coerceAtLeast(2))/(0.5f+amplitude*aspect*(SpatialMath.CAMERA_SHARE+maxOf(edge.top,edge.bottom)))
            val needed=maxOf(1f,base[0]/sx,base[1]/sy)
            val candidate=minOf(needed,1.04f)
            val proposed=floatArrayOf(base[0]/candidate,base[1]/candidate)
            val next=edges(proposed)
            if (maxOf(next.left,next.right,next.top,next.bottom)<=0.35f) { zoom=candidate; cover=proposed; edge=next }
        }
        val dx=amplitude*x; val dy=amplitude*y
        val marginX=((0.5f-1f/imageWidth.coerceAtLeast(2))/cover[0]-0.5f).coerceAtLeast(0f)
        val marginY=((0.5f-1f/imageHeight.coerceAtLeast(2))/cover[1]-0.5f).coerceAtLeast(0f)/aspect
        val limitX=marginX/(SpatialMath.CAMERA_SHARE+if(dx>=0) edge.left else edge.right)
        val limitY=marginY/(SpatialMath.CAMERA_SHARE+if(dy>=0) edge.top else edge.bottom)
        val norm=hypot(if(limitX>0) dx/limitX else if(dx==0f) 0f else 1e9f,
                       if(limitY>0) dy/limitY else if(dy==0f) 0f else 1e9f)
        val scale=if(norm>1f) 1f/norm else 1f
        return Plan(cover,dx,dy,dx*scale,dy*scale,limitX,limitY,amplitude*scale,zoom,edge,buildString {
            if(scale<0.99999f) append("边缘深度/采样余量不足，沿原方向限幅；") else append("已按真实边缘深度使用源图余量，未截断当前位移；")
            if(zoom>1.00001f) append("低深度背景最小裁切补偿 %.2f%%；".format(java.util.Locale.US,(zoom-1)*100))
            else append("未追加缩放；")
            append("未虚构边外背景，近景边缘不可可靠补全时保留保护")
        })
    }
}
