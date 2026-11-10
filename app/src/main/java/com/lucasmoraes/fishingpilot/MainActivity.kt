package com.lucasmoraes.fishingpilot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val requestCode = 7001
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,48,32,32); setBackgroundColor(0xFF0B0F12.toInt()) }
        val title=TextView(this).apply { text="FISHING PILOT"; textSize=28f; setTextColor(0xFFFFFFFF.toInt()) }
        val sub=TextView(this).apply { text="Detector local • baixa latência"; textSize=14f; setTextColor(0xFF9AA5AD.toInt()); setPadding(0,8,0,32) }
        status=TextView(this).apply { text="Pronto para iniciar"; textSize=16f; setTextColor(0xFF26D07C.toInt()); setPadding(0,0,0,24) }
        val start=Button(this).apply { text="ATIVAR CAPTURA"; setOnClickListener { requestProjection() } }
        val stop=Button(this).apply { text="PARAR"; setOnClickListener { stopService(Intent(this@MainActivity,CaptureService::class.java)); status.text="Parado" } }
        val info=TextView(this).apply { text="O app captura a tela, procura a faixa de pesca e acompanha o alvo verde a cada rodada. O ponto verde é recalculado em cada ciclo."; setTextColor(0xFFB8C1C8.toInt()); textSize=14f; setPadding(0,24,0,0) }
        root.addView(title); root.addView(sub); root.addView(status); root.addView(start); root.addView(stop); root.addView(info); setContentView(root)
    }
    private fun requestProjection() { val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; startActivityForResult(mgr.createScreenCaptureIntent(),requestCode) }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==this.requestCode && resultCode==RESULT_OK && data!=null){ status.text="Captura ativa"; startService(Intent(this,CaptureService::class.java).putExtra("resultCode",resultCode).putExtra("data",data)) } }
}
