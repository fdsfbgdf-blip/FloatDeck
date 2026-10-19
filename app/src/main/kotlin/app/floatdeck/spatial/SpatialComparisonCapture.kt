package app.floatdeck.spatial

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30 as GL
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Explicit GL-thread export only. No per-frame readback, timing fence, or private file sharing. */
internal object SpatialComparisonCapture {
    fun capture(context: Context, programs: Map<SpatialAlgorithm, Int>, restore: Int, width: Int, height: Int,
                metadata: JSONObject, sources: List<String>): File {
        require(width.toLong()*height <= 4_194_304) { "预览过大，拒绝分配读回内存" }
        val target=File(context.filesDir,"spatial_comparisons").apply { mkdirs() }
        val zip=File(target,"comparison-${System.currentTimeMillis()}.zip")
        // Read uniforms once. Both algorithms and all screenshot/timing passes use this exact snapshot.
        val floatUniforms=mapOf("uCover" to 2,"uMotion" to 2,"uCamera" to 2,"uDepthRange" to 2,"uTexel" to 2,"uSurfaceTolerance" to 1)
        val floats=floatUniforms.mapValues { (name,n) -> FloatArray(n).also { GL.glGetUniformfv(restore,GL.glGetUniformLocation(restore,name),it,0) } }
        val intUniforms=listOf("uImage","uDepth","uClock","uMask","uClockEnabled","uShowFallback")
        val ints=intUniforms.associateWith { name -> IntArray(1).also { GL.glGetUniformiv(restore,GL.glGetUniformLocation(restore,name),it,0) }[0] }
        val dither=GL.glIsEnabled(GL.GL_DITHER)
        val pixels=ByteBuffer.allocateDirect(width*height*4)
        val argb=IntArray(width*height)
        val timings=StringBuilder("algorithm,sync_median_ms,gpu_timer_median_ms,gpu_timer_valid,viewport_width,viewport_height\n")
        val timerAvailable=(GL.glGetString(GL.GL_EXTENSIONS) ?: "").split(' ').contains("GL_EXT_disjoint_timer_query")
        val query=IntArray(1)
        if(timerAvailable) GL.glGenQueries(1,query,0)
        fun apply(program: Int) {
            GL.glUseProgram(program)
            for((name,v) in floats) {
                val loc=GL.glGetUniformLocation(program,name)
                if(v.size==2) GL.glUniform2fv(loc,1,v,0) else GL.glUniform1f(loc,v[0])
            }
            for((name,v) in ints) GL.glUniform1i(GL.glGetUniformLocation(program,name),v)
            GL.glUniform1i(GL.glGetUniformLocation(program,"uProbe"),0)
            GL.glUniform1i(GL.glGetUniformLocation(program,"uShowFallback"),0)
            GL.glUniform1i(GL.glGetUniformLocation(program,"uMeasureFallback"),0)
        }
        fun ZipOutputStream.png(name: String) {
            pixels.clear(); GL.glReadPixels(0,0,width,height,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,pixels)
            check(GL.glGetError()==GL.GL_NO_ERROR) { "截图 GPU 读回错误" }
            for(y in 0 until height) for(x in 0 until width) {
                val i=(y*width+x)*4
                argb[(height-1-y)*width+x]=(-0x1000000 or ((pixels.get(i).toInt() and 255) shl 16)
                    or ((pixels.get(i+1).toInt() and 255) shl 8) or (pixels.get(i+2).toInt() and 255))
            }
            val bitmap=Bitmap.createBitmap(argb,width,height,Bitmap.Config.ARGB_8888)
            try { putNextEntry(ZipEntry(name)); check(bitmap.compress(Bitmap.CompressFormat.PNG,100,this)); closeEntry() }
            finally { bitmap.recycle() }
        }
        try {
            GL.glDisable(GL.GL_DITHER)
            ZipOutputStream(zip.outputStream()).use { out ->
                for((algorithm,p) in programs) {
                    apply(p)
                    GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
                    out.png("${algorithm.name}-normal.png")
                    GL.glUniform1i(GL.glGetUniformLocation(p,"uMeasureFallback"),1)
                    GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
                    out.png("${algorithm.name}-failure-mask.png")
                    GL.glUniform1i(GL.glGetUniformLocation(p,"uMeasureFallback"),0)
                    repeat(2) { GL.glDrawArrays(GL.GL_TRIANGLES,0,3) }; GL.glFinish()
                    val sync=mutableListOf<Double>(); val gpu=mutableListOf<Double>()
                    repeat(9) {
                        val start=System.nanoTime()
                        if(timerAvailable) GL.glBeginQuery(0x88BF,query[0]) // GL_TIME_ELAPSED_EXT
                        GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
                        if(timerAvailable) GL.glEndQuery(0x88BF)
                        GL.glFinish()
                        sync.add((System.nanoTime()-start)/1e6)
                        if(timerAvailable) {
                            val value=IntArray(1); val disjoint=IntArray(1)
                            GL.glGetQueryObjectuiv(query[0],GL.GL_QUERY_RESULT,value,0)
                            GL.glGetIntegerv(0x8FBB,disjoint,0) // GL_GPU_DISJOINT_EXT
                            if(disjoint[0]==0 && sync.last()<4000.0) gpu.add((value[0].toLong() and 0xffffffffL)/1e6)
                        }
                        check(GL.glGetError()==GL.GL_NO_ERROR) { "性能计时 GL 错误，不能报告有效结果" }
                    }
                    timings.append("${algorithm.name},${sync.sorted()[4]},${if(gpu.size==9) gpu.sorted()[4] else "unavailable"},${gpu.size==9},$width,$height\n")
                }
                metadata.put("width",width).put("height",height).put("timer_extension",timerAvailable)
                    .put("timing_note","On-demand fences only; sync includes CPU/driver. EXT timer is 32-bit, invalid if disjoint. Not sustained FPS.")
                    .put("unknown_background","V3 gray checker = no observed surface; never synthesized or copied foreground")
                for((name,v) in floats) metadata.put(name,org.json.JSONArray(v.map { it.toDouble() }))
                for((i,path) in sources.withIndex()) {
                    val digest=MessageDigest.getInstance("SHA-256")
                    File(path).inputStream().use { input -> val b=ByteArray(65536); while(true) { val n=input.read(b); if(n<0) break; digest.update(b,0,n) } }
                    metadata.put("source_${i}_sha256",digest.digest().joinToString("") { "%02x".format(it) })
                }
                out.putNextEntry(ZipEntry("metadata.json")); out.write(metadata.toString(2).toByteArray()); out.closeEntry()
                out.putNextEntry(ZipEntry("performance.csv")); out.write(timings.toString().toByteArray()); out.closeEntry()
            }
            return zip
        } catch(e: Exception) { zip.delete(); throw e }
        finally {
            if(timerAvailable) GL.glDeleteQueries(1,query,0)
            GL.glUseProgram(restore)
            GL.glUniform1i(GL.glGetUniformLocation(restore,"uMeasureFallback"),0)
            GL.glUniform1i(GL.glGetUniformLocation(restore,"uShowFallback"),ints.getValue("uShowFallback"))
            if(dither) GL.glEnable(GL.GL_DITHER)
            GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
        }
    }
}
