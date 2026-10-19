package app.floatdeck.spatial

import kotlin.math.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpatialV13Test {
    private fun rotation(x:Double,y:Double):FloatArray {
        val a=hypot(x,y); if(a==0.0) return floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
        val u=x/a; val v=y/a; val c=cos(a); val s=sin(a); val t=1-c
        return doubleArrayOf(c+u*u*t,u*v*t,v*s,u*v*t,c+v*v*t,-u*s,-v*s,u*s,c).map { it.toFloat() }.toFloatArray()
    }
    @Test fun `clockwise counterclockwise ellipse arbitrary entry stop and reversal retain direction`() {
        for(sign in listOf(-1,1)) for(ellipse in listOf(1.0,0.55)) {
            val pose=SpatialPose(); pose.update(rotation(0.0,0.0),0)
            var previous:SpatialPose.Input?=null
            for(i in 0..720) {
                val theta=0.37+sign*i*PI/360
                val expectedX=cos(theta)*0.6; val expectedY=sin(theta)*0.6*ellipse
                val result=pose.update(rotation(expectedY*PI/12,expectedX*PI/12),0)
                assertEquals(expectedX.toFloat(),result.x,0.0002f)
                assertEquals(expectedY.toFloat(),result.y,0.0002f)
                val filtered=SpatialMath.responseVector(result.x,result.y,1.8f,12f)
                assertEquals(0f,filtered.x*result.y-filtered.y*result.x,0.00001f)
                previous?.let { assertTrue(hypot(result.x-it.x,result.y-it.y)<0.01f) }
                previous=result
            }
            val stopped=pose.update(rotation(0.04,0.08),0)
            repeat(20) { assertEquals(stopped,pose.update(rotation(0.04,0.08),0)) }
            val reversed=pose.update(rotation(-0.04,-0.08),0)
            assertEquals(-stopped.x,reversed.x,0.00001f); assertEquals(-stopped.y,reversed.y,0.00001f)
            pose.recenter(); assertEquals(0f,pose.update(rotation(-0.04,-0.08),0).x,0.00001f)
        }
    }
    @Test fun `radial deadzone suppresses tiny noise but responds to natural one degree tilts`() {
        assertEquals(SpatialMath.Vector(0f,0f),SpatialMath.responseVector(0.001f,0.001f,3f,6f))
        assertTrue(SpatialMath.responseVector(1f/15,0f,1f,8f).x>0.1f)
        val diagonal=SpatialMath.responseVector(1f,1f,3f,6f)
        assertEquals(1f,hypot(diagonal.x,diagonal.y),0.0001f)
    }
    @Test fun `response is time based with default ninety percent settling under 100 ms`() {
        val a=SpatialMath.smoothing(0.016f,0.65f)
        assertTrue(1-(1-a).pow(6)>0.9f)
        for(speed in listOf(0f,0.65f,1f)) assertEquals(SpatialMath.smoothing(0.032f,speed),1-(1-SpatialMath.smoothing(0.016f,speed)).pow(2),0.000001f)
    }
    private fun profile(nearEdge:Boolean=false):SpatialBoundaryProfile {
        val pixels=IntArray(100*180) { i -> val x=i%100;val y=i/100
            if((x in 40..60 && y in 70..110)||(nearEdge&&x<20)) 0xffffffff.toInt() else 0xff000000.toInt()
        }
        return SpatialBoundaryProfile.create(pixels,100,180,0,255)
    }
    @Test fun `real far border preserves full doubled amplitude in all modes without extra crop`() {
        val c=SpatialMath.cover(720f/1280,720f/1280,0.04f)
        for(mode in listOf("stable","standard","enhanced")) for(strength in listOf(0f,0.5f,1f)) {
            val a=SpatialMath.amplitude(strength,mode)
            for(i in 0..360) {
                val t=i*PI/180; val x=cos(t).toFloat(); val y=sin(t).toFloat()
                val p=SpatialBoundaryPlanner.plan(c,profile(),720,1280,720,1280,a,x,y)
                assertEquals(a*x,p.appliedX,0.00001f);assertEquals(a*y,p.appliedY,0.00001f)
                assertEquals(1f,p.extraZoom)
            }
        }
    }
    @Test fun `near border stays protected and diagonal limiting preserves angle`() {
        val c=SpatialMath.cover(720f/1280,720f/1280,0.04f)
        val p=SpatialBoundaryPlanner.plan(c,profile(true),720,1280,720,1280,0.12f,0.8f,0.6f)
        assertTrue(p.appliedX<p.requestedX); assertEquals(1f,p.extraZoom)
        assertEquals(0.8f/0.6f,p.appliedX/p.appliedY,0.0001f)
        assertTrue(hypot(p.appliedX/p.limitX,p.appliedY/p.limitY)<=1.00001f)
        assertTrue(p.reason.contains("限幅"))
    }
    @Test fun `unknown boundary fails conservative and portrait landscape preserve source sampling margins`() {
        for(size in listOf(720 to 1280,1280 to 720,1080 to 2400)) {
            val c=SpatialMath.cover(720f/1280,size.first.toFloat()/size.second,0.04f)
            for(x in listOf(-1f,0f,1f)) for(y in listOf(-1f,0f,1f)) {
                val p=SpatialBoundaryPlanner.plan(c,null,size.first,size.second,720,1280,0.12f,x,y)
                assertTrue(abs(p.appliedX)*1.3f*p.cover[0] <= (1-p.cover[0])/2-1f/720+0.00001f)
                assertTrue(abs(p.appliedY)*size.first/size.second*1.3f*p.cover[1] <= (1-p.cover[1])/2-1f/1280+0.00001f)
            }
        }
    }
}
