package com.lucasmoraes.fishingpilot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.ArrayDeque

/** Detecta o painel escuro e analisa somente o minigame dentro dele. */
class RobustFishingDetector {
    data class Result(val fire:Boolean,val active:Boolean,val message:String)
    private data class Rect(val l:Int,val t:Int,val r:Int,val b:Int)
    private data class Target(val l:Int,val r:Int,val t:Int,val b:Int)
    private var previous=ByteArray(0)
    private var lastMarker=Float.NaN
    private var moving=0
    private var inside=0
    private var fired=false
    private var lost=0
    private var lastFire=0L

    fun reset(){ previous=ByteArray(0); resetRound(); lost=0 }
    private fun resetRound(){ lastMarker=Float.NaN; moving=0; inside=0; fired=false }

    fun process(d:ByteArray,w:Int,h:Int,px:Int,row:Int,now:Long):Result {
        if(w<=0||h<=0||px<3||row<w*px) return Result(false,false,"captura inválida")
        val panel=findPanel(d,w,h,px,row)
        if(panel==null){ lost++; if(lost>=6)resetRound(); previous=d.copyOf(); return Result(false,false,"captura OK • procurando caixa da pesca") }
        val target=findTarget(d,w,h,px,row,panel)
        if(target==null){ lost++; if(lost>=6)resetRound(); previous=d.copyOf(); return Result(false,true,"caixa detectada • procurando alvo verde") }
        lost=0
        val marker=findMarker(d,w,h,px,row,panel,target)
        if(marker==null){ inside=0; previous=d.copyOf(); return Result(false,true,"alvo verde detectado • rastreando marcador branco") }
        if(!lastMarker.isNaN()){ if(abs(marker-lastMarker)>=1f)moving++ else moving=0 }
        lastMarker=marker
        inside=if(marker>=target.l-2&&marker<=target.r+2)inside+1 else 0
        val fire=!fired&&moving>=2&&inside>=1&&now-lastFire>=600L
        if(fire){fired=true;lastFire=now}
        previous=d.copyOf()
        val msg=when{fire->"ALVO ALCANÇADO • solicitando ALT";fired->"ALT solicitado • aguardando próxima rodada";inside>0->"marcador no verde • confirmando";else->"pesca ativa • marcador=$marker alvo=${target.l}-${target.r}"}
        return Result(fire,true,msg)
    }

    private fun findPanel(d:ByteArray,w:Int,h:Int,px:Int,row:Int):Rect? {
        val cw=max(8,w/90); val ch=max(8,h/100)
        val cols=(w+cw-1)/cw; val rows=(h+ch-1)/ch
        val dark=BooleanArray(cols*rows)
        for(gy in 0 until rows){ val top=gy*ch; if(top<h*.42f)continue
            for(gx in 0 until cols){ val l=gx*cw; val r=min(w,l+cw); val b=min(h,top+ch); var n=0;var yes=0
                var y=top;while(y<b){var x=l;while(x<r){val o=y*row+x*px;if(o+2<d.size){val rr=d[o].toInt()and 255;val g=d[o+1].toInt()and 255;val bb=d[o+2].toInt()and 255;val hi=max(rr,max(g,bb));val lo=min(rr,min(g,bb));if(hi<=110&&hi-lo<=48)yes++;n++};x+=2};y+=2}
                if(n>=4&&yes.toFloat()/n>=.58f)dark[gy*cols+gx]=true
            }
        }
        val seen=BooleanArray(dark.size);var best:Rect?=null;var bestScore=0.0
        for(start in dark.indices){if(!dark[start]||seen[start])continue
            val q=ArrayDeque<Int>();q.add(start);seen[start]=true
            var minX=cols;var maxX=-1;var minY=rows;var maxY=-1;var count=0
            while(q.isNotEmpty()){val i=q.removeFirst();val gx=i%cols;val gy=i/cols;count++;minX=min(minX,gx);maxX=max(maxX,gx);minY=min(minY,gy);maxY=max(maxY,gy)
                for(dy in -1..1)for(dx in -1..1){if(dx==0&&dy==0)continue;val nx=gx+dx;val ny=gy+dy;if(nx !in 0 until cols||ny !in 0 until rows)continue;val ni=ny*cols+nx;if(dark[ni]&&!seen[ni]){seen[ni]=true;q.add(ni)}}
            }
            val l=minX*cw;val t=minY*ch;val r=min(w-1,(maxX+1)*cw);val b=min(h-1,(maxY+1)*ch);val bw=r-l;val bh=b-t
            if(bw<=0||bh<=0)continue
            val wr=bw.toFloat()/w;val hr=bh.toFloat()/h;val ar=bw.toFloat()/bh;val fill=count.toFloat()/((maxX-minX+1)*(maxY-minY+1))
            if(wr !in .18f.. .95f||hr !in .025f.. .32f||ar !in 1.8f..15f||fill<.30f)continue
            val score=count*fill*(1.0+wr);if(score>bestScore){bestScore=score;best=Rect(max(0,l-cw),max(0,t-ch),min(w-1,r+cw),min(h-1,b+ch))}
        }
        return best
    }

