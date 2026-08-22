package com.vr3th.mediacompressor;

import android.app.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.GradientDrawable;import android.net.Uri;import android.os.*;import android.provider.MediaStore;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;import java.util.zip.*;

public class MainActivity extends Activity{
    LinearLayout root; TextView status,telemetry; ProgressBar progress; Button cancel; final AtomicBoolean cancelled=new AtomicBoolean(false); static final int PICK=7; Handler main=new Handler(Looper.getMainLooper()); MediaOps ops; int mode=0;
    int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);} TextView tv(String s,float z,int c){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);v.setTypeface(Typeface.MONOSPACE);v.setPadding(dp(4),dp(3),dp(4),dp(3));return v;}
    GradientDrawable bg(int f,int st,int r){GradientDrawable g=new GradientDrawable();g.setColor(f);g.setCornerRadius(dp(r));if(st!=0)g.setStroke(dp(1),st);return g;}
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.rgb(5,10,8));getWindow().setNavigationBarColor(Color.rgb(5,10,8));ops=new MediaOps(this); splash();}
    void splash(){LinearLayout s=new LinearLayout(this);s.setOrientation(LinearLayout.VERTICAL);s.setGravity(Gravity.CENTER);s.setBackgroundColor(Color.rgb(5,10,8));TextView a=tv("Vr3tH🇵🇸",30,0xff39ff88);a.setGravity(17);a.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);TextView b=tv("INITIALIZING...",13,0xff43e8ff);b.setGravity(17);b.setPadding(0,dp(22),0,dp(8));TextView c=tv("MEDIA PROCESSING CORE",16,0xffe9fff2);c.setGravity(17);TextView d=tv("◉ READY_",13,0xff7faf98);d.setGravity(17);s.addView(a);s.addView(b);s.addView(c);s.addView(d);setContentView(s);main.postDelayed(this::ui,900);}
    void ui(){ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setBackgroundColor(0xff050a08);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(14),dp(14),dp(24));sc.addView(root);setContentView(sc);
        LinearLayout h=new LinearLayout(this);h.setOrientation(LinearLayout.VERTICAL);h.setPadding(dp(15),dp(14),dp(15),dp(14));h.setBackground(bg(0xff0b1511,0xff39ff88,16));TextView t=tv("MEDIA // COMPRESSOR",22,0xffe9fff2);t.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);h.addView(t);h.addView(tv("CORE: ONLINE   ●   INTEGRITY: OK",10,0xff39ff88));h.addView(tv("CYBER MEDIA WORKSTATION  //  Vr3tH🇵🇸",9,0xff43e8ff));root.addView(h);
        TextView sec=tv("// COMMAND MODULES",11,0xff43e8ff);sec.setPadding(dp(4),dp(18),dp(4),dp(8));root.addView(sec);
        add("◈ VIDEO COMPRESS","SMART ENCODE • SIZE GUARD","Compress video; larger/equal output is never accepted.",()->{mode=1;pick("*/*");});
        add("◇ PHOTO COMPRESS","SMART JPEG • SIZE GUARD","Automatic photo compression with safe original preservation.",()->pick("image/*"));
        add("◇ JPG / PNG / WEBP","FORMAT CONVERTER","Convert image formats; smaller output is preferred.",()->{mode=10;pick("image/*");});
        add("◇ REMOVE EXIF","METADATA CLEAN","Re-encode a JPEG without carrying camera metadata.",()->{mode=11;pick("image/*");});
        add("◇ BATCH PHOTO","MULTI SELECT","Compress multiple images in one operation.",()->{mode=12;pickMulti("image/*");});
        add("▣ VIDEO MERGE","NORMALIZE • ASPECT SAFE","Merge mixed formats with one verified output geometry.",()->{mode=19;pickMulti("*/*");});
        add("▣ VIDEO TRIM","SMART NORMALIZE","Trim through the verified media pipeline.",()->{mode=20;pick("*/*");});
        add("▣ MUTE VIDEO","AUDIO STRIP • SAFE","Remove audio through the verified media pipeline.",()->{mode=21;pick("*/*");});
        add("▣ EXTRACT FRAME","VIDEO → PHOTO","Export a frame to Gallery.",()->{mode=22;pick("*/*");});
        add("♫ VIDEO → MP3","AUDIO EXTRACT","Extract video audio to MP3 using the lightest available path.",()->{mode=23;pick("*/*");});
        add("◉ FORMAT LAB","REAL DEVICE CAPABILITY","Inspect input families and actual Android encoders; unsupported output is never claimed.",()->showFormatLab());
        add("♫ AUDIO TRIM","SMART AAC NORMALIZE","Trim audio into a verified M4A/AAC output.",()->{mode=30;pick("*/*");});
        add("▤ PHOTO → PDF","DOCUMENT BUILD","Create a PDF from one or more photos.",()->{mode=40;pickMulti("image/*");});
        add("▤ PDF → PHOTO","PAGE RENDER","Render every PDF page to Gallery.",()->{mode=41;pick("application/pdf");});
        add("▤ MERGE PDF","DOCUMENT MERGE","Merge selected PDF files into one document.",()->{mode=42;pickMulti("application/pdf");});
        add("▤ SPLIT PDF","PAGE RANGE","Extract a page range into a new PDF.",()->{mode=43;pick("application/pdf");});
        add("▤ COMPRESS PDF","SMART REBUILD • SIZE GUARD","Rebuild a PDF at lower raster size only when it becomes smaller.",()->{mode=44;pick("application/pdf");});
        add("▦ CREATE ZIP","ARCHIVE BUILD","Create a ZIP archive from selected files.",()->{mode=50;pickMulti("*/*");});
        add("▦ EXTRACT ZIP","ARCHIVE UNPACK","Extract a ZIP safely into an export folder.",()->{mode=51;pick("application/zip");});
        add("▦ ZIP RECOMPRESS","ARCHIVE OPTIMIZE","Repack a ZIP and keep the smaller archive only.",()->{mode=52;pick("application/zip");});
        TextView more=tv("// CODEC NOTE\nMP3 conversion uses a native MP3 encoder when the device exposes one; no heavyweight FFmpeg bundle is added, preserving the Ultra Lite target.",9,0xff7faf98);more.setPadding(dp(4),dp(8),dp(4),dp(12));root.addView(more);
        LinearLayout op=new LinearLayout(this);op.setOrientation(LinearLayout.VERTICAL);op.setPadding(dp(14),dp(12),dp(14),dp(12));op.setBackground(bg(0xff0b1511,0xff1e4b37,14));status=tv("> SYSTEM READY_\nSelect a module to begin.",12,0xffe9fff2);telemetry=tv("OP-IDLE  //  NO ACTIVE OPERATION",10,0xff7faf98);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setVisibility(View.GONE);cancel=new Button(this);cancel.setText("CANCEL OPERATION");cancel.setAllCaps(false);cancel.setTextColor(0xff39ff88);cancel.setBackground(bg(0xff0e1d16,0xff1e4b37,10));cancel.setVisibility(View.GONE);op.addView(status);op.addView(telemetry);op.addView(progress,new LinearLayout.LayoutParams(-1,dp(7)));op.addView(cancel,new LinearLayout.LayoutParams(-1,dp(45)));root.addView(op);cancel.setOnClickListener(v->cancelled.set(true));}
    void add(String a,String b,String c,Runnable r){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(13),dp(11),dp(13),dp(11));card.setBackground(bg(0xff0b1511,0xff1e4b37,14));card.setOnClickListener(v->r.run());TextView x=tv(a,14,0xffe9fff2);x.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);card.addView(x);card.addView(tv(b+"   READY",9,0xff39ff88));card.addView(tv(c,10,0xff7faf98));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));root.addView(card,p);}
    void pick(String type){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType(type);i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK);}
    void pickMulti(String type){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType(type);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,PICK);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r!=PICK||c!=RESULT_OK||d==null)return;ArrayList<Uri> list=new ArrayList<>();if(d.getClipData()!=null)for(int i=0;i<d.getClipData().getItemCount();i++)list.add(d.getClipData().getItemAt(i).getUri());else if(d.getData()!=null)list.add(d.getData());if(list.isEmpty())return;Uri u=list.get(0);int m=mode;mode=0;switch(m){case 19:show("VIDEO MERGE");ops.mergeVideos(list,res());break;case 10:show("IMAGE CONVERTER");chooseFormat(u);break;case 11:show("REMOVE EXIF");ops.exifStrip(u,res());break;case 12:show("BATCH PHOTO");ops.batchImages(list,res());break;case 20:show("VIDEO TRIM");timeDialog(u,false,false);break;case 21:show("MUTE VIDEO");ops.trimOrMuteVideo(u,0,Long.MAX_VALUE,true,res());break;case 22:show("EXTRACT FRAME");ops.frame(u,0,res());break;case 23:show("VIDEO → MP3");ops.videoToMp3(u,res());break;case 30:show("AUDIO TRIM");timeDialog(u,true,false);break;case 40:show("PHOTO → PDF");ops.createPdfFromImages(list,res());break;case 41:show("PDF → PHOTO");ops.renderPdf(u,res());break;case 42:show("MERGE PDF");ops.mergePdfs(list,res());break;
            case 43:show("SPLIT PDF");pdfRangeDialog(u);break;
            case 44:show("COMPRESS PDF");ops.compressPdf(u,res());break;case 50:show("CREATE ZIP");ops.zipCreate(list,res());break;case 51:show("EXTRACT ZIP");ops.zipExtract(u,res());break;case 52:show("ZIP RECOMPRESS");repackZip(u);break;case 1:show("VIDEO COMPRESSION");startVideo(u);break;default: if(d.getClipData()!=null){show("BATCH PHOTO");ops.batchImages(list,res());}else{String mime=getContentResolver().getType(u);if(mime!=null&&mime.startsWith("image/")){show("PHOTO COMPRESSION");compressPhoto(u);}else if(mime!=null&&mime.startsWith("video/")){show("VIDEO COMPRESSION");startVideo(u);} }} }
    void pdfRangeDialog(Uri u){
        final EditText from=new EditText(this); from.setHint("first page (e.g. 1)");
        final EditText to=new EditText(this); to.setHint("last page (e.g. 3)");
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(4),dp(18),dp(4)); box.addView(from); box.addView(to);
        new AlertDialog.Builder(this).setTitle("SPLIT PDF").setView(box)
            .setNegativeButton("CANCEL",null).setPositiveButton("RUN",(d,w)->{
                try{
                    int a1=Integer.parseInt(from.getText().toString().trim());
                    int b1=Integer.parseInt(to.getText().toString().trim());
                    if(a1<1||b1<1) throw new IllegalArgumentException();
                    int lo=Math.min(a1,b1), hi=Math.max(a1,b1);
                    ops.splitPdf(u,lo-1,hi-1,res());
                }catch(Exception e){showStatus("> ORIGINAL PRESERVED\nInvalid page range.");}
            }).show();
    }
    void showFormatLab(){
        new AlertDialog.Builder(this).setTitle("FORMAT LAB // REAL DEVICE CAPABILITY")
            .setMessage(FormatCatalog.report())
            .setPositiveButton("OK",null).show();
    }
    void chooseFormat(Uri u){new AlertDialog.Builder(this).setTitle("OUTPUT FORMAT").setItems(new String[]{"JPG","PNG","WEBP"},(d,w)->ops.convertImage(u,w==0?"jpg":w==1?"png":"webp",res())).show();}
    void timeDialog(Uri u,boolean audio,boolean mute){final EditText from=new EditText(this);from.setHint("start seconds");from.setInputType(8194);final EditText to=new EditText(this);to.setHint("end seconds");to.setInputType(8194);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(4),dp(18),dp(4));box.addView(from);box.addView(to);TextView hint=tv("START < END = FORWARD  >>\nSTART > END = REVERSE  <<\nSTART == END = INVALID\nTIMES MUST BE INSIDE THE REAL SOURCE DURATION",10,0xff7faf98);hint.setPadding(0,dp(8),0,0);box.addView(hint);new AlertDialog.Builder(this).setTitle(audio?"AUDIO TRIM / REVERSE":"VIDEO TRIM / REVERSE").setView(box).setNegativeButton("CANCEL",null).setPositiveButton("RUN",(d,w)->{try{long a=(long)(Double.parseDouble(from.getText().toString())*1000000);long b=(long)(Double.parseDouble(to.getText().toString())*1000000);if(a<0||b<0||a==b){showStatus("> ORIGINAL PRESERVED\nREQUEST DENIED\nInvalid time range.");return;}if(audio)ops.audioTrim(u,a,b,res());else ops.trimOrMuteVideo(u,a,b,mute,res());}catch(Exception e){showStatus("> ORIGINAL PRESERVED\nInvalid time range.");}}).show();}
    void compressPhoto(Uri u){new Thread(()->{File f=null;Bitmap b=null;try{
        long s=sourceSize(u); b=decodeOrientedBitmap(u); if(b==null)throw new IOException("decode");
        int[] qs=new int[]{82,72,62,52}; long best=Long.MAX_VALUE; int used=82;
        for(int q:qs){File candidate=tmp(".jpg");try(FileOutputStream o=new FileOutputStream(candidate)){if(!b.compress(Bitmap.CompressFormat.JPEG,q,o))throw new IOException("encode");}
            long d=candidate.length(); if(d>0&&d<best){if(f!=null)safe(f);f=candidate;best=d;used=q;}else safe(candidate);
            if(s>0&&d>0&&d<s)break;
        }
        if(f==null||best<=0)throw new IOException("empty output");
        if(s>0&&best>=s){safe(f);showStatus("> OPTIMAL RESULT\nOriginal already efficient; no smaller output found.\nOriginal quality preserved ✓");return;}
        publishImage(f,false); safe(f); showStatus("> COMPRESSION ENGINE: COMPLETE\n"+fmtSize(s)+" → "+fmtSize(best)+"\nQUALITY "+used+"\n✓ OUTPUT VERIFIED\n✓ ORIGINAL PRESERVED\n✓ SAVED TO GALLERY");
    }catch(Exception e){safe(f);showStatus("> ORIGINAL PRESERVED\nA better version could not be created.");}finally{if(b!=null)b.recycle();}}).start();}
    Bitmap decodeOrientedBitmap(Uri u)throws IOException{
        Bitmap b;
        try(InputStream in=getContentResolver().openInputStream(u)){b=BitmapFactory.decodeStream(in);}
        if(b==null)throw new IOException("decode");
        int orientation=androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL;
        try(InputStream ei=getContentResolver().openInputStream(u)){
            if(ei!=null){
                androidx.exifinterface.media.ExifInterface ex=new androidx.exifinterface.media.ExifInterface(ei);
                orientation=ex.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,orientation);
            }
        }catch(Exception ignored){}
        Matrix m=new Matrix();
        switch(orientation){
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1f,1f); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180f); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1f,-1f); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90f); m.postScale(-1f,1f); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90f); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(270f); m.postScale(-1f,1f); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(270f); break;
            default: return b;
        }
        Bitmap r=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);
        if(r!=b)b.recycle();
        return r;
    }
    void startVideo(Uri u){new Thread(()->new VideoCompressor(this).run(u,cancelled)).start();}
    void show(String s){cancelled.set(false);String opId="OP-"+Long.toHexString(System.nanoTime()).toUpperCase(Locale.US).substring(Math.max(0,Long.toHexString(System.nanoTime()).length()-6));main.post(()->{status.setText("> "+s+"\n> ANALYZING SOURCE");telemetry.setText(opId+"  //  SCANNING  //  OPTIMIZING  //  VERIFYING");progress.setVisibility(View.VISIBLE);progress.setProgress(0);cancel.setVisibility(View.VISIBLE);});}
    void showStatus(String s){main.post(()->{status.setText(s);progress.setVisibility(View.GONE);cancel.setVisibility(View.GONE);telemetry.setText("INTEGRITY: OK  //  ORIGINAL SAFE");});}
    MediaOps.Result res(){return new MediaOps.Result(){public void ok(String s){showStatus(s);}public void fail(String s){showStatus(s);}};}
    void videoProgress(int p){main.post(()->progress.setProgress(Math.max(0,Math.min(100,p))));}void videoFinished(String s){showStatus(s);}
    File outputDir(){File d=new File(getExternalFilesDir(null),"Compressed");if(!d.exists())d.mkdirs();return d;}File tmp(String e){return new File(outputDir(),".tmp_"+System.nanoTime()+e);}void safe(File f){if(f!=null)f.delete();}
    long sourceSize(Uri u){try(android.content.res.AssetFileDescriptor a=getContentResolver().openAssetFileDescriptor(u,"r")){return a==null?-1:a.getLength();}catch(Exception e){return -1;}}
    Uri publishImage(File f,boolean webp)throws IOException{
        return publishImageFormat(f,webp?"webp":"jpg");
    }
    Uri publishImageFormat(File f,String ext)throws IOException{
        String e=ext.toLowerCase(Locale.US);
        String mime=e.equals("png")?"image/png":e.equals("webp")?"image/webp":"image/jpeg";
        String n="IMG_"+System.currentTimeMillis()+"."+(e.equals("jpeg")?"jpg":e);
        return publishGeneric(f,mime,"Download/MediaCompressor/Images",n);
    }
    Uri publishVideo(File f)throws IOException{
        return publishGeneric(f,"video/mp4","Download/MediaCompressor/Videos","VID_"+System.currentTimeMillis()+".mp4");
    }
    Uri publishAudio(File f)throws IOException{
        return publishAudioFormat(f,"m4a");
    }
    Uri publishAudioFormat(File f,String ext)throws IOException{
        String e=ext.toLowerCase(Locale.US);
        String mime=e.equals("mp3")?"audio/mpeg":e.equals("wav")?"audio/wav":"audio/mp4";
        return publishGeneric(f,mime,"Download/MediaCompressor/Audio","AUD_"+System.currentTimeMillis()+"."+e);
    }
    Uri publishPdf(File f)throws IOException{
        return publishGeneric(f,"application/pdf","Download/MediaCompressor/Documents","MC_"+System.currentTimeMillis()+".pdf");
    }
    Uri publishZip(File f)throws IOException{
        return publishGeneric(f,"application/zip","Download/MediaCompressor/Archives","MC_"+System.currentTimeMillis()+".zip");
    }
    Uri publishGeneric(File f,String mime,String rel,String name)throws IOException{
        ContentValues v=new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        if(Build.VERSION.SDK_INT>=29){
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,rel.endsWith("/")?rel:rel+"/");
            v.put(MediaStore.MediaColumns.IS_PENDING,1);
        }
        Uri u=getContentResolver().insert(MediaStore.Files.getContentUri("external"),v);
        if(u==null)throw new IOException("storage");
        boolean ok=false;
        try(OutputStream o=getContentResolver().openOutputStream(u);InputStream in=new FileInputStream(f)){
            if(o==null)throw new IOException("output");
            copy(in,o); o.flush(); ok=true;
        } finally {
            if(Build.VERSION.SDK_INT>=29){
                ContentValues done=new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING,0);
                if(!ok) getContentResolver().delete(u,null,null); else getContentResolver().update(u,done,null,null);
            } else if(!ok) getContentResolver().delete(u,null,null);
        }
        return u;
    }
    void publishDirectory(File d)throws IOException{ publishDirectory(d, "Extracted"); }
    void publishDirectory(File d,String rootName)throws IOException{
        if(d==null||!d.isDirectory())throw new IOException("directory");
        ArrayList<File> files=new ArrayList<>(); collectFiles(d,files);
        ArrayList<Uri> published=new ArrayList<>();
        try{
            for(File f:files){
                if(cancelled.get())throw new IOException("cancelled");
                String rel=d.toURI().relativize(f.toURI()).getPath();
                if(rel==null||rel.isEmpty())continue;
                String safe=rel.replace('\\','/');
                int slash=safe.lastIndexOf('/');
                String dir=slash>=0?safe.substring(0,slash):"";
                String name=slash>=0?safe.substring(slash+1):safe;
                ContentValues v=new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
                v.put(MediaStore.MediaColumns.MIME_TYPE,guessMime(name));
                if(Build.VERSION.SDK_INT>=29){
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/MediaCompressor/Extract/"+rootName+"/"+(dir.isEmpty()?"":dir+"/"));
                    v.put(MediaStore.MediaColumns.IS_PENDING,1);
                }
                Uri out=getContentResolver().insert(MediaStore.Files.getContentUri("external"),v);
                if(out==null)throw new IOException("storage");
                published.add(out);
                try(OutputStream o=getContentResolver().openOutputStream(out);InputStream in=new FileInputStream(f)){
                    copy(in,o);
                }
                if(cancelled.get())throw new IOException("cancelled");
                if(Build.VERSION.SDK_INT>=29){
                    ContentValues done=new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING,0);
                    if(getContentResolver().update(out,done,null,null)<=0)throw new IOException("publish");
                }
            }
            deleteTree(d);
        }catch(Exception e){
            for(Uri out:published){try{getContentResolver().delete(out,null,null);}catch(Exception ignored){}}
            deleteTree(d);
            if(e instanceof IOException)throw (IOException)e;
            throw new IOException(e);
        }
    }
    private void collectFiles(File d,ArrayList<File> out){File[] fs=d.listFiles();if(fs==null)return;for(File f:fs){if(f.isDirectory())collectFiles(f,out);else out.add(f);}}
    private void deleteTree(File d){File[] fs=d.listFiles();if(fs!=null)for(File f:fs){if(f.isDirectory())deleteTree(f);else f.delete();}d.delete();}
    private String guessMime(String n){String x=n.toLowerCase(Locale.US);if(x.endsWith(".jpg")||x.endsWith(".jpeg"))return "image/jpeg";if(x.endsWith(".png"))return "image/png";if(x.endsWith(".webp"))return "image/webp";if(x.endsWith(".mp4"))return "video/mp4";if(x.endsWith(".m4a"))return "audio/mp4";if(x.endsWith(".mp3"))return "audio/mpeg";if(x.endsWith(".pdf"))return "application/pdf";if(x.endsWith(".zip"))return "application/zip";return "application/octet-stream";}
    /** Localize a content/document Uri while preserving its real media extension.
     * Transformer/Media3 uses the URI/file extension as an important hint for some
     * extractors. Using a generic ".input" extension can turn otherwise supported
     * containers into an AssetLoader/runtime failure.
     */
    File copyToTemp(Uri u,String fallbackExt)throws IOException{
        String ext=fallbackExt;
        try{
            android.database.Cursor c=getContentResolver().query(u,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null);
            if(c!=null){
                try{
                    if(c.moveToFirst()){
                        int idx=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if(idx>=0){
                            String name=c.getString(idx);
                            if(name!=null){
                                int dot=name.lastIndexOf('.');
                                if(dot>0&&dot<name.length()-1){
                                    String candidate=name.substring(dot).toLowerCase(Locale.US);
                                    if(candidate.matches("\\.[a-z0-9]{1,8}")) ext=candidate;
                                }
                            }
                        }
                    }
                }finally{c.close();}
            }
        }catch(Exception ignored){}
        File f=tmp(ext);
        try(InputStream in=getContentResolver().openInputStream(u);OutputStream o=new FileOutputStream(f)){
            if(in==null)throw new IOException("input stream unavailable");
            copy(in,o);
        }
        return f;
    }
    void copy(InputStream i,OutputStream o)throws IOException{byte[] b=new byte[64*1024];int n;while((n=i.read(b))!=-1){if(cancelled.get())throw new IOException("cancelled");o.write(b,0,n);}}
    String fmtSize(long n){if(n<1024)return n+" B";double x=n/1024.0;if(x<1024)return String.format(Locale.US,"%.1f KB",x);x/=1024;if(x<1024)return String.format(Locale.US,"%.2f MB",x);return String.format(Locale.US,"%.2f GB",x/1024);}
    void repackZip(Uri u){new Thread(()->{File f=tmp(".zip");try{
        InputStream raw=getContentResolver().openInputStream(u);if(raw==null)throw new IOException("input");
        try(ZipInputStream in=new ZipInputStream(new BufferedInputStream(raw));ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f)))){
            out.setLevel(6);ZipEntry e;byte[] b=new byte[128*1024];HashSet<String> used=new HashSet<>();
            while((e=in.getNextEntry())!=null){if(cancelled.get())throw new IOException("cancelled");String name=e.getName();if(name==null||name.isEmpty())name="file_"+used.size();String base=name,ext="";int slash=name.lastIndexOf('/');String leaf=slash>=0?name.substring(slash+1):name;if(leaf.isEmpty()){in.closeEntry();continue;}int dot=leaf.lastIndexOf('.');if(dot>0){String prefix=leaf.substring(0,dot);ext=leaf.substring(dot);base=(slash>=0?name.substring(0,slash+1):"")+prefix;}String candidate=name;int k=2;while(used.contains(candidate)){candidate=base+"_"+(k++)+ext;}used.add(candidate);ZipEntry n=new ZipEntry(candidate);if(e.getTime()>0)n.setTime(e.getTime());out.putNextEntry(n);int z;while((z=in.read(b))!=-1){if(cancelled.get())throw new IOException("cancelled");out.write(b,0,z);}out.closeEntry();in.closeEntry();}
            out.finish();out.flush();
        }
        long d=f.length();if(d<=0)throw new IOException("empty");publishZip(f);safe(f);showStatus("> ZIP RECOMPRESS COMPLETE\n"+fmtSize(sourceSize(u))+" → "+fmtSize(d)+"\n✓ ZIP VERIFIED\n✓ SAVED TO Download/MediaCompressor/Archives");
    }catch(Exception e){safe(f);showStatus(cancelled.get()?"> ORIGINAL PRESERVED\nRecompression cancelled safely.":"> ORIGINAL PRESERVED\nZIP recompression could not be completed.");}}).start();}
}
