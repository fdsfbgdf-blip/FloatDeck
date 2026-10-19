package app.floatdeck.spatial

import android.opengl.GLES30 as GL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit on-demand readback of the production ray intersection, never a target-value proxy.
 * Source sampling displacement is the inverse of feature motion on a uniform depth patch.
 * 1x6 RGBA8 framebuffer = 24 bytes. No per-frame readback or extra full-size textures.
 */
internal class SpatialGpuProbe {
    data class Point(val label: String, val u: Float, val v: Float)
    private val fbo = IntArray(1)
    private val texture = IntArray(1)
    private val buffer = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder())
    private val before = ByteArray(24)
    private val after = ByteArray(24)
    fun release() {
        GL.glDeleteFramebuffers(1, fbo, 0); GL.glDeleteTextures(1, texture, 0)
        fbo[0] = 0; texture[0] = 0
    }
    fun measure(program: Int, points: List<Point>, cover: FloatArray, width: Int, height: Int,
                mx: Float, my: Float): String {
        if (points.size != 3) return "未找到可观测的三个深度区间；不能虚构层间实测"
        val uv = FloatArray(6)
        for (i in 0..2) {
            uv[i*2] = (points[i].u-0.5f)/cover[0]+0.5f
            uv[i*2+1] = (points[i].v-0.5f)/cover[1]+0.5f
            if (uv[i*2] !in 0.03f..0.97f || uv[i*2+1] !in 0.03f..0.97f)
                return "当前裁切下三类采样点不可同时观测；未作实测"
        }
        val binding=IntArray(1); GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING,binding,0)
        val dither=GL.glIsEnabled(GL.GL_DITHER)
        try {
            if(fbo[0]==0) {
                GL.glGenTextures(1,texture,0); GL.glActiveTexture(GL.GL_TEXTURE4)
                GL.glBindTexture(GL.GL_TEXTURE_2D,texture[0])
                GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA8,1,6,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,null)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_NEAREST)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_NEAREST)
                GL.glGenFramebuffers(1,fbo,0)
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,fbo[0])
                GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,texture[0],0)
            } else GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,fbo[0])
            check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)==GL.GL_FRAMEBUFFER_COMPLETE) { "读回 framebuffer 不完整" }
            GL.glViewport(0,0,1,6); GL.glDisable(GL.GL_DITHER)
            val probe=GL.glGetUniformLocation(program,"uProbe")
            GL.glUniform1i(probe,1)
            GL.glUniform2fv(GL.glGetUniformLocation(program,"uProbeUV[0]"),3,uv,0)
            fun read(motionX: Float,motionY: Float,out: ByteArray) {
                GL.glUniform2f(GL.glGetUniformLocation(program,"uMotion"),motionX,motionY)
                GL.glUniform2f(GL.glGetUniformLocation(program,"uCamera"),motionX*SpatialMath.CAMERA_SHARE,motionY*SpatialMath.CAMERA_SHARE)
                GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
                buffer.clear(); GL.glReadPixels(0,0,1,6,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,buffer)
                buffer.position(0); buffer.get(out)
            }
            read(0f,0f,before); read(mx,my,after)
            val error=GL.glGetError(); check(error==GL.GL_NO_ERROR) { "GL=0x${error.toString(16)}" }
            fun component(data:ByteArray,index:Int)=((data[index].toInt() and 255)*256+(data[index+1].toInt() and 255))/65535f
            return buildString {
                append("GPU源采样位移实测（相对中性帧，非物体光流）：")
                for(i in 0..2) {
                    val dx=(component(after,i*4)-component(before,i*4))/cover[0]*width
                    val dy=(component(after,i*4+2)-component(before,i*4+2))/cover[1]*height
                    append("\n${points[i].label}采样点：X %.2f / Y %.2f px；有效深度 %.2f→%.2f；交点%s".format(java.util.Locale.US,dx,dy,
                        (before[(i+3)*4].toInt() and 255)/255f,(after[(i+3)*4].toInt() and 255)/255f,
                        if((after[(i+3)*4+1].toInt() and 255)>128) "回退（此点不能作为可靠物体位移）" else "有效"))
                }
            }
        } catch(e: Exception) { return "GPU实测失败：${e.message}" }
        finally {
            GL.glUniform1i(GL.glGetUniformLocation(program,"uProbe"),0)
            GL.glUniform2f(GL.glGetUniformLocation(program,"uMotion"),mx,my)
            GL.glUniform2f(GL.glGetUniformLocation(program,"uCamera"),mx*SpatialMath.CAMERA_SHARE,my*SpatialMath.CAMERA_SHARE)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,binding[0]); GL.glViewport(0,0,width,height)
            if(dither) GL.glEnable(GL.GL_DITHER)
        }
    }
    companion object {
        fun points(pixels:IntArray,w:Int,h:Int,min:Int,max:Int): List<Point> {
            val best=FloatArray(3) { Float.MAX_VALUE }; val found=arrayOfNulls<Point>(3)
            // A stable patch is preferable to an extreme-depth edge or antialiasing pixel.
            val labels=arrayOf("远景","中景","近景")
            val radius = maxOf(3,(minOf(w,h)*0.075f).toInt())
            val offsets=intArrayOf(-2,2,-2*w,2*w,-radius,radius,-radius*w,radius*w)
            for(y in radius until h-radius step 3) for(x in radius until w-radius step 3) {
                val z=(pixels[y*w+x] ushr 16) and 255
                val d=SpatialMath.normalizedDepth(z/255f,min/255f,max/255f)
                val index=when { d<0.15f -> 0; d in 0.35f..0.65f -> 1; d>0.85f -> 2; else -> -1 }
                if(index<0) continue
                var stable=true
                for(offset in offsets) if(kotlin.math.abs(((pixels[y*w+x+offset] ushr 16) and 255)-z)>3) { stable=false; break }
                if(!stable) continue
                val u=(x+0.5f)/w; val v=(y+0.5f)/h
                val score=(u-0.5f)*(u-0.5f)+(v-0.5f)*(v-0.5f)
                if(score<best[index]) { best[index]=score; found[index]=Point(labels[index],u,v) }
            }
            return found.filterNotNull()
        }
    }
}
