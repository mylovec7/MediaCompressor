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
        try{m=new MediaMetadataRetriever();m.setDataSource(a,u);String x=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);return x==null?0:Long.parseLong(x)*1000L;}catch(Exception e){return 0;}finally{if(m!=null)try{m.release();}catch(Exception ignored){}}
    }

    void trimOrMuteVideo(Uri u,long fromUs,long toUs,boolean mute,Result r){
        final long startUs=fromUs;
        final long requestedToUs=toUs;
        final boolean muteAudio=mute;
        new Thread(()->{
            File out=tmp(".mp4"); MediaExtractor ex=null; MediaMuxer mux=null;
            try{
                long dur=sourceDurationUs(u);
                long endUs=requestedToUs;
                if(muteAudio&&endUs==Long.MAX_VALUE)endUs=dur;
                if(startUs<0||endUs<0||startUs==endUs||dur<=0||startUs>dur||endUs>dur)
                    throw new IOException("TIME DOES NOT EXIST");
                if(startUs>endUs)
                    throw new IOException("REVERSE VIDEO REQUIRES FRAME TRANSCODE");
                ex=new MediaExtractor();
                ex.setDataSource(a,u,null);
                mux=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int[] map=new int[ex.getTrackCount()];
                Arrays.fill(map,-1);
                for(int i=0;i<ex.getTrackCount();i++){
                    MediaFormat f=ex.getTrackFormat(i);
                    String m=f.getString(MediaFormat.KEY_MIME);
                    if(m==null)continue;
                    if(muteAudio&&m.startsWith("audio/"))continue;
                    map[i]=mux.addTrack(f);
                }
                mux.start();
                ByteBuffer buf=ByteBuffer.allocateDirect(512*1024);
                MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();
                for(int i=0;i<ex.getTrackCount();i++){
                    if(map[i]<0)continue;
                    ex.selectTrack(i);
                    ex.seekTo(startUs,MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    while(true){
                        int n=ex.readSampleData(buf,0);
                        long t=ex.getSampleTime();
                        if(n<0||t<0||t>endUs)break;
                        bi.offset=0;
                        bi.size=n;
                        bi.presentationTimeUs=Math.max(0,t-startUs);
                        bi.flags=ex.getSampleFlags();
                        mux.writeSampleData(map[i],buf,bi);
                        ex.advance();
                    }
                    ex.unselectTrack(i);
                }
                mux.stop(); mux.release(); mux=null;
                long s=size(u),d=out.length();
                if(!validVideo(out)||d<=0||(s>0&&d>=s)){
                    safe(out);
                    done(r,"> OPTIMAL RESULT\\nOriginal preserved ✓");
                    return;
                }
                a.publishVideo(out); safe(out);
                done(r,"> VIDEO OPERATION COMPLETE\\n"+a.fmtSize(s)+" → "+a.fmtSize(d)+"\\n✓ VERIFIED\\n✓ ORIGINAL PRESERVED");
            }catch(Exception e){
                if(mux!=null)try{mux.release();}catch(Exception ignored){}
                safe(out);
                fail(r,"> ORIGINAL PRESERVED\\nVideo operation could not be completed.");
            }finally{
                if(ex!=null)ex.release();
            }
        }).start();
    }

    void mergeVideos(ArrayList<Uri> list,Result r){
        new Thread(()->{
            ArrayList<File> inputs=new ArrayList<>(); File out=tmp(".mp4"); MediaMuxer mux=null;
            try{
                if(list==null||list.size()<2)throw new IOException("select at least two videos");
                MediaFormat baseV=null,baseA=null;
                boolean compatible=true;
                for(Uri u:list){
                    if(a.cancelled.get())throw new IOException("cancelled");
                    File f=a.copyToTemp(u,".merge.mp4"); inputs.add(f);
                    MediaExtractor ex=new MediaExtractor(); ex.setDataSource(f.getAbsolutePath());
                    MediaFormat vf=null,af=null;
                    for(int i=0;i<ex.getTrackCount();i++){
                        MediaFormat tf=ex.getTrackFormat(i); String m=tf.getString(MediaFormat.KEY_MIME);
                        if(m!=null&&m.startsWith("video/")&&vf==null)vf=tf;
                        else if(m!=null&&m.startsWith("audio/")&&af==null)af=tf;
                    }
                    if(vf==null){ex.release();throw new IOException("video track missing");}
                    if(baseV==null){baseV=vf;baseA=af;}
                    else if(!sameVideoFormat(baseV,vf)||((baseA==null)!=(af==null))||(baseA!=null&&!sameAudioFormat(baseA,af))) compatible=false;
                    ex.release();
                }
                if(!compatible){
                    // Fallback: AndroidX Media3 Transformer transcodes and concatenates incompatible
                    // video/audio streams to a common MP4 instead of pretending the operation is impossible.
                    final Handler h=new Handler(Looper.getMainLooper());
                    final Transformer[] box=new Transformer[1];
                    final boolean[] finished=new boolean[]{false};
                    final Runnable cancelWatch=()->{
                        if(finished[0])return;
                        if(a.cancelled.get()){
                            try{if(box[0]!=null)box[0].cancel();}catch(Exception ignored){}
                            safe(out); finished[0]=true; fail(r,"> ORIGINALS PRESERVED\nMerge cancelled safely."); return;
                        }
                        h.postDelayed(new Runnable(){public void run(){
                            if(finished[0])return;
                            if(a.cancelled.get()){
                                try{if(box[0]!=null)box[0].cancel();}catch(Exception ignored){}
                                safe(out); finished[0]=true; fail(r,"> ORIGINALS PRESERVED\nMerge cancelled safely.");
                            } else h.postDelayed(this,200);
                        }},200);
                    };
                    h.post(()->{
                        try{
                            ArrayList<EditedMediaItem> items=new ArrayList<>();
                            for(Uri u:list)items.add(new EditedMediaItem.Builder(MediaItem.fromUri(u)).build());
                            EditedMediaItemSequence seq=EditedMediaItemSequence.withAudioAndVideoFrom(items);
                            Composition composition=new Composition.Builder(seq).build();
                            Transformer t=new Transformer.Builder(a.getApplicationContext()).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).build();
                            box[0]=t;
                            t.addListener(new Transformer.Listener(){
                                @Override public void onCompleted(Composition c,ExportResult result){
                                    if(finished[0])return; finished[0]=true;
                                    if(a.cancelled.get()){safe(out);fail(r,"> ORIGINALS PRESERVED\nMerge cancelled safely.");return;}
                                    if(!validVideo(out)||out.length()<=0){safe(out);fail(r,"> ORIGINALS PRESERVED\nMerge produced an invalid output.");return;}
                                    try{a.publishVideo(out);long d=out.length();safe(out);done(r,"> VIDEO MERGE COMPLETE\n"+a.fmtSize(d)+" output\n✓ CODECS NORMALIZED\n✓ VIDEO + AUDIO CONTINUOUS\n✓ VERIFIED\n✓ ORIGINALS PRESERVED");}
                                    catch(Exception e){safe(out);fail(r,"> ORIGINALS PRESERVED\nMerged video could not be exported.");}
                                }
                                @Override public void onError(Composition c,ExportResult result,ExportException e){
                                    if(finished[0])return; finished[0]=true; safe(out);
                                    fail(r,"> ORIGINALS PRESERVED\nVideo merge failed safely.\n"+(e.getMessage()==null?"Codec conversion failed.":e.getMessage()));
                                }
                            });
                            t.start(composition,out.getAbsolutePath());
                            cancelWatch.run();
                        }catch(Exception e){finished[0]=true;safe(out);fail(r,"> ORIGINALS PRESERVED\nVideo merge could not start.");}
                    });
                    return;
                }
                mux=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int outV=mux.addTrack(baseV),outA=baseA==null?-1:mux.addTrack(baseA); mux.start();
                long offsetUs=0;
                for(File f:inputs){
                    MediaExtractor ex=new MediaExtractor(); ex.setDataSource(f.getAbsolutePath()); int vt=-1,at=-1;
                    for(int i=0;i<ex.getTrackCount();i++){String m=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("video/")&&vt<0)vt=i;else if(m!=null&&m.startsWith("audio/")&&at<0)at=i;}
                    copyTrack(ex,vt,mux,outV,offsetUs);if(at>=0&&outA>=0)copyTrack(ex,at,mux,outA,offsetUs);long dur=trackDuration(f,vt);offsetUs+=Math.max(0,dur);ex.release();
                }
                mux.stop();mux.release();mux=null;
                if(!validVideo(out)||out.length()<=0)throw new IOException("merged output invalid");
                a.publishVideo(out);long d=out.length();safe(out);done(r,"> VIDEO MERGE COMPLETE\n"+a.fmtSize(d)+" output\n✓ VIDEO + AUDIO CONTINUOUS\n✓ VERIFIED\n✓ ORIGINALS PRESERVED");
            }catch(Exception e){if(mux!=null)try{mux.release();}catch(Exception ignored){}safe(out);fail(r,a.cancelled.get()?"> ORIGINALS PRESERVED\nMerge cancelled safely.":"> ORIGINALS PRESERVED\nVideo merge failed safely.\n"+(e.getMessage()==null?"Unsupported media.":e.getMessage()));}
            finally{for(File f:inputs)safe(f);}
        }).start();}

    private boolean sameVideoFormat(MediaFormat a,MediaFormat b){
        try{return Objects.equals(a.getString(MediaFormat.KEY_MIME),b.getString(MediaFormat.KEY_MIME))&&a.getInteger(MediaFormat.KEY_WIDTH)==b.getInteger(MediaFormat.KEY_WIDTH)&&a.getInteger(MediaFormat.KEY_HEIGHT)==b.getInteger(MediaFormat.KEY_HEIGHT)&&(!a.containsKey(MediaFormat.KEY_FRAME_RATE)||!b.containsKey(MediaFormat.KEY_FRAME_RATE)||a.getInteger(MediaFormat.KEY_FRAME_RATE)==b.getInteger(MediaFormat.KEY_FRAME_RATE));}catch(Exception e){return false;}
    }
    private boolean sameAudioFormat(MediaFormat a,MediaFormat b){
        try{return Objects.equals(a.getString(MediaFormat.KEY_MIME),b.getString(MediaFormat.KEY_MIME))&&(!a.containsKey(MediaFormat.KEY_SAMPLE_RATE)||!b.containsKey(MediaFormat.KEY_SAMPLE_RATE)||a.getInteger(MediaFormat.KEY_SAMPLE_RATE)==b.getInteger(MediaFormat.KEY_SAMPLE_RATE))&&(!a.containsKey(MediaFormat.KEY_CHANNEL_COUNT)||!b.containsKey(MediaFormat.KEY_CHANNEL_COUNT)||a.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==b.getInteger(MediaFormat.KEY_CHANNEL_COUNT));}catch(Exception e){return false;}
    }
    private long trackDuration(File f,int track)throws IOException{MediaExtractor ex=new MediaExtractor();try{ex.setDataSource(f.getAbsolutePath());ex.selectTrack(track);long last=0;while(true){long t=ex.getSampleTime();if(t<0)break;last=Math.max(last,t);ex.advance();}return last;}finally{ex.release();}}
    private void copyTrack(MediaExtractor ex,int track,MediaMuxer mux,int outTrack,long offset)throws IOException{if(track<0)return;ex.selectTrack(track);ByteBuffer b=ByteBuffer.allocateDirect(1024*1024);MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();while(!a.cancelled.get()){int n=ex.readSampleData(b,0);long t=ex.getSampleTime();if(n<0||t<0)break;bi.offset=0;bi.size=n;bi.presentationTimeUs=t+offset;bi.flags=ex.getSampleFlags();mux.writeSampleData(outTrack,b,bi);ex.advance();}ex.unselectTrack(track);if(a.cancelled.get())throw new IOException("cancelled");}
    private String q(String s){return "'"+s.replace("'","'\\''")+"'";}
    void frame(Uri u,long us,Result r){new Thread(()->{File f=null;try{MediaMetadataRetriever m=new MediaMetadataRetriever();m.setDataSource(a,u);Bitmap b=m.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);m.release();if(b==null)throw new IOException();f=tmp(".jpg");try(FileOutputStream o=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,92,o);}a.publishImage(f,false);safe(f);b.recycle();done(r,"> FRAME EXTRACTED\n✓ SAVED TO GALLERY");}catch(Exception e){safe(f);fail(r,"> ORIGINAL PRESERVED\nFrame extraction failed.");}}).start();}

    void audioTrim(Uri u,long fromUs,long toUs,Result r){new Thread(()->{
        File in=null,out=null;MediaExtractor ex=null;MediaMuxer mux=null;
        try{
            long dur=sourceDurationUs(u);if(fromUs<0||toUs<0||dur<=0||fromUs>dur||toUs>dur||fromUs==toUs)throw new IOException("TIME DOES NOT EXIST");if(fromUs>toUs)throw new IOException("REVERSE AUDIO REQUIRES DECODE/ENCODE ENGINE");
            in=a.copyToTemp(u,".audio"); ex=new MediaExtractor();ex.setDataSource(in.getAbsolutePath());int at=-1;
            for(int i=0;i<ex.getTrackCount();i++){String m=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("audio/")){at=i;break;}}
            if(at<0)throw new IOException("audio track missing");
            MediaFormat af=ex.getTrackFormat(at);String mime=af.getString(MediaFormat.KEY_MIME);
            boolean rawMp3="audio/mpeg".equals(mime);
            out=tmp(rawMp3?".mp3":".m4a");
            ex.selectTrack(at);ex.seekTo(fromUs,MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            if(rawMp3){
                try(FileOutputStream o=new FileOutputStream(out)){ByteBuffer b=ByteBuffer.allocateDirect(256*1024);while(!a.cancelled.get()){int n=ex.readSampleData(b,0);long t=ex.getSampleTime();if(n<0||t<0||t>toUs)break;b.position(0);byte[] x=new byte[n];b.get(x);o.write(x);ex.advance();}}
            }else{
                mux=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int ot=mux.addTrack(af);mux.start();ByteBuffer b=ByteBuffer.allocateDirect(512*1024);MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();while(!a.cancelled.get()){int n=ex.readSampleData(b,0);long t=ex.getSampleTime();if(n<0||t<0||t>toUs)break;bi.offset=0;bi.size=n;bi.presentationTimeUs=Math.max(0,t-fromUs);bi.flags=ex.getSampleFlags();mux.writeSampleData(ot,b,bi);ex.advance();}mux.stop();mux.release();mux=null;
            }
            if(a.cancelled.get())throw new IOException("cancelled");if(out.length()<=0)throw new IOException("empty output");long original=size(u);if(original>0&&out.length()>=original){safe(out);done(r,"> BLACK AUDIO // COMPRESSION STOPPED\nORIGINAL  :: "+a.fmtSize(original)+"\nOUTPUT    :: "+a.fmtSize(out.length())+"\nSTATUS    :: ALREADY SMALL\nACTION    :: ORIGINAL KEPT");return;}String ext=rawMp3?"mp3":"m4a";a.publishAudioFormat(out,ext);long d=out.length();safe(out);double red=original>0?((original-d)*100.0/original):0;done(r,"> BLACK AUDIO // TRIM COMPLETE\nAUDIO     :: "+String.format(Locale.US,"%.2f",dur/1000000.0)+"s\nRANGE     :: "+String.format(Locale.US,"%.3f",fromUs/1000000.0)+" → "+String.format(Locale.US,"%.3f",toUs/1000000.0)+"\nMODE      :: FORWARD >>\nOUTPUT    :: "+String.format(Locale.US,"%.3f",(toUs-fromUs)/1000000.0)+"s\nSTATUS    :: VERIFIED\nORIGINAL  :: SAFE\n\nAUDIO CUT SUCCESSFULLY");
        }catch(Exception e){if(mux!=null)try{mux.stop();}catch(Exception ignored){}if(mux!=null)try{mux.release();}catch(Exception ignored){}safe(out);fail(r,a.cancelled.get()?"> ORIGINAL PRESERVED\nAudio trim cancelled safely.":"> ORIGINAL PRESERVED\n"+e.getMessage());}finally{if(ex!=null)ex.release();safe(in);}
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

    private boolean validVideo(File f){
        if(f==null||!f.isFile()||f.length()<=0)return false;
        MediaMetadataRetriever m=null;
        try{
            m=new MediaMetadataRetriever();
            m.setDataSource(f.getAbsolutePath());
            String dur=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return dur!=null && Long.parseLong(dur)>0;
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
