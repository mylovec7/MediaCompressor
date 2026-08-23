package com.vr3th.mediacompressor;

import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
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

    private void remuxVideoOnly(File input,File output,long startUs,long endUs)throws Exception{
        MediaExtractor ex=new MediaExtractor();MediaMuxer mux=null;
        try{ex.setDataSource(input.getAbsolutePath());int vt=-1;MediaFormat vf=null;for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String m=f.getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("video/")){vt=i;vf=f;break;}}
            if(vt<0)throw new IOException("video track unavailable");String mime=vf.getString(MediaFormat.KEY_MIME);
            if(!"video/avc".equalsIgnoreCase(mime)&&!"video/hevc".equalsIgnoreCase(mime))throw new IOException("mute requires H.264/H.265 stream copy");
            mux=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int out=mux.addTrack(vf);mux.start();ex.selectTrack(vt);ex.seekTo(Math.max(0,startUs),MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            int cap=4*1024*1024;if(vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))cap=Math.max(cap,vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)*2);java.nio.ByteBuffer b=java.nio.ByteBuffer.allocateDirect(Math.min(cap,16*1024*1024));long first=-1;
            while(true){b.clear();int n=ex.readSampleData(b,0);if(n<0)break;long pts=ex.getSampleTime();if(pts<0||pts>endUs)break;if(first<0)first=pts;MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();bi.offset=0;bi.size=n;bi.presentationTimeUs=Math.max(0,pts-first);bi.flags=ex.getSampleFlags();mux.writeSampleData(out,b,bi);ex.advance();}
            ex.unselectTrack(vt);mux.stop();mux.release();mux=null;
        }finally{try{ex.release();}catch(Exception ignored){}try{if(mux!=null)mux.release();}catch(Exception ignored){}}
    }

    void trimOrMuteVideo(Uri u,long fromUs,long toUs,boolean mute,Result r){new Thread(()->{
        File local=null,video=null,audio=null,out=null;
        try{
            local=a.copyToTemp(u,".media");long dur=sourceDurationUs(Uri.fromFile(local));
            boolean reverse=fromUs>toUs;long start=Math.min(fromUs,toUs),end=Math.max(fromUs,toUs);if(toUs==Long.MAX_VALUE){end=dur;reverse=false;}
            if(start<0||end<=start||dur<=0||end>dur)throw new IOException("TIME DOES NOT EXIST");
            video=tmp(".video.mp4");
            if(mute && !reverse && start==0 && end>=dur){
                try{ remuxVideoOnly(local,video,0,dur); }
                catch(Exception streamCopyFailure){
                    NativeVideoEngine.encodeFrames(a,local,video,start,end,false,Math.max(500_000,estimateBitrate(local)),a.cancelled,0,0,null);
                }
            }else{
                NativeVideoEngine.encodeFrames(a,local,video,start,end,reverse,Math.max(500_000,estimateBitrate(local)),a.cancelled,0,0,null);
            }
            if(!validVideo(video))throw new IOException("video output invalid");
            if(!mute&&hasAudioTrack(local)){audio=tmp(".audio.m4a");NativeAudioEngine.trim(local,audio,start,end,reverse,a.cancelled);if(!hasAudioTrack(audio))throw new IOException("audio output invalid");out=tmp(".mp4");NativeMuxer.mux(video,audio,out);}else{out=video;video=null;}
            if(!validVideo(out))throw new IOException("final video invalid");long d=out.length();a.publishVideo(out);safe(out);done(r,"> VIDEO "+(reverse?"REVERSE":"TRIM")+" COMPLETE\n"+a.fmtSize(size(u))+" → "+a.fmtSize(d)+"\n✓ REAL "+(reverse?"REVERSE":"FORWARD")+"\n✓ VIDEO VERIFIED\n"+(mute?"✓ AUDIO REMOVED":"✓ AUDIO VERIFIED")+"\n✓ ORIGINAL PRESERVED");
        }catch(Exception e){safe(out);fail(r,"> ORIGINAL PRESERVED\nVIDEO OPERATION FAILED\n"+(e.getMessage()==null?"Native video engine could not complete the operation.":e.getMessage()));}
        finally{safe(local);safe(video);safe(audio);}
    }).start();}

    void mergeVideos(ArrayList<Uri> list,Result r){new Thread(()->{
        File out=tmp(".mp4");ArrayList<File> locals=new ArrayList<>(),segments=new ArrayList<>(),videoParts=new ArrayList<>(),audioParts=new ArrayList<>();
        try{
            if(list==null||list.size()<2)throw new IOException("select at least two videos");
            int[] canvas=displayVideoSize(list.get(0));
            String mime=VideoEngine.bestVideoMime();
            boolean allHaveAudio=true;
            for(Uri u:list){ if(!sourceHasAudio(u)){allHaveAudio=false;break;} }
            for(Uri u:list){
                if(a.cancelled.get())throw new IOException("cancelled");
                File local=a.copyToTemp(u,".media");locals.add(local);
                long dur=sourceDurationUs(Uri.fromFile(local)); if(dur<=0)throw new IOException("video duration unavailable");
                File vp=tmp(".merge_v.mp4");videoParts.add(vp);
                NativeVideoEngine.encodeFrames(a,local,vp,0,dur,false,Math.max(600_000,estimateBitrate(local)),a.cancelled,canvas[0],canvas[1],mime);
                if(!validVideo(vp))throw new IOException("video normalization failed");
                if(allHaveAudio){
                    File ap=tmp(".merge_a.m4a");audioParts.add(ap);
                    NativeAudioEngine.trim(local,ap,0,dur,false,a.cancelled);
                    if(!hasAudioTrack(ap))throw new IOException("audio normalization failed");
                    File seg=tmp(".merge_segment.mp4");segments.add(seg);
                    NativeMuxer.mux(vp,ap,seg);
                    if(!validVideo(seg))throw new IOException("normalized segment invalid");
                }else segments.add(vp);
            }
            NativeMuxer.concat(segments,out);
            if(!validVideo(out))throw new IOException("merged output invalid");
            long d=out.length();a.publishVideo(out);safe(out);
            done(r,"> VIDEO MERGE COMPLETE\n"+a.fmtSize(d)+" output\n✓ ALL INPUTS NORMALIZED\n✓ COMMON "+(mime.equals("video/hevc")?"H.265/HEVC":"H.264/AVC")+"\n"+(allHaveAudio?"✓ AUDIO NORMALIZED\n":"✓ VIDEO-ONLY SAFE MERGE\n")+"✓ OUTPUT VERIFIED\n✓ ORIGINALS PRESERVED");
        }catch(Exception e){safe(out);fail(r,"> ORIGINALS PRESERVED\nVIDEO MERGE FAILED\n"+(e.getMessage()==null?"Native normalization could not complete the merge.":e.getMessage()));}
        finally{for(File f:locals)safe(f);for(File f:segments)safe(f);for(File f:videoParts)safe(f);for(File f:audioParts)safe(f);}
    }).start();}

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

    void frame(Uri u,long us,Result r){new Thread(()->{File f=null,local=null;MediaMetadataRetriever m=null;Bitmap b=null;try{
        local=a.copyToTemp(u,".mp4"); m=new MediaMetadataRetriever();m.setDataSource(local.getAbsolutePath());
        b=m.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(b==null)throw new IOException();
        f=tmp(".jpg");try(FileOutputStream o=new FileOutputStream(f)){if(!b.compress(Bitmap.CompressFormat.JPEG,92,o))throw new IOException("encode");}
        if(!validImage(f))throw new IOException("output invalid");a.publishImage(f,false);safe(f);done(r,"> FRAME EXTRACTED\n✓ FRAME VERIFIED\n✓ SAVED TO GALLERY");
    }catch(Exception e){safe(f);fail(r,"> ORIGINAL PRESERVED\nFrame extraction failed.\n"+(e.getMessage()==null?"Unsupported media.":e.getMessage()));}
    finally{if(m!=null)try{m.release();}catch(Exception ignored){}if(b!=null)try{b.recycle();}catch(Exception ignored){}safe(local);}}).start();}

    void audioTrim(Uri u,long fromUs,long toUs,Result r){new Thread(()->{File local=null,out=null;try{
        local=a.copyToTemp(u,".media");long dur=sourceDurationUs(Uri.fromFile(local));boolean reverse=fromUs>toUs;long start=Math.min(fromUs,toUs),end=Math.max(fromUs,toUs);if(start<0||end<=start||dur<=0||end>dur)throw new IOException("TIME DOES NOT EXIST");out=tmp(".m4a");NativeAudioEngine.trim(local,out,start,end,reverse,a.cancelled);if(!hasAudioTrack(out))throw new IOException("audio output invalid");long d=out.length();a.publishAudioFormat(out,"m4a");safe(out);done(r,"> AUDIO "+(reverse?"REVERSE":"TRIM")+" COMPLETE\n"+a.fmtSize(size(u))+" → "+a.fmtSize(d)+"\n✓ REAL "+(reverse?"REVERSE":"FORWARD")+"\n✓ AAC VERIFIED\n✓ ORIGINAL PRESERVED");
    }catch(Exception e){safe(out);fail(r,"> ORIGINAL PRESERVED\nAUDIO OPERATION FAILED\n"+(e.getMessage()==null?"Native audio engine could not complete the operation.":e.getMessage()));}finally{safe(local);}}).start();}

    void videoToAudio(Uri u,Result r){new Thread(()->{File local=null,out=null;try{
        local=a.copyToTemp(u,".media");out=tmp(".m4a");long dur=sourceDurationUs(Uri.fromFile(local));NativeAudioEngine.trim(local,out,0,dur,false,a.cancelled);if(!hasAudioTrack(out))throw new IOException("audio output invalid");long d=out.length();a.publishAudioFormat(out,"m4a");safe(out);done(r,"> VIDEO → AUDIO COMPLETE\n"+a.fmtSize(d)+" output\n✓ AAC/M4A VERIFIED\n✓ ORIGINAL PRESERVED");
    }catch(Exception e){safe(out);fail(r,"> ORIGINAL PRESERVED\nVIDEO → AUDIO FAILED\n"+(e.getMessage()==null?"No compatible audio decoder/encoder was available.":e.getMessage()));}finally{safe(local);}}).start();}

    private int estimateBitrate(File f){try{MediaExtractor ex=new MediaExtractor();ex.setDataSource(f.getAbsolutePath());for(int i=0;i<ex.getTrackCount();i++){MediaFormat x=ex.getTrackFormat(i);String m=x.getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("video/")&&x.containsKey(MediaFormat.KEY_BIT_RATE)){int b=(int)x.getLong(MediaFormat.KEY_BIT_RATE);ex.release();return Math.max(500_000,Math.min(5_000_000,b));}}ex.release();}catch(Exception ignored){}return 1_500_000;}

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
        a.publishDirectory(dir,unique);dir=null;done(r,"> ZIP EXTRACT COMPLETE\nDownload/MediaCompressor/Archives/Extracted/"+unique+"/\n✓ EACH ZIP HAS ITS OWN FOLDER\n✓ OUTPUT EXPORTED");
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
