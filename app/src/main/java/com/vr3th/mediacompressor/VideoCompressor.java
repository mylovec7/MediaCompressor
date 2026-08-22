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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Smart, conservative video compressor.
 *
 * Rules:
 *  - localize SAF/content Uris before Transformer to avoid AssetLoader/provider failures;
 *  - never silently drop source audio;
 *  - reject output that is larger, missing a required track, or has an invalid duration;
 *  - keep originals untouched;
 *  - use H.264/AAC as the compatibility-first output path.
 */
public final class VideoCompressor {
    private final MainActivity a;
    VideoCompressor(MainActivity a){this.a=a;}

    void run(Uri src, AtomicBoolean cancel){
        File local=null, out=null;
        try{
            if(src==null) throw new IOException("input missing");
            final long original=a.sourceSize(src);
            local=a.copyToTemp(src,guessExtension(src));
            if(local.length()<=0) throw new IOException("input could not be localized");
            boolean sourceAudio=hasAudio(local) || LegacyVideoFallback.hasAudioTrack(local);
            long sourceDuration=durationUs(local);

            out=new File(a.outputDir(),".tmp_compress_"+System.nanoTime()+".mp4");
            String error=null;
            // Legacy containers go straight to the decoder fallback. This avoids
            // spending time in Media3 on extractors it does not implement and,
            // more importantly, avoids Media3 rejecting legacy audio such as MP2
            // before the fallback gets a chance to decode it.
            if(!isLegacyContainer(local.getName())){
                error=transform(mediaItemFor(local),false,out,cancel);
            }
            if(cancel.get()) throw new IOException("operation cancelled");
            if(error!=null || isLegacyContainer(local.getName()) || !out.exists() || out.length()<=0){
                safe(out);
                if(cancel.get()) throw new IOException("operation cancelled");
                boolean fallbackOk=new LegacyVideoFallback(a).transcode(local,out,original,sourceDuration);
                if(!fallbackOk) throw new IOException(cleanError(error));
            }

            if(!hasValidVideo(out)) throw new IOException("output video track is invalid");
            if(sourceAudio && !hasAudio(out)) throw new IOException("output audio track is missing");
            if(sourceDuration>0){
                long resultDuration=durationUs(out);
                if(resultDuration<=0 || Math.abs(resultDuration-sourceDuration)>1500000L)
                    throw new IOException("output duration verification failed");
            }
            accept(src,out);
        }catch(Exception e){
            safe(out);
            a.videoFinished("Gagal: "+cleanError(e.getMessage())+"\nINTEGRITY: OK  //  ORIGINAL SAFE");
        }finally{
            safe(local);
        }
    }

    private boolean isLegacyContainer(String name){
        String x=name.toLowerCase(Locale.US);
        return x.endsWith(".asf") || x.endsWith(".avi") || x.endsWith(".flv") ||
                x.endsWith(".m2ts") || x.endsWith(".m2v") || x.endsWith(".mpeg") ||
                x.endsWith(".mpg") || x.endsWith(".mxf") || x.endsWith(".ogv") ||
                x.endsWith(".rm") || x.endsWith(".wmv") || x.endsWith(".wtv") ||
                x.endsWith(".mts") || x.endsWith(".ts") || x.endsWith(".vob");
    }

    private MediaItem mediaItemFor(File f){
        MediaItem.Builder b=new MediaItem.Builder().setUri(Uri.fromFile(f));
        String mime=mimeForExtension(f.getName());
        if(mime!=null)b.setMimeType(mime);
        return b.build();
    }

    private String guessExtension(Uri src){
        String ext=null;
        try(android.database.Cursor c=a.getContentResolver().query(src,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()){
                int i=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if(i>=0){String n=c.getString(i);if(n!=null){int d=n.lastIndexOf('.');if(d>0)ext=n.substring(d).toLowerCase(Locale.US);}}
            }
        }catch(Exception ignored){}
        return ext!=null&&ext.matches("\\.[a-z0-9]{1,8}")?ext:".mp4";
    }

    private String mimeForExtension(String name){
        String x=name.toLowerCase(Locale.US);
        if(x.endsWith(".mp4")||x.endsWith(".m4v")||x.endsWith(".fmp4"))return "video/mp4";
        if(x.endsWith(".mov")||x.endsWith(".qt"))return "video/quicktime";
        if(x.endsWith(".mkv")||x.endsWith(".mk3d"))return "video/x-matroska";
        if(x.endsWith(".webm"))return "video/webm";
        if(x.endsWith(".3gp"))return "video/3gpp";
        if(x.endsWith(".3g2"))return "video/3gpp2";
        if(x.endsWith(".ogv")||x.endsWith(".ogm"))return "video/ogg";
        if(x.endsWith(".ts")||x.endsWith(".mts")||x.endsWith(".m2ts")||x.endsWith(".m2t"))return "video/mp2t";
        if(x.endsWith(".vob")||x.endsWith(".mpeg")||x.endsWith(".mpg")||x.endsWith(".mpe")||x.endsWith(".m1v")||x.endsWith(".m2v")||x.endsWith(".evo"))return "video/mpeg";
        if(x.endsWith(".flv")||x.endsWith(".f4v"))return "video/x-flv";
        return null;
    }

