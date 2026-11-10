package com.lucasmoraes.fishingpilot

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.content.pm.ServiceInfo

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var lastTrigger = 0L

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val n=notification()
        if(Build.VERSION.SDK_INT>=29) startForeground(1,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1,n)
        val code=i?.getIntExtra("resultCode",0) ?: return START_NOT_STICKY
        val data=if(Build.VERSION.SDK_INT>=33) i.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra("data")
        if(data==null) return START_NOT_STICKY
        val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection?.stop(); projection=mgr.getMediaProjection(code,data)
        val m=resources.displayMetrics
        reader?.close(); reader=ImageReader.newInstance(m.widthPixels,m.heightPixels,PixelFormat.RGBA_8888,2)
        projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopSelf()}},Handler(Looper.getMainLooper()))
        projection?.createVirtualDisplay("FishingPilot",m.widthPixels,m.heightPixels,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,null)
        reader?.setOnImageAvailableListener({r->process(r.acquireLatestImage())},Handler(Looper.getMainLooper()))
        return START_STICKY
    }

    private data class Target(val left:Float,val right:Float,val top:Float,val bottom:Float)

    private fun process(image:Image?) {
        image?:return
        try {
            val p=image.planes[0]; val b=p.buffer; val w=image.width; val h=image.height; val px=p.pixelStride; val row=p.rowStride
            val data=ByteArray(b.remaining()); b.get(data)
            val t=findGreenTarget(data,w,h,px,row) ?: return
            val marker=findBrightMarker(data,w,h,px,row,t) ?: return
            if(marker >= t.left-8f && marker <= t.right+8f) {
                val now=SystemClock.elapsedRealtime()
                if(now-lastTrigger>=650L){lastTrigger=now; triggerAlt()}
            }
        } finally { image.close() }
    }

    private fun findGreenTarget(data:ByteArray,w:Int,h:Int,px:Int,row:Int):Target? {
        var minX=w;var maxX=-1;var minY=h;var maxY=-1;var count=0
        for(y in 0 until h step 4) for(x in 0 until w step 4) {
            val o=y*row+x*px
            if(o+2>=data.size) continue
            val r=data[o].toInt() and 255; val g=data[o+1].toInt() and 255; val bl=data[o+2].toInt() and 255
            if(g>135 && g>r*1.35 && g>bl*1.25){minX=minOf(minX,x);maxX=maxOf(maxX,x);minY=minOf(minY,y);maxY=maxOf(maxY,y);count++}
        }
        return if(count>=8 && maxX-minX>=8 && maxY-minY>=2) Target(minX.toFloat(),maxX.toFloat(),minY.toFloat(),maxY.toFloat()) else null
    }

    private fun findBrightMarker(data:ByteArray,w:Int,h:Int,px:Int,row:Int,t:Target):Float? {
        val y0=t.top.toInt().coerceAtLeast(0);val y1=t.bottom.toInt().coerceAtMost(h-1)
        var best=-1;var score=0
        for(x in t.left.toInt()..t.right.toInt() step 3){
            var s=0;var y=y0
            while(y<=y1){val o=y*row+x*px;if(o+2<data.size){val r=data[o].toInt() and 255;val g=data[o+1].toInt() and 255;val b=data[o+2].toInt() and 255;val hi=maxOf(r,g,b);val spread=maxOf(r,g,b)-minOf(r,g,b);if(hi>205&&spread<45)s++};y+=3}
            if(s>score){score=s;best=x}
        }
        return if(best>=0&&score>=2) best.toFloat() else null
    }

    private fun triggerAlt() {
        val p=getSharedPreferences("pilot",MODE_PRIVATE)
        val x=p.getFloat("alt_x",-1f);val y=p.getFloat("alt_y",-1f)
        if(x>=0f&&y>=0f&&FishingAccessibilityService.tap(x,y)) return
        try { Runtime.getRuntime().exec(arrayOf("sh","-c","input keyevent 57")) } catch(_:Throwable){}
    }

    private fun createChannel(){if(Build.VERSION.SDK_INT>=26){val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager;nm.createNotificationChannel(NotificationChannel("capture","Fishing Pilot",NotificationManager.IMPORTANCE_LOW))}}
    private fun notification():Notification=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,"capture").setContentTitle("Fishing Pilot").setContentText("Monitorando pesca e acionando ALT").setSmallIcon(android.R.drawable.ic_menu_search).build() else Notification.Builder(this).setContentTitle("Fishing Pilot").setContentText("Monitorando pesca e acionando ALT").setSmallIcon(android.R.drawable.ic_menu_search).build()

    override fun onDestroy(){reader?.close();reader=null;projection?.stop();projection=null;super.onDestroy()}
    override fun onBind(intent:Intent?)=null
}