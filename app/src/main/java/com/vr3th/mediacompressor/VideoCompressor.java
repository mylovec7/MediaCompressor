package com.vr3th.mediacompressor;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adaptive, size-guarded compressor. Originals are never replaced. */
public final class VideoCompressor {
    private final MainActivity a;
    VideoCompressor(MainActivity a){this.a=a;}

    private static final class Meta { long duration; long videoBitrate; long audioBitrate; int w; int h; }
    private static final class Candidate { File file; long size; int bitrate; String codec; }

    void run(Uri src, AtomicBoolean cancel){
        File local=null; ArrayList<File> candidates=new ArrayList<>();
        try{
            if(src==null) throw new IOException("input missing");
            String ext=extensionFromUri(src);
            if(!isSupportedInput(ext)) throw new IOException("Format tidak termasuk daftar Video Compressor.");
            long original=a.sourceSize(src);
            local=a.copyToTemp(src,guessExtension(src));
            if(local.length()<=0) throw new IOException("input could not be localized");
            Meta m=readMeta(local);
            if(m.duration<=0) throw new IOException("video duration unavailable");
            boolean sourceAudio=hasAudio(local);

            // If the source is already very low bitrate, transcoding is unlikely to help.
            if(original>0 && m.videoBitrate>0 && m.duration>0 && m.videoBitrate<260_000){
                a.videoFinished("> SMART SKIP\nSource is already highly compressed.\nOriginal quality preserved ✓\nINTEGRITY: OK  //  ORIGINAL SAFE");
                return;
            }

            String codec=VideoEngine.bestVideoMime();
            // Three quality points: first tries to preserve quality, later points pursue size.
            double[] factors=codec.equals(MimeTypes.VIDEO_H265)?new double[]{0.72,0.60,0.50}:new double[]{0.68,0.56,0.46};
            Candidate best=null;
            for(double factor:factors){
                if(cancel.get()) throw new IOException("operation cancelled");
                long base=m.videoBitrate>0?m.videoBitrate:Math.max(320_000,(original*8)/m.duration);
                long target=Math.max(220_000,Math.min(base-1,Math.round(base*factor)));
                // Never ask for an absurd bitrate below a resolution-aware floor.
                long floor=qualityFloor(m.w,m.h);
                target=Math.max(floor,target);
                File out=new File(a.outputDir(),".tmp_compress_"+System.nanoTime()+".mp4");
                candidates.add(out);
                String error=transform(mediaItemFor(local),out,cancel,target,codec);
                if(error!=null||!out.exists()||out.length()<=0){safe(out);continue;}
                if(!hasValidVideo(out)||(sourceAudio&&!hasAudio(out))){safe(out);continue;}
                long resultDur=durationUs(out);
                if(resultDur<=0||Math.abs(resultDur-m.duration)>1_500_000L){safe(out);continue;}
                if(original<=0||out.length()>=original){safe(out);continue;}
                Candidate c=new Candidate();c.file=out;c.size=out.length();c.bitrate=(int)target;c.codec=codec;
                // Prefer the highest-quality result that still achieves a meaningful reduction.
                if(best==null || better(c,best,original)) { if(best!=null)safe(best.file); best=c; }
                else safe(out);
                if(original>0 && best.size<=Math.round(original*0.82)) break;
            }
            if(best==null){
                a.videoFinished("> OPTIMAL RESULT\nNo smaller output was found.\nOriginal quality preserved ✓\nINTEGRITY: OK  //  ORIGINAL SAFE");
                return;
            }
            a.publishVideo(best.file);a.videoProgress(100);
            double saved=(original-best.size)*100.0/original;
            a.videoFinished("> SMART COMPRESSION: COMPLETE\n"+a.fmtSize(original)+" → "+a.fmtSize(best.size)+
                    "\nSAVED "+String.format(Locale.US,"%.1f",Math.max(0,saved))+"%"+
                    "\n\n✓ ADAPTIVE BITRATE\n✓ "+(best.codec.equals(MimeTypes.VIDEO_H265)?"H.265/HEVC":"H.264/AVC")+" HARDWARE PATH\n✓ VIDEO + AUDIO VERIFIED\n✓ OUTPUT VERIFIED\n✓ ORIGINAL PRESERVED");
            safe(best.file);best=null;
        }catch(Exception e){
            a.videoFinished("Gagal: "+cleanError(e.getMessage())+"\nINTEGRITY: OK  //  ORIGINAL SAFE");
        }finally{
            if(local!=null)safe(local);
            for(File f:candidates)safe(f);
        }
    }

    private boolean better(Candidate x,Candidate y,long original){
        boolean xMeaningful=x.size<=Math.round(original*0.85), yMeaningful=y.size<=Math.round(original*0.85);
        if(xMeaningful!=yMeaningful)return xMeaningful;
        // Within the same reduction class, higher target bitrate wins quality.
        return x.bitrate>y.bitrate;
    }

    private long qualityFloor(int w,int h){
        long px=(long)Math.max(2,w)*Math.max(2,h);
        if(px>=8_000_000L)return 2_500_000L;
        if(px>=2_000_000L)return 1_300_000L;
        if(px>=900_000L)return 850_000L;
        return 450_000L;
    }

