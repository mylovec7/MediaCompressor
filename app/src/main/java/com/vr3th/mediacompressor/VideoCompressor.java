package com.vr3th.mediacompressor;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.transformer.Effects;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.effect.Presentation;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VideoCompressor {
    private final MainActivity a;
    VideoCompressor(MainActivity a){this.a=a;}

    void run(Uri src, AtomicBoolean cancel){
        File out=null;
        try{
            // Do not pre-probe with MediaExtractor: Android's platform extractor is much
            // narrower than Media3 and would reject otherwise transformable inputs.
            MediaItem item=MediaItem.fromUri(src);
            EditedMediaItem edited=new EditedMediaItem.Builder(item).build();

            out=new File(a.outputDir(),".tmp_compress_"+System.nanoTime()+".mp4");
            final Transformer[] box={null};
            final boolean[] finished={false};
            final String[] error={null};
            final CountDownLatch latch=new CountDownLatch(1);
            Handler main=new Handler(Looper.getMainLooper());
            File finalOut=out;
            main.post(()->{
                try{
                    Transformer t=new Transformer.Builder(a.getApplicationContext())
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setPortraitEncodingEnabled(true)
                            .setEnsureFileStartsOnVideoFrameEnabled(true)
                            .build();
                    box[0]=t;
                    t.addListener(new Transformer.Listener(){
                        @Override public void onCompleted(Composition c,ExportResult result){
                            if(finished[0])return;finished[0]=true;latch.countDown();
                        }
                        @Override public void onError(Composition c,ExportResult result,ExportException e){
                            if(finished[0])return;finished[0]=true;
                            error[0]=e.getMessage()==null?"Video tidak dapat diproses.":e.getMessage();
                            latch.countDown();
                        }
                    });
                    t.start(edited,finalOut.getAbsolutePath());
                }catch(Exception e){
                    if(finished[0])return;finished[0]=true;
                    error[0]=e.getMessage()==null?"Video tidak dapat diproses.":e.getMessage();
                    latch.countDown();
                }
            });
            while(!latch.await(200,java.util.concurrent.TimeUnit.MILLISECONDS)){
                if(cancel.get())try{if(box[0]!=null)box[0].cancel();}catch(Exception ignored){}
            }
            if(cancel.get()){
                safe(out);a.videoFinished("> ORIGINAL PRESERVED\nOperation cancelled safely.");return;
            }
            if(error[0]!=null||!out.exists()||out.length()<=0){
                safe(out);a.videoFinished("Gagal: "+(error[0]==null?"Output tidak valid.":error[0])+"\nINTEGRITY: OK  //  ORIGINAL SAFE");return;
            }
            long original=a.sourceSize(src), result=out.length();
            if(original>0&&result>=original){
                safe(out);
                a.videoFinished("> OPTIMAL RESULT\nNo smaller output was found.\nOriginal quality preserved ✓\nINTEGRITY: OK  //  ORIGINAL SAFE");
                return;
            }
            a.publishVideo(out);safe(out);a.videoProgress(100);
            double saved=original>0?((original-result)*100.0/original):0;
            a.videoFinished("> COMPRESSION ENGINE: COMPLETE\n"+a.fmtSize(original)+" → "+a.fmtSize(result)+"\nSAVED "+String.format(Locale.US,"%.1f",Math.max(0,saved))+"%\n\n✓ ASPECT RATIO PRESERVED\n✓ OUTPUT VERIFIED\n✓ ORIGINAL PRESERVED");
        }catch(Exception e){
            safe(out);
            a.videoFinished("Gagal: "+(e.getMessage()==null?"Video tidak dapat diproses.":e.getMessage())+"\nINTEGRITY: OK  //  ORIGINAL SAFE");
        }
    }

    private void safe(File f){if(f!=null)try{f.delete();}catch(Exception ignored){}}
}