    private fun findTarget(d:ByteArray,w:Int,h:Int,px:Int,row:Int,p:Rect):Target? {
        var l=w;var r=-1;var t=h;var b=-1;var n=0
        for(y in p.t..p.b step 2)for(x in p.l..p.r step 2){val o=y*row+x*px;if(o+2>=d.size)continue
            val rr=d[o].toInt()and 255;val g=d[o+1].toInt()and 255;val bb=d[o+2].toInt()and 255
            if(g>=90&&g-rr>=28&&g-bb>=25&&g>rr*1.18f){n++;l=min(l,x);r=max(r,x);t=min(t,y);b=max(b,y)}
        }
        if(n<3||r<l||(r-l)>(p.r-p.l)*.20f||(b-t)>(p.b-p.t)*.75f)return null
        return Target(l,r,t,b)
    }

    private fun findMarker(d:ByteArray,w:Int,h:Int,px:Int,row:Int,p:Rect,target:Target):Float? {
        if(previous.isEmpty()||previous.size!=d.size)return null
        val pad=max(5,(p.b-p.t)/5);val top=max(p.t,target.t-pad);val bottom=min(p.b,target.b+pad)
        val candidates=ArrayList<Pair<Int,Int>>();var x=p.l
        while(x<=p.r){if(x<target.l-4||x>target.r+4){var score=0;var white=0;var y=top
            while(y<=bottom){val o=y*row+x*px;if(o+2<d.size&&o+2<previous.size){val rr=d[o].toInt()and 255;val g=d[o+1].toInt()and 255;val bb=d[o+2].toInt()and 255;val hi=max(rr,max(g,bb));val lo=min(rr,min(g,bb))
                if(hi>=185&&hi-lo<=65){white++;val delta=abs(rr-(previous[o].toInt()and 255))+abs(g-(previous[o+1].toInt()and 255))+abs(bb-(previous[o+2].toInt()and 255));if(delta>=45)score++}}
                y+=2}
            if(white>0&&score>0)candidates.add(x to score)
        };x+=2}
        if(candidates.isEmpty())return null
        var bs=-1;var be=-1;var best=0;var gs=candidates[0].first;var ge=gs;var sc=candidates[0].second
        for(i in 1 until candidates.size){val cx=candidates[i].first;if(cx-ge<=6){ge=cx;sc+=candidates[i].second}else{if(sc>best){best=sc;bs=gs;be=ge};gs=cx;ge=cx;sc=candidates[i].second}}
        if(sc>best){best=sc;bs=gs;be=ge};if(bs<0||best<1)return null
        return (bs+be)/2f
    }
}