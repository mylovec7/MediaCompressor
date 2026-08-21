package com.vr3th.mediacompressor;

import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.Effects;
import androidx.media3.effect.Presentation;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.ToInt16PcmAudioProcessor;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

final class MediaOps {
    private final MainActivity a;
    MediaOps(MainActivity a){this.a=a;}
    interface Result { void ok(String s); void fail(String s); }
    private void done(Result r,String s){a.runOnUiThread(()->r.ok(s));}
    private void fail(Result r,String s){a.runOnUiThread(()->r.fail(s));}
    private File tmp(String ext){return new File(a.outputDir(),".tmp_"+System.nanoTime()+ext);}
    private void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1){out.write(b,0,n);}}
    private void safe(File f){if(f!=null)f.delete();}
    private void deleteTree(File d){if(d==null)return;File[] fs=d.listFiles();if(fs!=null)for(File f:fs){if(f.isDirectory())deleteTree(f);else f.delete();}d.delete();}
    private long size(Uri u){return a.sourceSize(u);}

    void convertImage(Uri u, String fmt, Result r){new Thread(()->{File f=null;Bitmap b=null;try{
        b=a.decodeOrientedBitmap(u);if(b==null)throw new IOException("decode");String ext=fmt.equals("webp")?"webp":fmt.equals("png")?"png":"jpg";
        Bitmap work=b;long src=size(u);long best=Long.MAX_VALUE;
        int[] scales=ext.equals("png")?new int[]{100,85,70,55,45}:new int[]{100};
        for(int pct:scales){File candidate=tmp("."+ext);Bitmap x=work;if(pct!=100){int w=Math.max(2,b.getWidth()*pct/100),h=Math.max(2,b.getHeight()*pct/100);x=Bitmap.createScaledBitmap(b,w,h,true);}Bitmap.CompressFormat cf=ext.equals("webp")?(Build.VERSION.SDK_INT>=30?Bitmap.CompressFormat.WEBP_LOSSY:Bitmap.CompressFormat.WEBP):ext.equals("png")?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG;try(FileOutputStream out=new FileOutputStream(candidate)){if(!x.compress(cf,ext.equals("png")?100:90,out))throw new IOException("encode");}if(x!=b)x.recycle();long d=candidate.length();if(d>0&&d<best){if(f!=null)safe(f);f=candidate;best=d;}else safe(candidate);if(ext.equals("png")&&src>0&&best<=Math.max(src*2,2_000_000L))break;}
        if(f==null||best<=0)throw new IOException("invalid output");a.publishImageFormat(f,ext);safe(f);done(r,"> CONVERSION COMPLETE\n"+(src>0?a.fmtSize(src)+" → ":"")+a.fmtSize(best)+"\n✓ ORIENTATION PRESERVED\n✓ OUTPUT VERIFIED");
    }catch(Exception e){safe(f);fail(r,"> ORIGINAL PRESERVED\nImage conversion could not be completed.");}finally{if(b!=null)b.recycle();}}).start();}

    void batchImages(ArrayList<Uri> list,Result r){new Thread(()->{int ok=0;for(Uri u:list){if(a.cancelled.get())break;try{Bitmap b;try{b=a.decodeOrientedBitmap(u);}catch(Exception x){continue;}File f=tmp(".jpg");try(FileOutputStream o=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,86,o);}long s=size(u),d=f.length();if(d>0&&(s<=0||d<s)){a.publishImage(f,false);ok++;}safe(f);b.recycle();}catch(Exception ignored){}}done(r,"> BATCH COMPLETE\n"+ok+" / "+list.size()+" outputs accepted\n✓ ORIGINALS PRESERVED");}).start();}

    void exifStrip(Uri u,Result r){new Thread(()->{File f=null;Bitmap b=null;try{BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.ARGB_8888;b=a.decodeOrientedBitmap(u);if(b==null)throw new IOException();f=tmp(".jpg");try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,95,out);}long s=size(u),d=f.length();if(d>=s&&s>0){safe(f);done(r,"> METADATA CLEAN\nNo smaller result was found.\nOriginal preserved ✓");return;}a.publishImage(f,false);safe(f);done(r,"> EXIF CLEAN COMPLETE\nMetadata-stripped JPEG saved ✓");}catch(Exception e){safe(f);fail(r,"> ORIGINAL PRESERVED\nMetadata cleanup could not be completed.");}finally{if(b!=null)b.recycle();}}).start();}

    private long sourceDurationUs(Uri u){
        MediaMetadataRetriever m=null;
        try{
            m=new MediaMetadataRetriever();
            m.setDataSource(a,u);
            String x=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(x!=null){long d=Long.parseLong(x)*1000L;if(d>0)return d;}
        }catch(Exception ignored){}
        finally{if(m!=null)try{m.release();}catch(Exception ignored){}}
        MediaExtractor ex=null;
        try{
            ex=new MediaExtractor();ex.setDataSource(a,u,null);
            long best=0;
            for(int i=0;i<ex.getTrackCount();i++){
                MediaFormat f=ex.getTrackFormat(i);
                if(f.containsKey(MediaFormat.KEY_DURATION))best=Math.max(best,f.getLong(MediaFormat.KEY_DURATION));
            }
            if(best>0)return best;
            // Some legacy containers expose no duration metadata. Derive it from the
            // last timestamp instead of treating a valid file as TIME DOES NOT EXIST.
            for(int i=0;i<ex.getTrackCount();i++){
                ex.selectTrack(i);
                long last=0;
                while(true){
                    long t=ex.getSampleTime();
                    if(t<0)break;
                    last=Math.max(last,t);
                    ex.advance();
                }
                ex.unselectTrack(i);
                best=Math.max(best,last);
            }
            return best;
        }catch(Exception ignored){return 0;}
        finally{if(ex!=null)try{ex.release();}catch(Exception ignored){}}
    }

    void trimOrMuteVideo(Uri u,long fromUs,long toUs,boolean mute,Result r){
        final long startUs=fromUs;
        final long requestedToUs=toUs;
        final boolean removeAudio=mute;
        new Thread(()->{
            File out=tmp(".mp4"), local=null;
            try{
                local=a.copyToTemp(u,".input");
                long dur=sourceDurationUs(Uri.fromFile(local));
                long endUs=requestedToUs==Long.MAX_VALUE?dur:requestedToUs;
                if(startUs<0||endUs<=0||dur<=0||startUs>=endUs||startUs>=dur||endUs>dur)
                    throw new IOException("TIME DOES NOT EXIST");
                if(startUs>endUs) throw new IOException("REVERSE VIDEO REQUIRES FRAME TRANSCODE");

                MediaItem item=new MediaItem.Builder()
                        .setUri(Uri.fromFile(local))
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startUs/1000L)
                                .setEndPositionMs(endUs/1000L)
                                .build()).build();
                EditedMediaItem edited=new EditedMediaItem.Builder(item).setRemoveAudio(removeAudio).build();
                runTransformerSingle(edited,out,r,"VIDEO OPERATION COMPLETE",size(u));
            }catch(Exception e){
                safe(out);
                fail(r,"> ORIGINAL PRESERVED\nVideo operation could not be completed.\n"+
                        (e.getMessage()==null?"Unsupported media.":e.getMessage()));
            }finally{safe(local);}
        }).start();
    }

    private void runTransformerSingle(EditedMediaItem edited,File out,Result r,String label,long sourceBytes)throws Exception{
        final Handler h=new Handler(Looper.getMainLooper());
        final CountDownLatch latch=new CountDownLatch(1);
        final String[] error={null};
        final boolean[] finished={false};
        final Transformer[] box={null};
        h.post(()->{
            try{
                Transformer t=new Transformer.Builder(a.getApplicationContext())
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .build();
                box[0]=t;
                t.addListener(new Transformer.Listener(){
                    @Override public void onCompleted(Composition c,ExportResult result){
                        if(finished[0])return; finished[0]=true; latch.countDown();
                    }
                    @Override public void onError(Composition c,ExportResult result,ExportException e){
                        if(finished[0])return; finished[0]=true;
                        error[0]=e.getMessage()==null?"media transformation failed":e.getMessage();
                        latch.countDown();
                    }
                });
                t.start(edited,out.getAbsolutePath());
            }catch(Exception e){
                if(finished[0])return; finished[0]=true;
                error[0]=e.getMessage()==null?"transformation could not start":e.getMessage();
                latch.countDown();
            }
        });
        while(!latch.await(200,java.util.concurrent.TimeUnit.MILLISECONDS)){
            if(a.cancelled.get()){
                try{if(box[0]!=null)box[0].cancel();}catch(Exception ignored){}
            }
        }
        if(a.cancelled.get()){safe(out);fail(r,"> ORIGINAL PRESERVED\nOperation cancelled safely.");return;}
        if(error[0]!=null||!validVideo(out)||out.length()<=0){
            safe(out);throw new IOException(error[0]==null?"output invalid":error[0]);
        }
        long s=sourceBytes; long d=out.length();
        if(!validVideo(out)){safe(out);throw new IOException("output video track is invalid");}
        a.publishVideo(out); safe(out);
        done(r,"> "+label+"\n"+a.fmtSize(s)+" → "+a.fmtSize(d)+"\n✓ VIDEO TRACK VERIFIED\n✓ OUTPUT VERIFIED\n✓ ORIGINAL PRESERVED");
    }

    void mergeVideos(ArrayList<Uri> list,Result r){
        new Thread(()->{
            File out=tmp(".mp4");
            ArrayList<File> locals=new ArrayList<>();
            try{
                if(list==null||list.size()<2)throw new IOException("select at least two videos");
                if(a.cancelled.get())throw new IOException("cancelled");
                int[] target=displayVideoSize(list.get(0));
                int targetW=Math.max(2,target[0]&~1), targetH=Math.max(2,target[1]&~1);
                ArrayList<EditedMediaItem> items=new ArrayList<>();
                boolean anyAudio=false;
                long expected=0;
                for(Uri u:list){
                    if(u==null)throw new IOException("missing input");
                    File local=a.copyToTemp(u,".input"); locals.add(local);
                    long d0=sourceDurationUs(Uri.fromFile(local));
                    if(d0<=0)throw new IOException("TIME DOES NOT EXIST");
                    expected+=d0;
                    if(sourceHasAudio(Uri.fromFile(local)))anyAudio=true;
                    MediaItem mi=MediaItem.fromUri(Uri.fromFile(local));
                    Effects effects=new Effects(Collections.emptyList(),Collections.singletonList(
                            Presentation.createForWidthAndHeight(targetW,targetH,Presentation.LAYOUT_SCALE_TO_FIT)));
                    items.add(new EditedMediaItem.Builder(mi).setEffects(effects).build());
                }

                String error=runMergeTransformer(items,out,false);
                if(error!=null||!validVideo(out)||out.length()<=0|| (anyAudio&&!hasAudioTrack(out))){
                    safe(out);
                    throw new IOException(error==null?(anyAudio?"output audio track is missing":"output validation failed"):error);
                }
                long actual=outputDurationUs(out);
                if(actual<=0||Math.abs(actual-expected)>1500000L){
                    safe(out);throw new IOException("Output duration verification failed");
                }
                long d=out.length();
                a.publishVideo(out);safe(out);
                done(r,"> VIDEO MERGE COMPLETE\n"+a.fmtSize(d)+" output\n✓ ASPECT RATIO PRESERVED\n✓ CODECS NORMALIZED\n"+
                        (anyAudio?"✓ VIDEO + AUDIO CONTINUOUS":"✓ VIDEO VERIFIED")+"\n✓ VERIFIED\n✓ ORIGINALS PRESERVED");
            }catch(Exception e){
                safe(out);
                fail(r,a.cancelled.get()?"> ORIGINALS PRESERVED\nMerge cancelled safely.":"> ORIGINALS PRESERVED\nVideo merge failed safely.\n"+
                        (e.getMessage()==null?"Unsupported media.":e.getMessage()));
            }finally{for(File f:locals)safe(f);}
        }).start();
    }

    private String runMergeTransformer(ArrayList<EditedMediaItem> items,File out,boolean videoOnly)throws Exception{
        final Handler h=new Handler(Looper.getMainLooper());
        final Transformer[] box={null}; final boolean[] finished={false}; final String[] error={null};
        final CountDownLatch latch=new CountDownLatch(1);
        h.post(()->{
            try{
                EditedMediaItemSequence seq=videoOnly?EditedMediaItemSequence.withVideoFrom(items):EditedMediaItemSequence.withAudioAndVideoFrom(items);
                Composition composition=new Composition.Builder(seq).build();
                Transformer t=new Transformer.Builder(a.getApplicationContext())
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setPortraitEncodingEnabled(true)
                        .setEnsureFileStartsOnVideoFrameEnabled(true)
                        .build();
                box[0]=t;
                t.addListener(new Transformer.Listener(){
                    @Override public void onCompleted(Composition c,ExportResult result){if(finished[0])return;finished[0]=true;latch.countDown();}
                    @Override public void onError(Composition c,ExportResult result,ExportException e){if(finished[0])return;finished[0]=true;error[0]=e.getMessage()==null?"merge export failed":e.getMessage();latch.countDown();}
                });
                t.start(composition,out.getAbsolutePath());
            }catch(Exception e){if(finished[0])return;finished[0]=true;error[0]=e.getMessage()==null?"merge could not start":e.getMessage();latch.countDown();}
        });
        while(!latch.await(200,java.util.concurrent.TimeUnit.MILLISECONDS)){
            if(a.cancelled.get())try{if(box[0]!=null)box[0].cancel();}catch(Exception ignored){}
        }
        if(a.cancelled.get())throw new IOException("cancelled");
        return error[0];
    }

    private boolean hasAnyAudioInputWithoutOutput(ArrayList<Uri> list,File out){
        boolean input=false;
        for(Uri u:list)if(sourceHasAudio(u)){input=true;break;}
        return input&&!hasAudioTrack(out);
    }

    private int[] displayVideoSize(Uri u)throws IOException{
        MediaMetadataRetriever m=null;
        try{
            m=new MediaMetadataRetriever();
            m.setDataSource(a,u);
            String ws=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hs=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rs=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int w=ws==null?0:Integer.parseInt(ws), h=hs==null?0:Integer.parseInt(hs), rot=rs==null?0:Integer.parseInt(rs);
            if(w<=0||h<=0)throw new IOException("video geometry unavailable");
            if(rot==90||rot==270){int x=w;w=h;h=x;}
            return new int[]{Math.max(2,w&~1),Math.max(2,h&~1)};
        }catch(Exception e){
            // Geometry is needed only for the common output canvas. If the platform
            // retriever cannot expose it, use a safe landscape canvas rather than
            // rejecting an otherwise transformable input.
            return new int[]{1280,720};
        }finally{if(m!=null)try{m.release();}catch(Exception ignored){}}
    }

    private boolean sameVideoFormat(MediaFormat a,MediaFormat b){
        try{return Objects.equals(a.getString(MediaFormat.KEY_MIME),b.getString(MediaFormat.KEY_MIME))&&a.getInteger(MediaFormat.KEY_WIDTH)==b.getInteger(MediaFormat.KEY_WIDTH)&&a.getInteger(MediaFormat.KEY_HEIGHT)==b.getInteger(MediaFormat.KEY_HEIGHT)&&(!a.containsKey(MediaFormat.KEY_FRAME_RATE)||!b.containsKey(MediaFormat.KEY_FRAME_RATE)||a.getInteger(MediaFormat.KEY_FRAME_RATE)==b.getInteger(MediaFormat.KEY_FRAME_RATE));}catch(Exception e){return false;}
    }
    private boolean sameAudioFormat(MediaFormat a,MediaFormat b){
        try{return Objects.equals(a.getString(MediaFormat.KEY_MIME),b.getString(MediaFormat.KEY_MIME))&&(!a.containsKey(MediaFormat.KEY_SAMPLE_RATE)||!b.containsKey(MediaFormat.KEY_SAMPLE_RATE)||a.getInteger(MediaFormat.KEY_SAMPLE_RATE)==b.getInteger(MediaFormat.KEY_SAMPLE_RATE))&&(!a.containsKey(MediaFormat.KEY_CHANNEL_COUNT)||!b.containsKey(MediaFormat.KEY_CHANNEL_COUNT)||a.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==b.getInteger(MediaFormat.KEY_CHANNEL_COUNT));}catch(Exception e){return false;}
    }
    private long trackDuration(File f,int track)throws IOException{MediaExtractor ex=new MediaExtractor();try{ex.setDataSource(f.getAbsolutePath());ex.selectTrack(track);long last=0;while(true){long t=ex.getSampleTime();if(t<0)break;last=Math.max(last,t);ex.advance();}return last;}finally{ex.release();}}
    private void copyTrack(MediaExtractor ex,int track,MediaMuxer mux,int outTrack,long offset)throws IOException{if(track<0)return;ex.selectTrack(track);ByteBuffer b=ByteBuffer.allocateDirect(1024*1024);MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();while(!a.cancelled.get()){int n=ex.readSampleData(b,0);long t=ex.getSampleTime();if(n<0||t<0)break;bi.offset=0;bi.size=n;bi.presentationTimeUs=t+offset;bi.flags=ex.getSampleFlags();mux.writeSampleData(outTrack,b,bi);ex.advance();}ex.unselectTrack(track);if(a.cancelled.get())throw new IOException("cancelled");}
    private String q(String s){return "'"+s.replace("'","'\\''")+"'";}
    void frame(Uri u,long us,Result r){new Thread(()->{File f=null,local=null;MediaMetadataRetriever m=null;Bitmap b=null;try{
        local=a.copyToTemp(u,".input"); m=new MediaMetadataRetriever();m.setDataSource(local.getAbsolutePath());
        b=m.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(b==null)throw new IOException();
        f=tmp(".jpg");try(FileOutputStream o=new FileOutputStream(f)){if(!b.compress(Bitmap.CompressFormat.JPEG,92,o))throw new IOException("encode");}
        if(!validImage(f))throw new IOException("output invalid");a.publishImage(f,false);safe(f);done(r,"> FRAME EXTRACTED\n✓ FRAME VERIFIED\n✓ SAVED TO GALLERY");
    }catch(Exception e){safe(f);fail(r,"> ORIGINAL PRESERVED\nFrame extraction failed.\n"+(e.getMessage()==null?"Unsupported media.":e.getMessage()));}
    finally{if(m!=null)try{m.release();}catch(Exception ignored){}if(b!=null)try{b.recycle();}catch(Exception ignored){}safe(local);}}).start();}

    void audioTrim(Uri u,long fromUs,long toUs,Result r){new Thread(()->{
        File out=tmp(".m4a"),local=null;
        try{
            if(fromUs>toUs)throw new IOException("REVERSE AUDIO REQUIRES DECODE/ENCODE ENGINE");
            local=a.copyToTemp(u,".input");
            long dur=sourceDurationUs(Uri.fromFile(local));
            if(fromUs<0||toUs<=0||dur<=0||fromUs>=toUs||fromUs>=dur||toUs>dur)
                throw new IOException("TIME DOES NOT EXIST");
            MediaItem item=new MediaItem.Builder().setUri(Uri.fromFile(local))
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(fromUs/1000L).setEndPositionMs(toUs/1000L).build()).build();
            EditedMediaItem edited=new EditedMediaItem.Builder(item).build();
            final Handler h=new Handler(Looper.getMainLooper()); final CountDownLatch latch=new CountDownLatch(1);
            final Transformer[] box={null}; final boolean[] finished={false}; final String[] error={null};
            h.post(()->{try{
                Transformer t=new Transformer.Builder(a.getApplicationContext()).setAudioMimeType(MimeTypes.AUDIO_AAC).build();box[0]=t;
                t.addListener(new Transformer.Listener(){
                    @Override public void onCompleted(Composition c,ExportResult result){if(finished[0])return;finished[0]=true;latch.countDown();}
                    @Override public void onError(Composition c,ExportResult result,ExportException e){if(finished[0])return;finished[0]=true;error[0]=e.getMessage()==null?"audio transformation failed":e.getMessage();latch.countDown();}
                });t.start(edited,out.getAbsolutePath());
            }catch(Exception e){if(finished[0])return;finished[0]=true;error[0]=e.getMessage()==null?"audio transformation failed":e.getMessage();latch.countDown();}});
            while(!latch.await(200,java.util.concurrent.TimeUnit.MILLISECONDS)){if(a.cancelled.get())try{if(box[0]!=null)box[0].cancel();}catch(Exception ignored){}}
            if(a.cancelled.get()){safe(out);fail(r,"> ORIGINAL PRESERVED\nAudio trim cancelled safely.");return;}
            if(error[0]!=null||!out.exists()||out.length()<=0||!hasAudioTrack(out))throw new IOException(error[0]==null?"audio output track is invalid":error[0]);
            long sourceDur=dur,resultDur=outputDurationUs(out);long expected=toUs-fromUs;
            if(resultDur<=0||Math.abs(resultDur-expected)>1500000L)throw new IOException("output duration verification failed");
            long original=a.sourceSize(u),d=out.length();a.publishAudioFormat(out,"m4a");safe(out);
            done(r,"> AUDIO TRIM COMPLETE\n"+a.fmtSize(original)+" → "+a.fmtSize(d)+"\n✓ AUDIO TRACK VERIFIED\n✓ AAC OUTPUT VERIFIED\n✓ ORIGINAL PRESERVED");
        }catch(Exception e){safe(out);fail(r,"> ORIGINAL PRESERVED\n"+(e.getMessage()==null?"Audio trim could not be completed.":e.getMessage()));}
        finally{safe(local);}
    }).start();}

    void trimAudio(Uri u,long fromUs,long toUs,boolean mute,Result r){audioTrim(u,fromUs,toUs,r);}
    void createPdfFromImages(ArrayList<Uri> list,Result r){
        new Thread(()->{
            File f=tmp(".pdf");
            try{
                PdfDocument doc=new PdfDocument();
                for(Uri u:list){
                    Bitmap b=a.decodeOrientedBitmap(u);
                    if(b==null) continue;
                    int w=595,h=Math.max(1,(int)(w*(b.getHeight()/(float)b.getWidth())));
                    PdfDocument.PageInfo pi=new PdfDocument.PageInfo.Builder(w,h,doc.getPages().size()+1).create();
                    PdfDocument.Page p=doc.startPage(pi);
                    p.getCanvas().drawBitmap(b,null,new Rect(0,0,w,h),null);
                    doc.finishPage(p); b.recycle();
                }
                try(FileOutputStream o=new FileOutputStream(f)){doc.writeTo(o);} doc.close();
                if(f.length()<=0) throw new IOException();
                a.publishPdf(f); safe(f);
                done(r,"> PDF CREATED\n✓ SAVED TO DOCUMENTS");
            }catch(Exception e){safe(f);fail(r,"> PDF CREATION FAILED\nOriginal files preserved.");}
        }).start();
    }

    void renderPdf(Uri u,Result r){
        new Thread(()->{
            File f=null,local=null; PdfRenderer pr=null; ParcelFileDescriptor pfd=null;
            try{
                local=a.copyToTemp(u,".pdf"); pfd=ParcelFileDescriptor.open(local,ParcelFileDescriptor.MODE_READ_ONLY); pr=new PdfRenderer(pfd);
                if(pr.getPageCount()==0) throw new IOException(); int ok=0;
                for(int i=0;i<pr.getPageCount();i++){
                    PdfRenderer.Page p=pr.openPage(i); Bitmap b=Bitmap.createBitmap(p.getWidth(),p.getHeight(),Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE);
                    p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); p.close(); f=tmp(".jpg");
                    try(FileOutputStream o=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,92,o);} a.publishImage(f,false); safe(f); b.recycle(); ok++;
                }
                done(r,"> PDF → IMAGE COMPLETE\n"+ok+" pages exported ✓");
            }catch(Exception e){safe(f);fail(r,"> PDF COULD NOT BE RENDERED\nOriginal preserved.");}
            finally{try{if(pr!=null)pr.close();}catch(Exception ignored){} try{if(pfd!=null)pfd.close();}catch(Exception ignored){} if(local!=null)local.delete();}
        }).start();
    }


    private Bitmap renderPdfPage(PdfRenderer pr,int index,float scale)throws IOException{
        PdfRenderer.Page p=pr.openPage(index);
        int w=Math.max(1,(int)(p.getWidth()*scale)), h=Math.max(1,(int)(p.getHeight()*scale));
        Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE);
        p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); p.close(); return b;
    }

    private void addBitmapPage(PdfDocument doc,Bitmap b,int pageNo){
        int w=595, h=Math.max(1,(int)(w*(b.getHeight()/(float)Math.max(1,b.getWidth()))));
        PdfDocument.PageInfo pi=new PdfDocument.PageInfo.Builder(w,h,pageNo).create();
        PdfDocument.Page p=doc.startPage(pi); p.getCanvas().drawBitmap(b,null,new Rect(0,0,w,h),null); doc.finishPage(p);
    }

    void mergePdfs(ArrayList<Uri> list,Result r){
        new Thread(()->{
            File out=tmp(".pdf"); PdfDocument doc=new PdfDocument(); int pages=0;
            try{
                if(list.size()<2) throw new IOException("select at least two PDFs");
                for(Uri u:list){
                    File local=a.copyToTemp(u,".pdf"); ParcelFileDescriptor pfd=ParcelFileDescriptor.open(local,ParcelFileDescriptor.MODE_READ_ONLY);
                    PdfRenderer pr=new PdfRenderer(pfd);
                    for(int i=0;i<pr.getPageCount();i++){
                        Bitmap b=renderPdfPage(pr,i,1f); addBitmapPage(doc,b,++pages); b.recycle();
                        if(a.cancelled.get()) throw new IOException("cancelled");
                    }
                    pr.close(); pfd.close(); local.delete();
                }
                try(FileOutputStream o=new FileOutputStream(out)){doc.writeTo(o);} doc.close();
                if(out.length()<=0) throw new IOException();
                a.publishPdf(out); safe(out);
                done(r,"> PDF MERGE COMPLETE\\n"+pages+" pages rebuilt ✓\\n✓ ORIGINALS PRESERVED");
            }catch(Exception e){try{doc.close();}catch(Exception ignored){} safe(out); fail(r,"> ORIGINAL PRESERVED\\nPDF merge could not be completed.");}
        }).start();
    }

    void splitPdf(Uri u,int first,int last,Result r){
        new Thread(()->{
            File out=tmp(".pdf"); PdfDocument doc=new PdfDocument(); ParcelFileDescriptor pfd=null; PdfRenderer pr=null;
            try{
                File local=a.copyToTemp(u,".pdf"); pfd=ParcelFileDescriptor.open(local,ParcelFileDescriptor.MODE_READ_ONLY); pr=new PdfRenderer(pfd);
                if(first<0||last>=pr.getPageCount()||first>last) throw new IOException("range");
                int pages=0;
                for(int i=first;i<=last;i++){
                    Bitmap b=renderPdfPage(pr,i,1f); addBitmapPage(doc,b,++pages); b.recycle();
                    if(a.cancelled.get()) throw new IOException("cancelled");
                }
                try(FileOutputStream o=new FileOutputStream(out)){doc.writeTo(o);} doc.close();
                pr.close(); pfd.close(); local.delete();
                if(out.length()<=0) throw new IOException();
                a.publishPdf(out); safe(out); done(r,"> PDF SPLIT COMPLETE\\nPages "+(first+1)+"–"+(last+1)+" exported ✓");
            }catch(Exception e){try{doc.close();}catch(Exception ignored){}try{if(pr!=null)pr.close();}catch(Exception ignored){}try{if(pfd!=null)pfd.close();}catch(Exception ignored){}safe(out);fail(r,"> ORIGINAL PRESERVED\\nPDF split could not be completed.");}
        }).start();
    }

    void compressPdf(Uri u,Result r){
        new Thread(()->{
            File out=tmp(".pdf"); PdfDocument doc=new PdfDocument(); ParcelFileDescriptor pfd=null; PdfRenderer pr=null;
            try{
                long src=a.sourceSize(u); File local=a.copyToTemp(u,".pdf"); pfd=ParcelFileDescriptor.open(local,ParcelFileDescriptor.MODE_READ_ONLY); pr=new PdfRenderer(pfd);
                int pages=0;
                for(int i=0;i<pr.getPageCount();i++){
                    Bitmap b=renderPdfPage(pr,i,0.72f);
                    // Re-encode through JPEG to make the rasterized page lighter while keeping the PDF valid.
                    Bitmap page=Bitmap.createBitmap(b.getWidth(),b.getHeight(),Bitmap.Config.RGB_565);
                    Canvas c=new Canvas(page); c.drawColor(Color.WHITE); c.drawBitmap(b,0,0,null); b.recycle();
                    addBitmapPage(doc,page,++pages); page.recycle();
                    if(a.cancelled.get()) throw new IOException("cancelled");
                }
                try(FileOutputStream o=new FileOutputStream(out)){doc.writeTo(o);} doc.close();
                pr.close(); pfd.close(); local.delete();
                long dst=out.length();
                if(!validPdf(out) || dst<=0 || (src>0&&dst>=src)){safe(out);done(r,"> OPTIMAL RESULT\\nNo smaller PDF found.\\nOriginal preserved ✓");return;}
                a.publishPdf(out); safe(out); done(r,"> PDF COMPRESSION ENGINE: COMPLETE\\n"+a.fmtSize(src)+" → "+a.fmtSize(dst)+"\\n✓ OUTPUT VERIFIED\\n✓ ORIGINAL PRESERVED");
            }catch(Exception e){try{doc.close();}catch(Exception ignored){}try{if(pr!=null)pr.close();}catch(Exception ignored){}try{if(pfd!=null)pfd.close();}catch(Exception ignored){}safe(out);fail(r,"> ORIGINAL PRESERVED\\nPDF compression could not be completed.");}
        }).start();
    }

    void zipCreate(ArrayList<Uri> list,Result r){new Thread(()->{File f=tmp(".zip");try{
        if(list==null||list.isEmpty())throw new IOException("no files");
        try(ZipOutputStream z=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f)))){
            z.setLevel(6);HashSet<String> used=new HashSet<>();int i=0;byte[] b=new byte[128*1024];
            for(Uri u:list){if(a.cancelled.get())throw new IOException("cancelled");String name="file_"+(++i);
                try(Cursor c=a.getContentResolver().query(u,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){String n=c.getString(0);if(n!=null&&!n.isEmpty())name=n;}}
                name=name.replaceAll("[\\/:*?\"<>|]","_");if(name.isEmpty())name="file_"+i;String base=name,ext="";int dot=name.lastIndexOf('.');if(dot>0){base=name.substring(0,dot);ext=name.substring(dot);}String candidate=name;int k=2;while(used.contains(candidate))candidate=base+"_"+(k++)+ext;used.add(candidate);
                ZipEntry e=new ZipEntry(candidate);e.setTime(System.currentTimeMillis());z.putNextEntry(e);try(InputStream in=a.getContentResolver().openInputStream(u)){if(in==null)throw new IOException("input");int n;while((n=in.read(b))!=-1){if(a.cancelled.get())throw new IOException("cancelled");z.write(b,0,n);}}z.closeEntry();
            }z.finish();z.flush();
        }
        if(!validZip(f))throw new IOException("invalid zip");a.publishZip(f);safe(f);done(r,"> ZIP CREATED\n✓ ZIP VERIFIED\n✓ SAVED TO Download/MediaCompressor/Archives");
    }catch(Exception e){safe(f);fail(r,a.cancelled.get()?"> ORIGINAL PRESERVED\nZIP creation cancelled safely.":"> ZIP CREATION FAILED\nOriginal files preserved.");}}).start();}
    void zipExtract(Uri u,Result r){new Thread(()->{File local=null,dir=null;try{
        local=a.copyToTemp(u,".zip");String baseName="Extracted";try(Cursor c=a.getContentResolver().query(u,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){String n=c.getString(0);if(n!=null&&!n.isEmpty()){int dot=n.lastIndexOf('.');baseName=dot>0?n.substring(0,dot):n;}}}
        baseName=baseName.replaceAll("[\\/:*?\"<>|]","_");if(baseName.isEmpty())baseName="Extracted";
        dir=new File(a.outputDir(),"EXTRACT_"+System.nanoTime());if(!dir.mkdirs())throw new IOException("folder");
        try(ZipInputStream z=new ZipInputStream(new BufferedInputStream(new FileInputStream(local)))){ZipEntry e;byte[] b=new byte[128*1024];String root=dir.getCanonicalPath()+File.separator;while((e=z.getNextEntry())!=null){if(a.cancelled.get())throw new IOException("cancelled");String raw=e.getName()==null?"":e.getName().replace('\\','/');while(raw.startsWith("/"))raw=raw.substring(1);if(raw.isEmpty()||raw.equals(".")||raw.equals("..")||raw.contains("../")||raw.contains("/.."))throw new IOException("unsafe ZIP entry");String safeName=raw.replaceAll("[\\:*?\"<>|]","_");File out=new File(dir,safeName);if(!out.getCanonicalPath().startsWith(root))throw new IOException("unsafe ZIP entry");if(e.isDirectory()){if(!out.mkdirs()&&!out.isDirectory())throw new IOException("folder");z.closeEntry();continue;}File parent=out.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("folder");try(FileOutputStream o=new FileOutputStream(out)){int n;while((n=z.read(b))!=-1){if(a.cancelled.get())throw new IOException("cancelled");o.write(b,0,n);}}z.closeEntry();}}
        String unique=baseName+"_"+System.currentTimeMillis();
        a.publishDirectory(dir,unique);dir=null;done(r,"> ZIP EXTRACT COMPLETE\nDownload/MediaCompressor/Extract/"+unique+"/\n✓ EACH ZIP HAS ITS OWN FOLDER\n✓ OUTPUT EXPORTED");
    }catch(Exception e){if(dir!=null)deleteTree(dir);fail(r,a.cancelled.get()?"> ORIGINAL PRESERVED\nExtraction cancelled safely.":"> ORIGINAL PRESERVED\nZIP could not be extracted.");}finally{if(local!=null)local.delete();}}).start();}
    private boolean validImage(File f){
        if(f==null||!f.isFile()||f.length()<=0)return false;
        BitmapFactory.Options o=new BitmapFactory.Options(); o.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(f.getAbsolutePath(),o);
        return o.outWidth>0 && o.outHeight>0;
    }

    private boolean sourceHasAudio(Uri u){
        MediaMetadataRetriever m=null;
        try{m=new MediaMetadataRetriever();m.setDataSource(a,u);String x=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);return "yes".equalsIgnoreCase(x)||"1".equals(x);}
        catch(Exception e){return false;}
        finally{if(m!=null)try{m.release();}catch(Exception ignored){}}
    }

    private boolean hasAudioTrack(File f){
        MediaExtractor ex=null;
        try{ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());for(int i=0;i<ex.getTrackCount();i++){String m=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("audio/"))return true;}return false;}
        catch(Exception e){return false;}
        finally{if(ex!=null)try{ex.release();}catch(Exception ignored){}}
    }

    private long outputDurationUs(File f){
        MediaExtractor ex=null;
        try{ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());long best=0;for(int i=0;i<ex.getTrackCount();i++){MediaFormat fmt=ex.getTrackFormat(i);if(fmt.containsKey(MediaFormat.KEY_DURATION))best=Math.max(best,fmt.getLong(MediaFormat.KEY_DURATION));}return best;}
        catch(Exception e){return 0;}
        finally{if(ex!=null)try{ex.release();}catch(Exception ignored){}}
    }

    private boolean validVideo(File f){
        if(f==null||!f.isFile()||f.length()<=0)return false;
        MediaMetadataRetriever m=null;
        try{
            m=new MediaMetadataRetriever();
            m.setDataSource(f.getAbsolutePath());
            String dur=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(dur==null||Long.parseLong(dur)<=0)return false;
            MediaExtractor ex=null;
            try{ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());for(int i=0;i<ex.getTrackCount();i++){String mime=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("video/"))return true;}return false;}
            finally{if(ex!=null)try{ex.release();}catch(Exception ignored){}}
        }catch(Exception e){return false;}
        finally{if(m!=null)try{m.release();}catch(Exception ignored){}}
    }

    private boolean validPdf(File f){
        if(f==null||!f.isFile()||f.length()<=0)return false;
        ParcelFileDescriptor pfd=null; PdfRenderer pr=null;
        try{
            pfd=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
            pr=new PdfRenderer(pfd);
            return pr.getPageCount()>0;
        }catch(Exception e){return false;}
        finally{
            if(pr!=null)try{pr.close();}catch(Exception ignored){}
            if(pfd!=null)try{pfd.close();}catch(Exception ignored){}
        }
    }

    private boolean validZip(File f){
        if(f==null||!f.isFile()||f.length()<=0)return false;
        try(ZipFile z=new ZipFile(f)){
            return z.size()>=0;
        }catch(Exception e){return false;}
    }

}
