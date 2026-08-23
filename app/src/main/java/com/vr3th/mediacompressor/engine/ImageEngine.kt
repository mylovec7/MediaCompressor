package com.vr3th.mediacompressor.engine
import android.content.Context;import android.graphics.*;import android.net.Uri;import android.os.Build
import com.vr3th.mediacompressor.data.ProcessResult;import com.vr3th.mediacompressor.utils.StorageUtils;import java.io.FileOutputStream
class ImageEngine(private val context:Context){
 fun compressImage(uri:Uri,originalName:String,targetQuality:Int=82,maxWidth:Int=1920,maxHeight:Int=1920):ProcessResult{
  val start=System.currentTimeMillis();var size=0L;context.contentResolver.openFileDescriptor(uri,"r")?.use{size=it.statSize}
  try{val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,o)}
   var sample=1;while(o.outWidth/sample>maxWidth*2||o.outHeight/sample>maxHeight*2)sample*=2
   val b=context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})}?:throw IllegalStateException("Failed to decode image bitmap.")
   val scale=minOf(maxWidth.toFloat()/b.width,maxHeight.toFloat()/b.height,1f);val final=if(scale<1)Bitmap.createScaledBitmap(b,(b.width*scale).toInt(),(b.height*scale).toInt(),true)else b
   val alpha=o.outMimeType?.contains("png",true)==true;val webp=Build.VERSION.SDK_INT>=Build.VERSION_CODES.R;val fmt=if(alpha&&webp)Bitmap.CompressFormat.WEBP_LOSSY else if(alpha)Bitmap.CompressFormat.PNG else if(webp)Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
   val ext=if(fmt==Bitmap.CompressFormat.PNG)".png" else if(fmt==Bitmap.CompressFormat.JPEG)".jpg" else ".webp";val out=StorageUtils.createTempFile(context,"img_",ext);FileOutputStream(out).use{final.compress(fmt,targetQuality,it)}
   val w=final.width;val h=final.height;if(final!==b)b.recycle();final.recycle()
   return ProcessResult(true,originalName,size,out.length(),"${o.outWidth}x${o.outHeight}","${w}x${h}",0,0,o.outMimeType?:"image","${fmt.name}",System.currentTimeMillis()-start,out.absolutePath)
  }catch(x:Exception){return ProcessResult(false,originalName,size,0,"-","-",0,0,"-","-",0,"",errorMessage=x.localizedMessage?:"Image compression failed.")}
 }
}
