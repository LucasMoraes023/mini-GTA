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
import java.nio.ByteBuffer

class CaptureService: Service() {
    private var projection: MediaProjection?=null; private var reader: ImageReader?=null; private var lastTrigger=0L
    override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int { createChannel(); startForeground(1,notification()); val code=i?.getIntExtra("resultCode",0)?:0; val data=i?.getParcelableExtra<Intent>("data")?:return START_NOT_STICKY; val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; projection=mgr.getMediaProjection(code,data); val dm=getSystemService(DISPLAY_SERVICE) as DisplayManager; val d=dm.getDisplay(android.view.Display.DEFAULT_DISPLAY); val m=resources.displayMetrics; reader=ImageReader.newInstance(m.widthPixels,m.heightPixels,PixelFormat.RGBA_8888,2); projection?.createVirtualDisplay("FishingPilot",m.widthPixels,m.heightPixels,m.densityDpi,0,reader?.surface,null,null); reader?.setOnImageAvailableListener({ r -> process(r.acquireLatestImage()) },Handler(Looper.getMainLooper())); return START_STICKY }
    private fun process(image:Image?){ image?:return; try { val p=image.planes[0]; val b=p.buffer; val w=image.width; val h=image.height; val stride=p.pixelStride; val row=p.rowStride; val data=ByteArray(b.remaining()); b.get(data); val green=findGreen(data,w,h,stride,row); if(green!=null){ val now=SystemClock.elapsedRealtime(); if(now-lastTrigger>250){ lastTrigger=now; /* detector event: UI action is intentionally left configurable */ } } } finally { image.close() } }
    private fun findGreen(data:ByteArray,w:Int,h:Int,pixel:Int,row:Int):Point? { var best:Point?=null; var count=0; for(y in 0 until h step 3){ var x=0; while(x<w){ val o=y*row+x*pixel; if(o+2<data.size){ val r=data[o].toInt() and 255; val g=data[o+1].toInt() and 255; val bb=data[o+2].toInt() and 255; if(g>130 && g>r*1.35 && g>bb*1.25){ count++; if(count==1) best=Point(x,y) } }; x+=3 } }; return best }
    private fun createChannel(){ if(Build.VERSION.SDK_INT>=26){ val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager; nm.createNotificationChannel(NotificationChannel("capture","Fishing Pilot",NotificationManager.IMPORTANCE_LOW)) } }
    private fun notification():Notification { return if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,"capture").setContentTitle("Fishing Pilot").setContentText("Monitorando a tela").setSmallIcon(android.R.drawable.ic_menu_search).build() else Notification.Builder(this).setContentTitle("Fishing Pilot").setContentText("Monitorando a tela").setSmallIcon(android.R.drawable.ic_menu_search).build() }
    override fun onBind(intent:Intent?)=null
}
