package app.floatdeck.spatial

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30 as GL
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/** Uses real Android PNG decode/GLUtils upload and the production ES3 Shader, not a mock GPU. */
class SpatialRenderABTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val w=320; private val h=480
    private fun withGl(test:()->Unit) {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
        val config=arrayOfNulls<android.opengl.EGLConfig>(1)
        val attrs=intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
            EGL14.EGL_RENDERABLE_TYPE,EGLExt.EGL_OPENGL_ES3_BIT_KHR,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_NONE)
        assertTrue(EGL14.eglChooseConfig(display,attrs,0,config,0,1,IntArray(1),0))
        val surface=EGL14.eglCreatePbufferSurface(display,config[0],intArrayOf(EGL14.EGL_WIDTH,w,EGL14.EGL_HEIGHT,h,EGL14.EGL_NONE),0)
        val glContext=EGL14.eglCreateContext(display,config[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
        assertTrue(EGL14.eglMakeCurrent(display,surface,surface,glContext))
        try { test() } finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,glContext);EGL14.eglTerminate(display)
        }
    }
    private fun fixture(test:(android.content.SharedPreferences,List<File>)->Unit) {
        val prefs=context.getSharedPreferences("AB-${UUID.randomUUID()}",0)
        val files=listOf("image","depth").map { name -> File(context.cacheDir,"AB-${UUID.randomUUID()}-$name.png") }
        val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val depth=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(w*h)
        for(y in 0 until h) for(x in 0 until w) {
            val z=if(y in h/4..h*3/4) when {
                x==w/2-1 -> 70
                x==w/2 -> 190
                x in w/2+1..w*3/4 -> 240
                else -> 16
            } else 16
            pixels[y*w+x]=Color.rgb(z,z,z)
            image.setPixel(x,y,Color.rgb(30+x*150/w,40+y*140/h,80))
        }
        depth.setPixels(pixels,0,w,0,0,w,h)
        listOf(image,depth).forEachIndexed { i,b -> files[i].outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
        prefs.edit().putString("image",files[0].path).putString("depth",files[1].path).putFloat("strength",1f)
            .putString("parallax_mode","enhanced").putFloat("crop",0.04f).commit()
        try { test(prefs,files) } finally { files.forEach { it.delete() };prefs.edit().clear().commit() }
    }
    private fun frame():ByteArray {
        val b=ByteBuffer.allocateDirect(w*h*4);GL.glReadPixels(0,0,w,h,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,b)
        assertEquals(0,GL.glGetError());val result=ByteArray(b.capacity());b.position(0);b.get(result);return result
    }
    private fun textureRed(texture:Int,x:Int,y:Int):Int {
        val fbo=IntArray(1);GL.glGenFramebuffers(1,fbo,0)
        try {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,fbo[0])
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,texture,0)
            assertEquals(GL.GL_FRAMEBUFFER_COMPLETE,GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER))
            val b=ByteBuffer.allocateDirect(4);GL.glReadPixels(x,y,1,1,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,b)
            assertEquals(0,GL.glGetError());return b.get(0).toInt() and 255
        } finally { GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0);GL.glDeleteFramebuffers(1,fbo,0) }
    }
    @Test fun actualDepthTextureSwitchesWithoutChangingPoseUniformsPreferencesOrSourceFiles()=fixture { prefs,files -> withGl {
        val sourceBytes=files.map { it.readBytes() };val saved=prefs.all
        val renderer=SpatialRenderer(context,prefs,diagnosticSession=true)
        renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,w,h)
        renderer.forcedView=SpatialPose.Input(0.6f,-0.4f)
        try {
            renderer.onDrawFrame(null);val repaired=renderer.diagnostics
            assertTrue(repaired.message,repaired.ready);assertTrue(repaired.depthRepairChangedPixels>0)
            assertEquals(16,textureRed(repaired.depthTexture,w/2-1,h/2))
            val photoRed=textureRed(repaired.imageTexture,50,50)
            renderer.useOriginalDepth=true;renderer.onDrawFrame(null);val raw=renderer.diagnostics
            assertTrue(raw.ready);assertFalse(raw.depthRepairEnabled)
            assertEquals(70,textureRed(raw.depthTexture,w/2-1,h/2))
            assertEquals(photoRed,textureRed(raw.imageTexture,50,50))
            assertEquals(repaired.viewX,raw.viewX,0f);assertEquals(repaired.viewY,raw.viewY,0f)
            assertEquals(repaired.motionX,raw.motionX,0f);assertEquals(repaired.motionY,raw.motionY,0f)
            assertEquals(repaired.cameraX,raw.cameraX,0f);assertEquals(repaired.cameraY,raw.cameraY,0f)
            assertEquals(repaired.extraZoom,raw.extraZoom,0f);assertEquals(repaired.safePercent,raw.safePercent,0f)
            assertEquals(repaired.normalizationMin,raw.normalizationMin);assertEquals(repaired.normalizationMax,raw.normalizationMax)
            assertEquals(4,raw.depthLevels);assertEquals(2,repaired.depthLevels)
            renderer.useOriginalDepth=false;renderer.onDrawFrame(null)
            assertEquals(16,textureRed(renderer.diagnostics.depthTexture,w/2-1,h/2))
            assertEquals(saved,prefs.all)
            files.forEachIndexed { i,f -> assertArrayEquals(sourceBytes[i],f.readBytes()) }
        } finally { renderer.release() }
    } }
    @Test fun binaryGpuFailureCountEqualsPurpleActualPathAndNormalFrameIsRestored()=fixture { prefs,_ -> withGl {
        val renderer=SpatialRenderer(context,prefs,diagnosticSession=true)
        renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,w,h)
        val rows=mutableListOf("depth,view_x,view_y,strength,failed,total,percent,gl")
        try {
            for(raw in listOf(false,true)) for(view in listOf(SpatialPose.Input(),SpatialPose.Input(0.9f,0f),SpatialPose.Input(0f,-0.9f),SpatialPose.Input(0.6f,-0.6f))) {
                renderer.useOriginalDepth=raw;renderer.forcedView=view;renderer.showFallback=false
                renderer.onDrawFrame(null);val normal=frame()
                renderer.measureFallbackRequested=true;renderer.onDrawFrame(null);val s=renderer.diagnostics
                assertTrue(s.message,s.ready);assertEquals(0,s.fallbackGlError)
                assertEquals(w*h,s.fallbackSampleWidth*s.fallbackSampleHeight)
                assertTrue(s.fallbackSampleForced)
                assertEquals(view.x,s.fallbackSampleViewX,0.0001f);assertEquals(view.y,s.fallbackSampleViewY,0.0001f)
                assertArrayEquals("Binary diagnostic pass must never remain visible",normal,frame())
                renderer.showFallback=true;renderer.onDrawFrame(null);val purple=frame()
                var failed=0
                for(i in 0 until w*h) if((purple[i*4].toInt() and 255)==255 && purple[i*4+1].toInt()==0 && (purple[i*4+2].toInt() and 255)==255) failed++
                assertEquals("Mask must mark exactly the actual purple fallback branch",failed,s.fallbackPixels)
                assertEquals(failed*100f/(w*h),s.fallbackPercent,0.00001f)
                if(view.x==0f && view.y==0f && !raw) assertEquals("Sharp repaired zero-motion scene must hit, not mark all depth edges",0,failed)
                if(view.x!=0f || view.y!=0f) assertTrue("Occlusion fixture must exercise real fallback",failed>0)
                rows.add("$raw,${view.x},${view.y},${s.fallbackSampleStrength},$failed,${w*h},${s.fallbackPercent},${s.fallbackGlError}")
                renderer.showFallback=false
            }
            File(context.cacheDir,"render-diagnostics.csv").writeText(rows.joinToString("\n")+"\n")
            // Mode change must invalidate, not relabel, a previous capture.
            renderer.useOriginalDepth=false;renderer.onDrawFrame(null)
            assertEquals(-1,renderer.diagnostics.fallbackPixels)
        } finally { renderer.release() }
    } }
    @Test fun productionRendererIgnoresDiagnosticFlagsAndReloadRetainsDiagnosticSessionChoices()=fixture { prefs,_ -> withGl {
        val renderer=SpatialRenderer(context,prefs)
        renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,w,h)
        renderer.forcedView=SpatialPose.Input(0.6f,-0.4f)
        try {
            renderer.onDrawFrame(null);val baseline=frame()
            renderer.useOriginalDepth=true;renderer.showFallback=true;renderer.measureFallbackRequested=true
            renderer.onDrawFrame(null)
            assertArrayEquals(baseline,frame());assertTrue(renderer.diagnostics.depthRepairEnabled)
            assertFalse(renderer.diagnostics.showFallback);assertEquals(-1,renderer.diagnostics.fallbackPixels)
            assertEquals(16,textureRed(renderer.diagnostics.depthTexture,w/2-1,h/2))
        } finally { renderer.release() }
        val diagnostic=SpatialRenderer(context,prefs,diagnosticSession=true)
        diagnostic.forcedView=SpatialPose.Input(0.6f,-0.4f);diagnostic.useOriginalDepth=true
        try {
            repeat(2) {
                diagnostic.onSurfaceCreated(null,null);diagnostic.onSurfaceChanged(null,w,h);diagnostic.onDrawFrame(null)
                assertFalse(diagnostic.diagnostics.depthRepairEnabled)
                assertEquals(70,textureRed(diagnostic.diagnostics.depthTexture,w/2-1,h/2))
                assertEquals(0.6f,diagnostic.diagnostics.viewX,0f);assertEquals(-0.4f,diagnostic.diagnostics.viewY,0f)
                diagnostic.release()
            }
        } finally { diagnostic.release() }
    } }
    @Test fun runtimeAlgorithmsShareTexturesPoseStrengthAndInvalidateMeasurement()=fixture { prefs,files -> withGl {
        val renderer=SpatialRenderer(context,prefs,diagnosticSession=true)
        val saved=prefs.all.toMap(); val bytes=files.map { it.readBytes() }
        renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,w,h)
        renderer.forcedView=SpatialPose.Input(0.8f,0.51f)
        try {
            renderer.onDrawFrame(null);val base=renderer.diagnostics
            for(algorithm in listOf(SpatialAlgorithm.V1,SpatialAlgorithm.V2,SpatialAlgorithm.V3)) {
                renderer.diagnosticAlgorithm=algorithm;renderer.onDrawFrame(null)
                val d=renderer.diagnostics
                assertTrue(d.message,d.ready);assertEquals(algorithm.name,d.algorithm)
                assertEquals(base.imageTexture,d.imageTexture);assertEquals(base.depthTexture,d.depthTexture)
                assertEquals(base.viewX,d.viewX,0f);assertEquals(base.viewY,d.viewY,0f)
                assertEquals(base.motionX,d.motionX,0f);assertEquals(base.motionY,d.motionY,0f)
                assertEquals(base.strength,d.strength,0f);assertEquals(base.cameraX,d.cameraX,0f)
                assertEquals(base.normalizationMin,d.normalizationMin);assertEquals(base.normalizationMax,d.normalizationMax)
                assertEquals(-1,d.fallbackPixels)
                renderer.measureFallbackRequested=true;renderer.onDrawFrame(null)
                assertEquals(algorithm.name,renderer.diagnostics.fallbackSampleAlgorithm)
                assertEquals(0,renderer.diagnostics.fallbackGlError)
            }
            assertEquals(saved,prefs.all)
            files.forEachIndexed { index,file -> assertArrayEquals(bytes[index],file.readBytes()) }
        } finally { renderer.release() }
        val production=SpatialRenderer(context,prefs)
        try {
            production.onSurfaceCreated(null,null);production.onSurfaceChanged(null,w,h)
            production.diagnosticAlgorithm=SpatialAlgorithm.V1;production.onDrawFrame(null)
            assertEquals("V3",production.diagnostics.algorithm)
        } finally { production.release() }
    } }
    @Test fun exportContainsNormalColorMasksIdenticalUniformMetadataAndMeasuredTimings()=fixture { prefs,_ -> withGl {
        val renderer=SpatialRenderer(context,prefs,diagnosticSession=true)
        renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,w,h)
        renderer.forcedView=SpatialPose.Input(0.8f,0.51f)
        try {
            renderer.onDrawFrame(null);val normal=frame()
            renderer.captureComparisonRequested=true;renderer.onDrawFrame(null)
            val file=renderer.comparisonZip
            assertNotNull(renderer.comparisonMessage,file)
            java.util.zip.ZipFile(file!!).use { zip ->
                for(algorithm in SpatialAlgorithm.entries) {
                    val entry=zip.getEntry("${algorithm.name}-normal.png");assertNotNull(entry)
                    val b=android.graphics.BitmapFactory.decodeStream(zip.getInputStream(entry))
                    try {
                        assertEquals(w,b.width);assertEquals(h,b.height)
                        val colors=mutableSetOf<Int>()
                        for(y in 0 until h step 7) for(x in 0 until w step 7) colors.add(b.getPixel(x,y))
                        assertTrue("Normal export must contain source color, not just a binary diagnostic",colors.size>32)
                    } finally { b.recycle() }
                    assertNotNull(zip.getEntry("${algorithm.name}-failure-mask.png"))
                }
                val meta=org.json.JSONObject(zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().readText())
                assertEquals(0.8,meta.getDouble("view_x"),0.0001)
                assertEquals(0.51,meta.getDouble("view_y"),0.0001)
                assertEquals(1.0,meta.getDouble("strength"),0.0)
                assertEquals(64,meta.getString("source_0_sha256").length)
                val timing=zip.getInputStream(zip.getEntry("performance.csv")).bufferedReader().readText()
                assertEquals(4,timing.trim().lines().size)
                assertTrue(timing.contains("sync_median_ms"))
            }
            assertArrayEquals("Capture must restore normal preview",normal,frame())
            file.copyTo(File(context.cacheDir,"v3-comparison.zip"),overwrite=true)
        } finally { renderer.release() }
    } }

    @Test fun highResolutionNeutralNormalColorEqualsDirectPhotoAndHasNoInternalFailure()=withGl {
        val sw=1146;val sh=2048
        val prefs=context.getSharedPreferences("color-${UUID.randomUUID()}",0)
        val files=listOf("color","depth").map { File(context.cacheDir,"v3-${UUID.randomUUID()}-$it.png") }
        val pixels=IntArray(sw*sh)
        for(index in files.indices) {
            for(y in 0 until sh) for(x in 0 until sw) {
                pixels[y*sw+x]=if(index==0) Color.rgb((x*3+y*5) and 255,((x/7)*31+(y/11)*17) and 255,((x/3+y/5)*23) and 255)
                    else { val z=if((x/7+y/9)%2==0) 25 else 230;Color.rgb(z,z,z) }
            }
            val b=Bitmap.createBitmap(pixels,sw,sh,Bitmap.Config.ARGB_8888)
            try { files[index].outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { b.recycle() }
        }
        prefs.edit().putString("image",files[0].path).putString("depth",files[1].path).putFloat("strength",1f)
            .putString("parallax_mode","enhanced").putFloat("crop",0.04f).commit()
        val renderer=SpatialRenderer(context,prefs,diagnosticSession=true)
        var reference=0
        try {
            renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,w,h)
            renderer.useOriginalDepth=true;renderer.forcedView=SpatialPose.Input()
            GL.glDisable(GL.GL_DITHER);renderer.onDrawFrame(null)
            assertTrue(renderer.diagnostics.message,renderer.diagnostics.ready)
            val actual=frame()
            reference=app.floatdeck.gl.ShaderProgram.compile(app.floatdeck.gl.Shaders.spatialVertex,
                """#version 300 es
                precision highp float;
                uniform highp sampler2D uImage;
                uniform vec2 uCover;
                in vec2 vUV;
                out vec4 fragColor;
                void main() { fragColor=vec4(texture(uImage,(vUV-0.5)*uCover+0.5).rgb,1.0); }
                """.trimIndent())
            GL.glUseProgram(reference);GL.glDisable(GL.GL_BLEND)
            GL.glUniform1i(GL.glGetUniformLocation(reference,"uImage"),0)
            val cover=SpatialMath.cover(sw.toFloat()/sh,w.toFloat()/h,0.04f)
            GL.glUniform2f(GL.glGetUniformLocation(reference,"uCover"),cover[0],cover[1])
            GL.glDrawArrays(GL.GL_TRIANGLES,0,3);val expected=frame()
            var maxError=0
            for(i in actual.indices) maxError=maxOf(maxError,kotlin.math.abs((actual[i].toInt() and 255)-(expected[i].toInt() and 255)))
            assertTrue("1146x2048 normal-color identity error=$maxError",maxError<=1)
            renderer.measureFallbackRequested=true;renderer.onDrawFrame(null)
            assertEquals("Neutral high-resolution input must not generate internal holes",0,renderer.diagnostics.fallbackPixels)
            fun save(name:String,bytes:ByteArray) {
                val argb=IntArray(w*h)
                for(y in 0 until h) for(x in 0 until w) {
                    val n=(y*w+x)*4
                    argb[(h-1-y)*w+x]=Color.rgb(bytes[n].toInt() and 255,bytes[n+1].toInt() and 255,bytes[n+2].toInt() and 255)
                }
                val b=Bitmap.createBitmap(argb,w,h,Bitmap.Config.ARGB_8888)
                try { File(context.cacheDir,name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { b.recycle() }
            }
            save("v3-neutral-actual.png",actual);save("v3-neutral-reference.png",expected)
        } finally { if(reference!=0) GL.glDeleteProgram(reference);renderer.release();files.forEach { it.delete() };prefs.edit().clear().commit() }
    }

}