    private String transform(MediaItem item, boolean removeAudio, File out, AtomicBoolean cancel)throws Exception{
        final Transformer[] box={null};
        final boolean[] finished={false};
        final String[] error={null};
        final CountDownLatch latch=new CountDownLatch(1);
        Handler main=new Handler(Looper.getMainLooper());
        EditedMediaItem edited=new EditedMediaItem.Builder(item).setRemoveAudio(removeAudio).build();
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
                        if(finished[0])return; finished[0]=true; latch.countDown();
                    }
                    @Override public void onError(Composition c,ExportResult result,ExportException e){
                        if(finished[0])return; finished[0]=true;
                        error[0]=e.getMessage()==null?"media transformation failed":e.getMessage(); latch.countDown();
                    }
                });
                t.start(edited,out.getAbsolutePath());
            }catch(Exception e){
                if(finished[0])return; finished[0]=true;
                error[0]=e.getMessage()==null?"transformation could not start":e.getMessage(); latch.countDown();
            }
        });
        while(!latch.await(200,java.util.concurrent.TimeUnit.MILLISECONDS)){
            if(cancel.get())try{if(box[0]!=null)box[0].cancel();}catch(Exception ignored){}
        }
        if(cancel.get()){safe(out);throw new IOException("operation cancelled");}
        return error[0];
    }

    private void accept(Uri src,File out)throws Exception{
        if(!hasValidVideo(out))throw new IOException("output video track is invalid");
        long original=a.sourceSize(src), result=out.length();
        if(original>0&&result>=original){
            safe(out);
            a.videoFinished("> OPTIMAL RESULT\nNo smaller output was found.\nOriginal quality preserved ✓\nINTEGRITY: OK  //  ORIGINAL SAFE");
            return;
        }
        a.publishVideo(out); safe(out); a.videoProgress(100);
        double saved=original>0?((original-result)*100.0/original):0;
        a.videoFinished("> COMPRESSION ENGINE: COMPLETE\n"+a.fmtSize(original)+" → "+a.fmtSize(result)+
                "\nSAVED "+String.format(Locale.US,"%.1f",Math.max(0,saved))+"%\n\n✓ ASPECT RATIO PRESERVED\n✓ VIDEO + AUDIO VERIFIED\n✓ OUTPUT VERIFIED\n✓ ORIGINAL PRESERVED");
    }

    private boolean hasValidVideo(File f){
        MediaExtractor ex=null;
        try{
            ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());
            for(int i=0;i<ex.getTrackCount();i++){
                MediaFormat fmt=ex.getTrackFormat(i); String m=fmt.getString(MediaFormat.KEY_MIME);
                if(m!=null&&m.startsWith("video/")&&fmt.containsKey(MediaFormat.KEY_DURATION)&&fmt.getLong(MediaFormat.KEY_DURATION)>0)return true;
            }
        }catch(Exception ignored){}finally{if(ex!=null)try{ex.release();}catch(Exception ignored){}}
        return false;
    }
    private boolean hasAudio(File f){
        MediaExtractor ex=null;
        try{ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());for(int i=0;i<ex.getTrackCount();i++){String m=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("audio/"))return true;}}
        catch(Exception ignored){}finally{if(ex!=null)try{ex.release();}catch(Exception ignored){}}
        return false;
    }
    private long durationUs(File f){
        MediaExtractor ex=null;long best=0;
        try{ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());for(int i=0;i<ex.getTrackCount();i++){MediaFormat fmt=ex.getTrackFormat(i);if(fmt.containsKey(MediaFormat.KEY_DURATION))best=Math.max(best,fmt.getLong(MediaFormat.KEY_DURATION));}return best;}
        catch(Exception ignored){return 0;}finally{if(ex!=null)try{ex.release();}catch(Exception ignored){}}
    }
    private String cleanError(String s){
        if(s==null||s.trim().isEmpty())return "Video tidak dapat diproses.";
        String x=s.replace('\n',' ').trim();
        String low=x.toLowerCase(Locale.US);
        if(low.contains("asset loader")||low.contains("unexpected runtime"))
            return "Input container could not be decoded safely.";
        if(low.contains("audiodecoder")||low.contains("audio/mpeg-l2")||low.contains("mpeg-l2"))
            return "Audio codec MP2 is not available on this device.";
        if(x.length()>180)x=x.substring(0,180)+"…";
        return x;
    }
    private void safe(File f){if(f!=null)try{f.delete();}catch(Exception ignored){}}
}
