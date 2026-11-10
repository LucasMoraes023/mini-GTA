package com.lucasmoraes.fishingpilot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Detector específico da barra de pesca: verde + trilho horizontal + preenchimento móvel. */
class RobustFishingDetector {
    data class Result(val fire: Boolean, val active: Boolean, val message: String)
    private data class Bar(val left:Int,val right:Int,val top:Int,val bottom:Int)
    private var bar: Bar? = null
    private var lastGreenLeft = Int.MIN_VALUE
    private var lastEdge = Int.MIN_VALUE
    private var lastT = 0L
    private var speed = 0f
    private var moving = 0
    private var fired = false
    private var lastFire = Long.MIN_VALUE / 2
    private var lost = 0

    fun reset(){ bar=null; lastGreenLeft=Int.MIN_VALUE; lastEdge=Int.MIN_VALUE; lastT=0; speed=0f; moving=0; fired=false; lost=0 }

    fun process(data:ByteArray,w:Int,h:Int,px:Int,row:Int,now:Long):Result {
        val b = bar ?: findBar(data,w,h,px,row).also { bar=it }
        if (b == null) { lost++; if(lost>=8) reset(); return Result(false,false,"procurando a barra de pesca") }
        val reading = read(data,w,h,px,row,b)
        if(reading==null){ lost++; if(lost>=8) reset(); return Result(false,true,"barra detectada • aguardando leitura") }
        lost=0
        val gl=reading.first; val gr=reading.second; val edge=reading.third
        if(lastGreenLeft!=Int.MIN_VALUE && abs(gl-lastGreenLeft)>max(12,w/100)) newRound()
        if(lastEdge!=Int.MIN_VALUE && edge < lastEdge-max(20,w/60)) newRound()
        lastGreenLeft=gl
        if(lastEdge!=Int.MIN_VALUE && now>lastT){
            val dx=edge-lastEdge
            if(dx>0){ val inst=dx.toFloat()/(now-lastT); speed=if(speed==0f)inst else speed*.65f+inst*.35f; moving++ }
        }
        lastEdge=edge; lastT=now
        val gw=max(1,gr-gl)
        val predicted=edge+speed*90f
        val inside=edge>=gl-2
        val reached=predicted>=gl+gw*.15f
        val passed=edge>gr+gw*.15f
        val fire=!fired && !passed && moving>=3 && (inside||reached) && now-lastFire>=600
        if(fire){fired=true;lastFire=now}
        val msg=when{fire->"ALT! borda=$edge verde=$gl-$gr";fired->"ALT enviado • aguardando próxima rodada";passed->"passou da faixa verde";else->"seguindo: borda=$edge verde=$gl-$gr"}
        return Result(fire,true,msg)
    }

    private fun newRound(){ fired=false; lastEdge=Int.MIN_VALUE; lastT=0; speed=0f; moving=0 }

    private fun findBar(d:ByteArray,w:Int,h:Int,px:Int,row:Int):Bar?{
        val ys0=0; val ys1=h-1; val minGreen=max(8,w/200)
        for(y in ys0..ys1 step max(1,h/800)){
            var x=0
            while(x<w){
                if(!green(d,w,h,px,row,x,y)){x+=2;continue}
                val gl=x; while(x<w && green(d,w,h,px,row,x,y)) x+=2; val gr=min(w-1,x-1)
                if(gr-gl+1<minGreen) continue
                var left=gl-1; var gap=0
                while(left>=0){ if(barPixel(d,w,h,px,row,left,y)){gap=0}else{gap++;if(gap>3)break};left-- }
                var right=gr+1; gap=0
                while(right<w){ if(barPixel(d,w,h,px,row,right,y)){gap=0}else{gap++;if(gap>3)break};right++ }
                val l=left+1; val r=right-1
                if(r-l+1<max(100,(w*.18f).toInt())) continue
                val half=max(12,h/24)
                return Bar(l,r,max(0,y-half),min(h-1,y+half))
            }
        }
        return null
    }

    private fun read(d:ByteArray,w:Int,h:Int,px:Int,row:Int,b:Bar):Triple<Int,Int,Int>?{
        val ys=intArrayOf(b.top+(b.bottom-b.top)/4,b.top+(b.bottom-b.top)/2,b.top+(b.bottom-b.top)*3/4)
        val greens=ys.asList().mapNotNull{ findGreenRun(d,w,h,px,row,b.left,b.right,it) }
        if(greens.size<2)return null
        val gl=greens.map{it.first}.sorted()[greens.size/2]; val gr=greens.map{it.second}.sorted()[greens.size/2]
        if(gr-gl<max(8,w/150))return null
        val edges=ArrayList<Int>()
        for(y in ys){ var e=b.left-1; var x=b.left; while(x<gl){if(fill(d,w,h,px,row,x,y))e=x;x++}; if(e>=b.left)edges.add(e) }
        if(edges.size<2)return null
        edges.sort(); return Triple(gl,gr,edges[edges.size/2])
    }

    private fun findGreenRun(d:ByteArray,w:Int,h:Int,px:Int,row:Int,l:Int,r:Int,y:Int):Pair<Int,Int>?{
        var x=l
        while(x<=r){if(!green(d,w,h,px,row,x,y)){x++;continue};val s=x;while(x<=r&&green(d,w,h,px,row,x,y))x++;val e=x-1;if(e-s+1>=max(8,w/150))return s to e};return null
    }

    private fun rgb(d:ByteArray,w:Int,h:Int,px:Int,row:Int,x:Int,y:Int):Int{if(x<0||y<0||x>=w||y>=h)return 0;val o=y*row+x*px;if(o+2>=d.size)return 0;return ((d[o].toInt() and 255) shl 16) or ((d[o+1].toInt() and 255) shl 8) or (d[o+2].toInt() and 255)}
    private fun green(d:ByteArray,w:Int,h:Int,px:Int,row:Int,x:Int,y:Int):Boolean{val c=rgb(d,w,h,px,row,x,y);val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255;return g>=95&&g-r>=45&&g-b>=45&&r<=150&&b<=150}
    private fun fill(d:ByteArray,w:Int,h:Int,px:Int,row:Int,x:Int,y:Int):Boolean{val c=rgb(d,w,h,px,row,x,y);val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255;val hi=max(r,max(g,b));val lo=min(r,min(g,b));return hi>=135&&hi-lo<=45}
    private fun track(d:ByteArray,w:Int,h:Int,px:Int,row:Int,x:Int,y:Int):Boolean{val c=rgb(d,w,h,px,row,x,y);val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255;val hi=max(r,max(g,b));val lo=min(r,min(g,b));return hi<=45&&hi-lo<=18}
    private fun barPixel(d:ByteArray,w:Int,h:Int,px:Int,row:Int,x:Int,y:Int)=green(d,w,h,px,row,x,y)||fill(d,w,h,px,row,x,y)||track(d,w,h,px,row,x,y)
}
