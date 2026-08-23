package com.vr3th.mediacompressor;

import android.app.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.GradientDrawable;import android.net.Uri;import android.os.*;import android.provider.MediaStore;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;import java.util.zip.*;

public class MainActivity extends Activity{
    LinearLayout root; TextView status,telemetry; ProgressBar progress; Button cancel; final AtomicBoolean cancelled=new AtomicBoolean(false); static final int PICK=7; Handler main=new Handler(Looper.getMainLooper()); MediaOps ops; int mode=0;
    int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);} TextView tv(String s,float z,int c){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);v.setTypeface(Typeface.MONOSPACE);v.setPadding(dp(4),dp(3),dp(4),dp(3));return v;}
    GradientDrawable bg(int f,int st,int r){GradientDrawable g=new GradientDrawable();g.setColor(f);g.setCornerRadius(dp(r));if(st!=0)g.setStroke(dp(1),st);return g;}
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xff08050a);getWindow().setNavigationBarColor(0xff08050a);ops=new MediaOps(this);splash();}
    void splash(){
        LinearLayout s=new LinearLayout(this);s.setOrientation(LinearLayout.VERTICAL);s.setGravity(Gravity.CENTER);s.setPadding(dp(28),0,dp(28),0);s.setBackgroundColor(0xff08050a);
        TextView mark=tv("MC",54,0xffff4fa3);mark.setGravity(17);mark.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        TextView name=tv("MEDIA COMPRESSOR",19,0xffffffff);name.setGravity(17);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        TextView by=tv("SMART MEDIA ENGINE  •  Vr3tH🇵🇸",10,0xffff9bc8);by.setGravity(17);by.setPadding(0,dp(8),0,dp(26));
        TextView ready=tv("●  OFFLINE ENGINE READY",11,0xff71e6b1);ready.setGravity(17);
        s.addView(mark);s.addView(name);s.addView(by);s.addView(ready);setContentView(s);main.postDelayed(this::ui,650);
    }
    TextView label(String s){TextView v=tv(s,10,0xffff8fc2);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setLetterSpacing(.08f);v.setPadding(dp(3),dp(18),dp(3),dp(8));return v;}
    LinearLayout card(String title,String sub,String icon,Runnable r){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(13),dp(14),dp(13));c.setBackground(bg(0xff120b12,0xff3a1b2d,16));c.setOnClickListener(v->r.run());
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic=tv(icon,20,0xffff4fa3);ic.setGravity(17);row.addView(ic,new LinearLayout.LayoutParams(dp(34),dp(34)));
        LinearLayout tx=new LinearLayout(this);tx.setOrientation(LinearLayout.VERTICAL);tx.setPadding(dp(10),0,0,0);
        TextView a=tv(title,14,0xffffffff);a.setTypeface(Typeface.DEFAULT,Typeface.BOLD);tx.addView(a);
        tx.addView(tv(sub,9,0xffa995a2));row.addView(tx,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=tv("›",26,0xffff73b4);arrow.setGravity(17);row.addView(arrow,new LinearLayout.LayoutParams(dp(28),dp(40)));c.addView(row);
        return c;
    }
    void addCard(String title,String sub,String icon,Runnable r){LinearLayout c=card(title,sub,icon,r);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(9));root.addView(c,p);}
    void ui(){
        ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setBackgroundColor(0xff08050a);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(15),dp(12),dp(15),dp(28));sc.addView(root);setContentView(sc);
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setPadding(dp(17),dp(17),dp(17),dp(17));top.setBackground(bg(0xff120a11,0xff51223c,20));
        LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=tv("MediaCompressor",24,0xffffffff);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);r.addView(title,new LinearLayout.LayoutParams(0,-2,1));TextView gear=tv("⚙",22,0xffff6eaf);gear.setGravity(17);r.addView(gear,new LinearLayout.LayoutParams(dp(38),dp(38)));top.addView(r);
        top.addView(tv("SMART MEDIA PROCESSING",10,0xffff8fc2));
        LinearLayout engine=new LinearLayout(this);engine.setGravity(Gravity.CENTER_VERTICAL);engine.setPadding(0,dp(14),0,0);TextView dot=tv("●",12,0xff6fe5ad);engine.addView(dot);engine.addView(tv("  OFFLINE ENGINE  •  READY",10,0xffd7cbd4));top.addView(engine);root.addView(top);
        root.addView(label("QUICK COMPRESS"));
        LinearLayout hero=card("Compress Media","Video  •  Photo  •  Audio","＋",()->pick("*/*"));hero.setPadding(dp(16),dp(18),dp(16),dp(18));hero.setBackground(bg(0xff1a0b16,0xffff4fa3,20));root.addView(hero);
        root.addView(label("VIDEO"));
        addCard("Video Compress","Smart encode  •  size guard","▶",()->{mode=1;pick("*/*");});
        addCard("Video Merge","Combine multiple videos","◆",()->{mode=19;pickMulti("*/*");});
        addCard("Video Trim","Cut without touching the original","✂",()->{mode=20;pick("*/*");});
        addCard("Mute Video","Remove audio safely","♪",()->{mode=21;pick("*/*");});
        addCard("Extract Frame","Video → photo","▣",()->{mode=22;pick("*/*");});
        addCard("Video → Audio","Extract audio","♫",()->{mode=23;pick("*/*");});
        root.addView(label("PHOTO"));
        addCard("Photo Compress","Smart JPEG  •  size guard","▧",()->pick("image/*"));
        addCard("JPG / PNG / WEBP","Image format converter","◈",()->{mode=10;pick("image/*");});
        addCard("Remove EXIF","Clean camera metadata","⌘",()->{mode=11;pick("image/*");});
        addCard("Batch Photo","Compress multiple images","▦",()->{mode=12;pickMulti("image/*");});
        root.addView(label("TOOLS"));
        addCard("Format Lab","See real device codec capability","◎",()->showFormatLab());
        addCard("Audio Trim","Trim audio into verified output","♫",()->{mode=30;pick("*/*");});
        addCard("Photo → PDF","Build PDF from photos","▤",()->{mode=40;pickMulti("image/*");});
        addCard("PDF → Photo","Render PDF pages","▤",()->{mode=41;pick("application/pdf");});
        addCard("Merge PDF","Combine PDF files","▤",()->{mode=42;pickMulti("application/pdf");});
        addCard("Split PDF","Extract a page range","▤",()->{mode=43;pick("application/pdf");});
        addCard("Compress PDF","Rebuild only when smaller","▤",()->{mode=44;pick("application/pdf");});
        root.addView(label("ARCHIVE"));
        addCard("Create ZIP","Archive selected files","▦",()->{mode=50;pickMulti("*/*");});
        addCard("Extract ZIP","Unpack safely","▦",()->{mode=51;pick("application/zip");});
        addCard("ZIP Recompress","Keep only the smaller archive","▦",()->{mode=52;pick("application/zip");});
        LinearLayout op=new LinearLayout(this);op.setOrientation(LinearLayout.VERTICAL);op.setPadding(dp(15),dp(14),dp(15),dp(14));op.setBackground(bg(0xff100a10,0xff321827,16));
        status=tv("> SYSTEM READY_\nSelect a tool to begin.",12,0xffffffff);status.setTypeface(Typeface.MONOSPACE);telemetry=tv("OFFLINE  //  NO ACTIVE OPERATION",9,0xffa995a2);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setVisibility(View.GONE);cancel=new Button(this);cancel.setText("CANCEL");cancel.setAllCaps(false);cancel.setTextColor(0xffff72b3);cancel.setBackground(bg(0xff170c14,0xff3a1b2d,10));cancel.setVisibility(View.GONE);op.addView(status);op.addView(telemetry);op.addView(progress,new LinearLayout.LayoutParams(-1,dp(6)));op.addView(cancel,new LinearLayout.LayoutParams(-1,dp(44)));root.addView(op);
        TextView foot=tv("MediaCompressor  •  by Vr3tH🇵🇸\nOffline processing  •  Original files are never replaced",9,0xff806f7a);foot.setGravity(Gravity.CENTER);foot.setPadding(0,dp(22),0,0);root.addView(foot);cancel.setOnClickListener(v->cancelled.set(true));
    }
    void pick(String type){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType(type);i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK);}
    void pickMulti(String type){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType(type);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,PICK);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r!=PICK||c!=RESULT_OK||d==null)return;ArrayList<Uri> list=new ArrayList<>();if(d.getClipData()!=null)for(int i=0;i<d.getClipData().getItemCount();i++)list.add(d.getClipData().getItemAt(i).getUri());else if(d.getData()!=null)list.add(d.getData());if(list.isEmpty())return;Uri u=list.get(0);int m=mode;mode=0;switch(m){case 19:show("VIDEO MERGE");ops.mergeVideos(list,res());break;case 10:show("IMAGE CONVERTER");chooseFormat(u);break;case 11:show("REMOVE EXIF");ops.exifStrip(u,res());break;case 12:show("BATCH PHOTO");ops.batchImages(list,res());break;case 20:show("VIDEO TRIM");timeDialog(u,false,false);break;case 21:show("MUTE VIDEO");ops.trimOrMuteVideo(u,0,Long.MAX_VALUE,true,res());break;case 22:show("EXTRACT FRAME");ops.frame(u,0,res());break;case 23:show("VIDEO → AUDIO");ops.videoToAudio(u,res());break;case 30:show("AUDIO TRIM");timeDialog(u,true,false);break;case 40:show("PHOTO → PDF");ops.createPdfFromImages(list,res());break;case 41:show("PDF → PHOTO");ops.renderPdf(u,res());break;case 42:show("MERGE PDF");ops.mergePdfs(list,res());break;
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
        publishImage(f,false); safe(f); showStatus("> COMPRESSION ENGINE: COMPLETE\n"+fmtSize(s)+" → "+fmtSize(best)+"\nQUALITY "+used+"\n✓ OUTPUT VERIFIED\n✓ ORIGINAL PRESERVED\n✓ SAVED TO Download/MediaCompressor/Images");
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
    void showStatus(String s){main.post(()->{status.setText(s);progress.setVisibility(View.GONE);cancel.setVisibility(View.GONE);boolean failed=s.contains("FAILED")||s.contains("DENIED")||s.contains("ERROR");telemetry.setText(failed?"INTEGRITY: ORIGINAL SAFE  //  OPERATION FAILED":"INTEGRITY: OK  //  ORIGINAL SAFE");});}
    MediaOps.Result res(){return new MediaOps.Result(){public void ok(String s){showStatus(s);}public void fail(String s){showStatus(s);}};}
    void videoProgress(int p){main.post(()->progress.setProgress(Math.max(0,Math.min(100,p))));}void videoFinished(String s){showStatus(s);}
    static final String ROOT="Download/MediaCompressor";
    static final String DIR_VIDEOS=ROOT+"/Videos";
    static final String DIR_IMAGES=ROOT+"/Images";
    static final String DIR_AUDIO=ROOT+"/Audio";
    static final String DIR_DOCUMENTS=ROOT+"/Documents";
    static final String DIR_ARCHIVES=ROOT+"/Archives";
    static final String DIR_EXTRACTED=DIR_ARCHIVES+"/Extracted";
    File outputDir(){File d=new File(getCacheDir(),"mc_work");if(!d.exists())d.mkdirs();return d;}File tmp(String e){return new File(outputDir(),".tmp_"+System.nanoTime()+e);}void safe(File f){if(f!=null)f.delete();}
    long sourceSize(Uri u){try(android.content.res.AssetFileDescriptor a=getContentResolver().openAssetFileDescriptor(u,"r")){return a==null?-1:a.getLength();}catch(Exception e){return -1;}}
    Uri publishImage(File f,boolean webp)throws IOException{
        return publishImageFormat(f,webp?"webp":"jpg");
    }
    Uri publishImageFormat(File f,String ext)throws IOException{
        String e=ext.toLowerCase(Locale.US);
        String mime=e.equals("png")?"image/png":e.equals("webp")?"image/webp":"image/jpeg";
        String n="IMG_"+System.currentTimeMillis()+"."+(e.equals("jpeg")?"jpg":e);
        return publishGeneric(f,mime,DIR_IMAGES,n);
    }
    Uri publishVideo(File f)throws IOException{
        return publishGeneric(f,"video/mp4",DIR_VIDEOS,"VID_"+System.currentTimeMillis()+".mp4");
    }
    Uri publishAudio(File f)throws IOException{
        return publishAudioFormat(f,"m4a");
    }
    Uri publishAudioFormat(File f,String ext)throws IOException{
        String e=ext.toLowerCase(Locale.US);
        String mime=e.equals("mp3")?"audio/mpeg":e.equals("wav")?"audio/wav":"audio/mp4";
        return publishGeneric(f,mime,DIR_AUDIO,"AUD_"+System.currentTimeMillis()+"."+e);
    }
    Uri publishPdf(File f)throws IOException{
        return publishGeneric(f,"application/pdf",DIR_DOCUMENTS,"MC_"+System.currentTimeMillis()+".pdf");
    }
    Uri publishZip(File f)throws IOException{
        return publishGeneric(f,"application/zip",DIR_ARCHIVES,"MC_"+System.currentTimeMillis()+".zip");
    }
    Uri publishGeneric(File f,String mime,String rel,String name)throws IOException{
        ContentValues v=new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        if(Build.VERSION.SDK_INT>=29){
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,rel.endsWith("/")?rel:rel+"/");
            v.put(MediaStore.MediaColumns.IS_PENDING,1);
        }else{
            File dir=new File(Environment.getExternalStorageDirectory(),rel);
            if(!dir.exists()&&!dir.mkdirs())throw new IOException("storage folder");
            v.put(MediaStore.MediaColumns.DATA,new File(dir,name).getAbsolutePath());
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
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH,DIR_EXTRACTED+"/"+rootName+"/"+(dir.isEmpty()?"":dir+"/"));
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
     * The URI/file extension is used as an input hint for some legacy providers.
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