    private Meta readMeta(File f)throws IOException{
        MediaExtractor ex=new MediaExtractor();Meta m=new Meta();
        try{ex.setDataSource(f.getAbsolutePath());
            for(int i=0;i<ex.getTrackCount();i++){
                MediaFormat fmt=ex.getTrackFormat(i);String mime=fmt.getString(MediaFormat.KEY_MIME);if(mime==null)continue;
                if(mime.startsWith("video/")){
                    if(fmt.containsKey(MediaFormat.KEY_DURATION))m.duration=Math.max(m.duration,fmt.getLong(MediaFormat.KEY_DURATION));
                    if(fmt.containsKey(MediaFormat.KEY_BIT_RATE))m.videoBitrate=Math.max(m.videoBitrate,fmt.getLong(MediaFormat.KEY_BIT_RATE));
                    if(fmt.containsKey(MediaFormat.KEY_WIDTH))m.w=fmt.getInteger(MediaFormat.KEY_WIDTH);
                    if(fmt.containsKey(MediaFormat.KEY_HEIGHT))m.h=fmt.getInteger(MediaFormat.KEY_HEIGHT);
                }else if(mime.startsWith("audio/")&&fmt.containsKey(MediaFormat.KEY_BIT_RATE))m.audioBitrate=Math.max(m.audioBitrate,fmt.getLong(MediaFormat.KEY_BIT_RATE));
            }
            if(m.duration<=0)throw new IOException("video duration unavailable");
            return m;
        }finally{ex.release();}
    }

    private String extensionFromUri(Uri src){
        try(android.database.Cursor c=a.getContentResolver().query(src,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);if(i>=0){String n=c.getString(i);if(n!=null){int d=n.lastIndexOf('.');if(d>=0&&d<n.length()-1)return n.substring(d).toLowerCase(Locale.US);}}}
        }catch(Exception ignored){}return "";
    }
    private String guessExtension(Uri src){String x=extensionFromUri(src);return x.matches("\\.[a-z0-9]{1,8}")?x:".mp4";}
    private MediaItem mediaItemFor(File f){return MediaItem.fromUri(Uri.fromFile(f));}

    private boolean isSupportedInput(String x){
        x=x.toLowerCase(Locale.US);
        return x.matches("\\.(3g2|3gp|f4v|m4v|mkv|mov|mp4|qt|webm|m2ts|mts|ts|avi|flv|m2v|mpeg|mpg|mxf|ogv|rm|wmv|wtv|vob)$");
    }

    private String transform(MediaItem item,File out,AtomicBoolean cancel,int bitrate,String codec)throws Exception{
        final Transformer[] box={null};final String[] error={null};final boolean[] finished={false};final CountDownLatch latch=new CountDownLatch(1);Handler main=new Handler(Looper.getMainLooper());
        main.post(()->{try{
            Transformer t=VideoEngine.builder(a.getApplicationContext(),codec,MimeTypes.AUDIO_AAC,bitrate).build();box[0]=t;
            t.addListener(new Transformer.Listener(){
                @Override public void onCompleted(Composition c,ExportResult r){if(finished[0])return;finished[0]=true;latch.countDown();}
                @Override public void onError(Composition c,ExportResult r,ExportException e){if(finished[0])return;finished[0]=true;error[0]=e.getMessage()==null?"media transformation failed":e.getMessage();latch.countDown();}
            });
            t.start(new EditedMediaItem.Builder(item).build(),out.getAbsolutePath());
        }catch(Exception e){if(finished[0])return;finished[0]=true;error[0]=e.getMessage()==null?"transformation could not start":e.getMessage();latch.countDown();}});
        while(!latch.await(200,TimeUnit.MILLISECONDS)){if(cancel.get())try{if(box[0]!=null)box[0].cancel();}catch(Exception ignored){}}
        if(cancel.get()){safe(out);throw new IOException("operation cancelled");}return error[0];
    }

    private boolean hasValidVideo(File f){MediaExtractor ex=null;try{ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());for(int i=0;i<ex.getTrackCount();i++){MediaFormat x=ex.getTrackFormat(i);String m=x.getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("video/")&&x.containsKey(MediaFormat.KEY_DURATION)&&x.getLong(MediaFormat.KEY_DURATION)>0)return true;}return false;}catch(Exception e){return false;}finally{if(ex!=null)ex.release();}}
    private boolean hasAudio(File f){MediaExtractor ex=null;try{ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());for(int i=0;i<ex.getTrackCount();i++){String m=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("audio/"))return true;}return false;}catch(Exception e){return false;}finally{if(ex!=null)ex.release();}}
    private long durationUs(File f){MediaExtractor ex=null;long d=0;try{ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());for(int i=0;i<ex.getTrackCount();i++){MediaFormat x=ex.getTrackFormat(i);if(x.containsKey(MediaFormat.KEY_DURATION))d=Math.max(d,x.getLong(MediaFormat.KEY_DURATION));}return d;}catch(Exception e){return 0;}finally{if(ex!=null)ex.release();}}
    private String cleanError(String s){if(s==null||s.trim().isEmpty())return "Video tidak dapat diproses.";String x=s.replace('\n',' ').trim();String l=x.toLowerCase(Locale.US);if(l.contains("encoder")&&l.contains("available"))return "No compatible video encoder is available in this device.";if(l.contains("decoder"))return "No compatible decoder is available for this video.";return x.length()>180?x.substring(0,180):x;}
    private void safe(File f){if(f!=null)try{f.delete();}catch(Exception ignored){}}
}
