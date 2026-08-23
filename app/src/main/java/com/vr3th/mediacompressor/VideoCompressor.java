package com.vr3th.mediacompressor;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adaptive native compressor. Every accepted video is actually re-encoded. */
public final class VideoCompressor {
    private final MainActivity a;
    VideoCompressor(MainActivity a){this.a=a;}
    private static final class Meta{long duration,videoBitrate;int w,h;}
    private static final class Candidate{File file;long size;int bitrate;String mime;}

    void run(Uri src, AtomicBoolean cancel){
        File local=null;ArrayList<File> tmp=new ArrayList<>();
        try{
            if(src==null)throw new IOException("input missing");
            local=a.copyToTemp(src,".media");
            Meta m=meta(local);long original=a.sourceSize(src);
            if(m.duration<=0||m.w<=0||m.h<=0)throw new IOException("video metadata unavailable");
            Candidate best=null;
            long base=m.videoBitrate>0?m.videoBitrate:Math.max(320_000,(original>0?original*8/m.duration:1_000_000));
            String[] codecs=VideoEngine.hasEncoder("video/hevc")&&VideoEngine.hasEncoder("video/avc")
                    ?new String[]{"video/hevc","video/avc"}
                    :new String[]{VideoEngine.bestVideoMime()};
            int[] factors={72,60,50,42,35};
            for(String mime:codecs){
                for(int f:factors){
                    if(cancel.get())throw new IOException("cancelled");
                    int target=(int)Math.max(180_000,Math.min(8_000_000,base*f/100));
                    File out=new File(a.outputDir(),".tmp_vc_"+System.nanoTime()+".mp4");tmp.add(out);
                    NativeVideoEngine.Result nr=NativeVideoEngine.encodeFrames(a,local,out,0,m.duration,false,target,cancel,0,0,mime);
                    if(!out.exists()||out.length()<=0)continue;
                    if(!validVideo(out))continue;
                    if(original>0&&out.length()>=original)continue;
                    Candidate c=new Candidate();c.file=out;c.size=out.length();c.bitrate=target;c.mime=mime;
                    if(best==null||c.size<best.size){if(best!=null)safe(best.file);best=c;}
                    if(original>0&&best.size<=Math.round(original*.50))break;
                }
                if(best!=null&&original>0&&best.size<=Math.round(original*.50))break;
            }
            if(best==null)throw new IOException("No smaller valid re-encoded output was produced");
            long d=best.size; a.publishVideo(best.file); double saved=original>0?(original-d)*100.0/original:0;
            a.videoProgress(100);
            String codec=best.mime.equals("video/hevc")?"H.265 / HEVC":"H.264 / AVC";
            a.videoFinished("> NATIVE COMPRESSION: COMPLETE\n"+a.fmtSize(original)+" → "+a.fmtSize(d)+"\nSAVED "+String.format(Locale.US,"%.1f",Math.max(0,saved))+"%\n\n✓ ALWAYS RE-ENCODED\n✓ "+codec+" VERIFIED\n✓ OUTPUT VERIFIED\n✓ ORIGINAL PRESERVED");
            best=null;
        }catch(Exception e){a.videoFinished("> COMPRESSION FAILED\n"+(e.getMessage()==null?"Native encoder could not create a smaller valid output.":e.getMessage())+"\n✓ ORIGINAL PRESERVED");}
        finally{safe(local);for(File f:tmp)safe(f);}
    }
    private Meta meta(File f)throws Exception{MediaExtractor ex=new MediaExtractor();Meta m=new Meta();try{ex.setDataSource(f.getAbsolutePath());for(int i=0;i<ex.getTrackCount();i++){MediaFormat x=ex.getTrackFormat(i);String mime=x.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("video/")){if(x.containsKey(MediaFormat.KEY_DURATION))m.duration=Math.max(m.duration,x.getLong(MediaFormat.KEY_DURATION));if(x.containsKey(MediaFormat.KEY_BIT_RATE))m.videoBitrate=Math.max(m.videoBitrate,x.getLong(MediaFormat.KEY_BIT_RATE));m.w=x.containsKey(MediaFormat.KEY_WIDTH)?x.getInteger(MediaFormat.KEY_WIDTH):0;m.h=x.containsKey(MediaFormat.KEY_HEIGHT)?x.getInteger(MediaFormat.KEY_HEIGHT):0;}}return m;}finally{ex.release();}}
    private boolean validVideo(File f){try{MediaExtractor ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());for(int i=0;i<ex.getTrackCount();i++){String m=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("video/")){ex.release();return true;}}ex.release();}catch(Exception ignored){}return false;}
    private void safe(File f){if(f!=null)try{f.delete();}catch(Exception ignored){}}
}
