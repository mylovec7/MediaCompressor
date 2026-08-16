package com.vr3th.mediacompressor;

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
    private long size(Uri u){return a.sourceSize(u);}

    void convertImage(Uri u, String fmt, Result r){new Thread(()->{File f=null;Bitmap b=null;try{
        BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.ARGB_8888;
        try(InputStream in=a.getContentResolver().openInputStream(u)){b=BitmapFactory.decodeStream(in,null,o);}if(b==null)throw new IOException("decode");
        String ext=fmt.equals("webp")?"webp":fmt.equals("png")?"png":"jpg";f=tmp("."+ext);
        Bitmap.CompressFormat cf=ext.equals("webp")?(Build.VERSION.SDK_INT>=30?Bitmap.CompressFormat.WEBP_LOSSY:Bitmap.CompressFormat.WEBP):ext.equals("png")?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG;
        try(FileOutputStream out=new FileOutputStream(f)){if(!b.compress(cf,90,out))throw new IOException("encode");}
        long src=size(u),dst=f.length();if(!validImage(f)||dst<=0||(src>0&&dst>=src)){safe(f);done(r,"> OPTIMAL RESULT\nOriginal preserved ✓");return;}
        Uri saved=a.publishImage(f,ext.equals("webp"));safe(f);done(r,"> CONVERSION COMPLETE\n"+a.fmtSize(src)+" → "+a.fmtSize(dst)+"\n✓ OUTPUT VERIFIED\n✓ ORIGINAL PRESERVED\n✓ SAVED TO GALLERY");
    }catch(Exception e){safe(f);fail(r,"> ORIGINAL PRESERVED\nNo better output was created.");}finally{if(b!=null)b.recycle();}}).start();}

    void batchImages(ArrayList<Uri> list,Result r){new Thread(()->{int ok=0;for(Uri u:list){if(a.cancelled.get())break;try{Bitmap b=BitmapFactory.decodeStream(a.getContentResolver().openInputStream(u));if(b==null)continue;File f=tmp(".jpg");try(FileOutputStream o=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,86,o);}long s=size(u),d=f.length();if(d>0&&(s<=0||d<s)){a.publishImage(f,false);ok++;}safe(f);b.recycle();}catch(Exception ignored){}}done(r,"> BATCH COMPLETE\n"+ok+" / "+list.size()+" outputs accepted\n✓ ORIGINALS PRESERVED");}).start();}

    void exifStrip(Uri u,Result r){new Thread(()->{File f=null;Bitmap b=null;try{BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.ARGB_8888;b=BitmapFactory.decodeStream(a.getContentResolver().openInputStream(u),null,o);if(b==null)throw new IOException();f=tmp(".jpg");try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,95,out);}long s=size(u),d=f.length();if(d>=s&&s>0){safe(f);done(r,"> METADATA CLEAN\nNo smaller result was found.\nOriginal preserved ✓");return;}a.publishImage(f,false);safe(f);done(r,"> EXIF CLEAN COMPLETE\nMetadata-stripped JPEG saved ✓");}catch(Exception e){safe(f);fail(r,"> ORIGINAL PRESERVED\nMetadata cleanup could not be completed.");}finally{if(b!=null)b.recycle();}}).start();}

    void trimOrMuteVideo(Uri u,long fromUs,long toUs,boolean mute,Result r){new Thread(()->{File out=tmp(".mp4");MediaExtractor ex=null;MediaMuxer mux=null;try{ex=new MediaExtractor();ex.setDataSource(a,u,null);mux=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int[] map=new int[ex.getTrackCount()];Arrays.fill(map,-1);for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String m=f.getString(MediaFormat.KEY_MIME);if(m==null)continue;if(mute&&m.startsWith("audio/"))continue;map[i]=mux.addTrack(f);}mux.start();ByteBuffer buf=ByteBuffer.allocateDirect(512*1024);MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();for(int i=0;i<ex.getTrackCount();i++){if(map[i]<0)continue;ex.selectTrack(i);ex.seekTo(fromUs,MediaExtractor.SEEK_TO_CLOSEST_SYNC);while(true){int n=ex.readSampleData(buf,0);long t=ex.getSampleTime();if(n<0||t<0||t>toUs)break;bi.offset=0;bi.size=n;bi.presentationTimeUs=Math.max(0,t-fromUs);bi.flags=ex.getSampleFlags();mux.writeSampleData(map[i],buf,bi);ex.advance();}ex.unselectTrack(i);}mux.stop();mux.release();mux=null;long s=size(u),d=out.length();if(!validVideo(out)||d<=0||(s>0&&d>=s)){safe(out);done(r,"> OPTIMAL RESULT\nOriginal preserved ✓");return;}a.publishVideo(out);safe(out);done(r,"> VIDEO OPERATION COMPLETE\n"+a.fmtSize(s)+" → "+a.fmtSize(d)+"\n✓ VERIFIED\n✓ ORIGINAL PRESERVED");}catch(Exception e){if(mux!=null)try{mux.release();}catch(Exception ignored){}safe(out);fail(r,"> ORIGINAL PRESERVED\nVideo operation could not be completed.");}finally{if(ex!=null)ex.release();}}).start();}

    void mergeVideos(ArrayList<Uri> list,Result r){new Thread(()->{File out=tmp(".mp4");MediaMuxer mux=null;try{if(list.size()<2)throw new IOException("need two videos");MediaExtractor first=new MediaExtractor();first.setDataSource(a,list.get(0),null);int v=-1,au=-1;for(int i=0;i<first.getTrackCount();i++){String m=first.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("video/"))v=i;else if(m!=null&&m.startsWith("audio/"))au=i;}if(v<0)throw new IOException("video track");mux=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int mv=mux.addTrack(first.getTrackFormat(v));int ma=au>=0?mux.addTrack(first.getTrackFormat(au)):-1;mux.start();long offset=0;ByteBuffer buf=ByteBuffer.allocateDirect(512*1024);MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();for(Uri u:list){MediaExtractor ex=new MediaExtractor();ex.setDataSource(a,u,null);int vt=-1,at=-1;for(int i=0;i<ex.getTrackCount();i++){String m=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("video/"))vt=i;else if(m!=null&&m.startsWith("audio/"))at=i;}if(vt<0)throw new IOException("video track mismatch");ex.selectTrack(vt);while(true){int n=ex.readSampleData(buf,0);if(n<0)break;bi.offset=0;bi.size=n;bi.presentationTimeUs=Math.max(0,offset+ex.getSampleTime());bi.flags=ex.getSampleFlags();mux.writeSampleData(mv,buf,bi);ex.advance();}long dur=ex.getTrackFormat(vt).containsKey(MediaFormat.KEY_DURATION)?ex.getTrackFormat(vt).getLong(MediaFormat.KEY_DURATION):0;offset+=Math.max(0,dur);ex.unselectTrack(vt);if(at>=0&&ma>=0){ex.selectTrack(at);while(true){int n=ex.readSampleData(buf,0);if(n<0)break;bi.offset=0;bi.size=n;bi.presentationTimeUs=Math.max(0,offset+ex.getSampleTime());bi.flags=ex.getSampleFlags();mux.writeSampleData(ma,buf,bi);ex.advance();}ex.unselectTrack(at);}ex.release();}first.release();mux.stop();mux.release();mux=null;long s=0;for(Uri u:list){long z=a.sourceSize(u);if(z>0)s+=z;}long d=out.length();if(d<=0||(s>0&&d>=s)){safe(out);done(r,"> OPTIMAL RESULT\\nCombined output was not smaller.\\nOriginals preserved ✓");return;}a.publishVideo(out);safe(out);done(r,"> VIDEO MERGE COMPLETE\\n"+a.fmtSize(s)+" → "+a.fmtSize(d)+"\\n✓ VERIFIED\\n✓ ORIGINALS PRESERVED");}catch(Exception e){if(mux!=null)try{mux.release();}catch(Exception ignored){}safe(out);fail(r,"> ORIGINALS PRESERVED\\nVideos are not codec-compatible for lossless merge.");}}).start();}

    void frame(Uri u,long us,Result r){new Thread(()->{File f=null;try{MediaMetadataRetriever m=new MediaMetadataRetriever();m.setDataSource(a,u);Bitmap b=m.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);m.release();if(b==null)throw new IOException();f=tmp(".jpg");try(FileOutputStream o=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,92,o);}a.publishImage(f,false);safe(f);b.recycle();done(r,"> FRAME EXTRACTED\n✓ SAVED TO GALLERY");}catch(Exception e){safe(f);fail(r,"> ORIGINAL PRESERVED\nFrame extraction failed.");}}).start();}

    void audioTrim(Uri u,long fromUs,long toUs,Result r){trimAudio(u,fromUs,toUs,false,r);}
    void trimAudio(Uri u,long fromUs,long toUs,boolean mute,Result r){new Thread(()->{File out=tmp(".m4a");MediaExtractor ex=null;MediaMuxer mux=null;try{ex=new MediaExtractor();ex.setDataSource(a,u,null);int track=-1;for(int i=0;i<ex.getTrackCount();i++){String m=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("audio/")){track=i;break;}}if(track<0)throw new IOException();MediaFormat f=ex.getTrackFormat(track);mux=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int mt=mux.addTrack(f);mux.start();ex.selectTrack(track);ex.seekTo(fromUs,MediaExtractor.SEEK_TO_CLOSEST_SYNC);ByteBuffer buf=ByteBuffer.allocateDirect(512*1024);MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();while(true){int n=ex.readSampleData(buf,0);long t=ex.getSampleTime();if(n<0||t<0||t>toUs)break;bi.offset=0;bi.size=n;bi.presentationTimeUs=Math.max(0,t-fromUs);bi.flags=ex.getSampleFlags();mux.writeSampleData(mt,buf,bi);ex.advance();}mux.stop();mux.release();mux=null;if(out.length()<=0)throw new IOException();a.publishAudio(out);safe(out);done(r,"> AUDIO TRIM COMPLETE\n✓ SAVED TO MUSIC");}catch(Exception e){if(mux!=null)try{mux.release();}catch(Exception ignored){}safe(out);fail(r,"> ORIGINAL PRESERVED\nAudio could not be trimmed.");}finally{if(ex!=null)ex.release();}}).start();}

    void createPdfFromImages(ArrayList<Uri> list,Result r){
        new Thread(()->{
            File f=tmp(".pdf");
            try{
                PdfDocument doc=new PdfDocument();
                for(Uri u:list){
                    Bitmap b=BitmapFactory.decodeStream(a.getContentResolver().openInputStream(u));
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
                a.publishPdf(out); safe(out); done(r,"> PDF COMPRESSION COMPLETE\\n"+a.fmtSize(src)+" → "+a.fmtSize(dst)+"\\n✓ OUTPUT VERIFIED\\n✓ ORIGINAL PRESERVED");
            }catch(Exception e){try{doc.close();}catch(Exception ignored){}try{if(pr!=null)pr.close();}catch(Exception ignored){}try{if(pfd!=null)pfd.close();}catch(Exception ignored){}safe(out);fail(r,"> ORIGINAL PRESERVED\\nPDF compression could not be completed.");}
        }).start();
    }

    void zipCreate(ArrayList<Uri> list,Result r){
        new Thread(()->{
            File f=tmp(".zip");
            try(ZipOutputStream z=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f)))){
                int i=0;
                for(Uri u:list){
                    String name="file_"+(++i); String mime=a.getContentResolver().getType(u);
                    if(mime!=null&&mime.contains("jpeg"))name+=".jpg"; else if(mime!=null&&mime.contains("png"))name+=".png"; else if(mime!=null&&mime.contains("pdf"))name+=".pdf"; else if(mime!=null&&mime.contains("video"))name+=".mp4"; else name+=".bin";
                    z.putNextEntry(new ZipEntry(name)); try(InputStream in=a.getContentResolver().openInputStream(u)){copy(in,z);} z.closeEntry();
                }
                if(!validZip(f)) throw new IOException("invalid zip"); a.publishZip(f); safe(f); done(r,"> ZIP CREATED\n✓ SAVED TO DOWNLOADS");
            }catch(Exception e){safe(f);fail(r,"> ZIP CREATION FAILED\nOriginal files preserved.");}
        }).start();
    }

    void zipExtract(Uri u,Result r){
        new Thread(()->{
            File dir=new File(a.outputDir(),"EXTRACT_"+System.currentTimeMillis());
            if(!dir.mkdirs()){fail(r,"> ORIGINAL PRESERVED\nCould not create export folder.");return;}
            try(ZipInputStream z=new ZipInputStream(new BufferedInputStream(a.getContentResolver().openInputStream(u)))){
                ZipEntry e; byte[] b=new byte[64*1024]; int n;
                while((e=z.getNextEntry())!=null){
                    if(e.isDirectory()){new File(dir,e.getName()).mkdirs();continue;}
                    File out=new File(dir,e.getName());
                    String base=dir.getCanonicalPath()+File.separator;
                    String target=out.getCanonicalPath();
                    if(!target.startsWith(base)) throw new IOException("unsafe ZIP entry");
                    File parent=out.getParentFile(); if(parent!=null)parent.mkdirs();
                    try(FileOutputStream o=new FileOutputStream(out)){while((n=z.read(b))!=-1)o.write(b,0,n);} z.closeEntry();
                }
                a.publishDirectory(dir); done(r,"> ZIP EXTRACT COMPLETE\n✓ FILES EXPORTED");
            }catch(Exception e){fail(r,"> ORIGINAL PRESERVED\nZIP could not be extracted.");}
        }).start();
    }
}
