package com.vr3th.mediacompressor.engine
import android.content.Context
import android.media.*;import android.net.Uri
import com.vr3th.mediacompressor.data.ProcessResult;import com.vr3th.mediacompressor.utils.StorageUtils
import java.nio.ByteBuffer
class AudioEngine(private val context:Context){
 fun extractAudioFromVideo(uri:Uri,originalName:String):ProcessResult{
  val start=System.currentTimeMillis();val out=StorageUtils.createTempFile(context,"audio_",".m4a");val e=MediaExtractor();var m:MediaMuxer?=null
  var orig=0L;context.contentResolver.openFileDescriptor(uri,"r")?.use{orig=it.statSize}
  try{e.setDataSource(context,uri,null);var idx=-1;var fmt:MediaFormat?=null;for(i in 0 until e.trackCount){val f=e.getTrackFormat(i);if((f.getString(MediaFormat.KEY_MIME)?:"").startsWith("audio/")){idx=i;fmt=f;break}}
   if(idx<0||fmt==null)return ProcessResult(false,originalName,orig,0,"-","-",0,0,"-","-",0,"",errorMessage="No audio track found in media.")
   e.selectTrack(idx);m=MediaMuxer(out.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);val mt=m.addTrack(fmt);m.start();val b=ByteBuffer.allocateDirect(512*1024);val bi=MediaCodec.BufferInfo()
   while(true){val ti=e.sampleTrackIndex;if(ti!=idx){if(ti<0)break;e.advance();continue};bi.size=e.readSampleData(b,0);if(bi.size>0){bi.presentationTimeUs=e.sampleTime;bi.flags=e.sampleFlags;bi.offset=0;m.writeSampleData(mt,b,bi)};e.advance()}
   return ProcessResult(true,originalName,orig,out.length(),"-","-",0,0,fmt.getString(MediaFormat.KEY_MIME)?:"Audio","Audio Stream Copy / M4A",System.currentTimeMillis()-start,out.absolutePath)
  }catch(x:Exception){return ProcessResult(false,originalName,orig,0,"-","-",0,0,"-","-",0,"",errorMessage=x.localizedMessage?:"Audio extraction failed.")}finally{try{e.release()}catch(_:Exception){};try{m?.stop();m?.release()}catch(_:Exception){}}
 }
}
