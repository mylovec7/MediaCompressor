package com.vr3th.mediacompressor;

import android.graphics.Bitmap;
import android.media.*;
import android.net.Uri;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small dependency-free video path. It uses Android platform codecs only. */
final class NativeVideoEngine {
    private NativeVideoEngine() {}

    static final class Result {
        final long durationUs; final int width; final int height; final long bytes;
        Result(long d,int w,int h,long b){durationUs=d;width=w;height=h;bytes=b;}
    }

    static Result encodeFrames(MainActivity a, File input, File output, long startUs, long endUs,
                               boolean reverse, int bitrate, AtomicBoolean cancel) throws Exception {
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        MediaCodec enc=null; MediaMuxer mux=null;
        try {
            r.setDataSource(input.getAbsolutePath());
            int w=integer(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),0);
            int h=integer(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),0);
            w=Math.max(2,w&~1); h=Math.max(2,h&~1);
            long duration=longValue(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),0)*1000L;
            if(endUs<=0||endUs>duration) endUs=duration;
            if(startUs<0||startUs>=endUs) throw new IOException("invalid trim range");
            double fps=doubleValue(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE),30.0);
            if(fps<1||fps>120)fps=30.0;
            long step=Math.max(1,(long)(1_000_000.0/fps));
            ArrayList<Long> times=new ArrayList<>();
            for(long t=startUs;t<endUs;t+=step){times.add(t);if(times.size()>200000)break;}
            if(times.isEmpty())times.add(startUs);
            if(reverse)Collections.reverse(times);
            String mime="video/avc";
            MediaCodecInfo info=findEncoder(mime);
            if(info==null)throw new IOException("H.264 encoder unavailable");
            int color=findColorFormat(info,mime);
            if(color==0)throw new IOException("YUV encoder input unavailable");
            MediaFormat fmt=MediaFormat.createVideoFormat(mime,w,h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,color);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE,Math.max(180_000,bitrate));
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE,(int)Math.round(fps));
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);
            enc=MediaCodec.createByCodecName(info.getName());
            enc.configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack=-1; boolean muxStarted=false;
            MediaCodec.BufferInfo oi=new MediaCodec.BufferInfo();
            long outPts=0;
            for(Long originalTime:times){
                if(cancel.get())throw new IOException("cancelled");
                Bitmap raw=r.getFrameAtTime(originalTime,MediaMetadataRetriever.OPTION_CLOSEST);
                if(raw==null)continue;
                Bitmap b=raw;
                if(raw.getWidth()!=w||raw.getHeight()!=h)b=Bitmap.createScaledBitmap(raw,w,h,true);
                byte[] yuv=bitmapToYuv(b,color);
                if(b!=raw)b.recycle(); raw.recycle();
                boolean queued=false;
                while(!queued){
                    int ii=enc.dequeueInputBuffer(10_000);
                    if(ii>=0){ByteBuffer ib=enc.getInputBuffer(ii);if(ib==null||ib.capacity()<yuv.length)throw new IOException("encoder buffer too small");ib.clear();ib.put(yuv);enc.queueInputBuffer(ii,0,yuv.length,outPts,0);outPts+=step;queued=true;}
                    while(true){
                        int oo=enc.dequeueOutputBuffer(oi,0);
                        if(oo==MediaCodec.INFO_TRY_AGAIN_LATER)break;
                        if(oo==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                            if(!muxStarted){videoTrack=mux.addTrack(enc.getOutputFormat());mux.start();muxStarted=true;}
                            continue;
                        }
                        if(oo>=0){
                            if(muxStarted&&oi.size>0){ByteBuffer ob=enc.getOutputBuffer(oo);if(ob!=null){ob.position(oi.offset);ob.limit(oi.offset+oi.size);mux.writeSampleData(videoTrack,ob,oi);}}
                            enc.releaseOutputBuffer(oo,false);
                        }
                    }
                }
            }
            int ii;
            while((ii=enc.dequeueInputBuffer(10_000))<0){}
            enc.queueInputBuffer(ii,0,0,outPts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            boolean eos=false;
            while(!eos){int oo=enc.dequeueOutputBuffer(oi,10_000);if(oo==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(!muxStarted){videoTrack=mux.addTrack(enc.getOutputFormat());mux.start();muxStarted=true;}}else if(oo>=0){if(muxStarted&&oi.size>0){ByteBuffer ob=enc.getOutputBuffer(oo);if(ob!=null){ob.position(oi.offset);ob.limit(oi.offset+oi.size);mux.writeSampleData(videoTrack,ob,oi);}}eos=(oi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;enc.releaseOutputBuffer(oo,false);}}
            if(muxStarted)mux.stop();
            return new Result(times.size()*step,w,h,output.length());
        } finally {try{r.release();}catch(Exception ignored){}try{if(enc!=null){enc.stop();enc.release();}}catch(Exception ignored){}try{if(mux!=null){/* stopped above when successful */}}catch(Exception ignored){}}
    }

    private static int integer(String s,int d){try{return s==null?d:Integer.parseInt(s);}catch(Exception e){return d;}}
    private static long longValue(String s,long d){try{return s==null?d:Long.parseLong(s);}catch(Exception e){return d;}}
    private static double doubleValue(String s,double d){try{return s==null?d:Double.parseDouble(s);}catch(Exception e){return d;}}
    private static MediaCodecInfo findEncoder(String mime){for(MediaCodecInfo i:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos())if(i.isEncoder())for(String t:i.getSupportedTypes())if(mime.equalsIgnoreCase(t))return i;return null;}
    private static int findColorFormat(MediaCodecInfo i,String mime){MediaCodecInfo.CodecCapabilities c=i.getCapabilitiesForType(mime);int[] f=c.colorFormats;int[] pref={MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible};for(int p:pref)for(int x:f)if(x==p)return x;return f.length==0?0:f[0];}

    private static byte[] bitmapToYuv(Bitmap b,int color){
        int w=b.getWidth(),h=b.getHeight();int[] px=new int[w*h];b.getPixels(px,0,w,0,0,w,h);byte[] yuv=new byte[w*h*3/2];
        int y=0,uv=w*h;for(int j=0;j<h;j++)for(int i=0;i<w;i++){int c=px[j*w+i];int R=(c>>16)&255,G=(c>>8)&255,B=c&255;int yy=((66*R+129*G+25*B+128)>>8)+16;int u=(( -38*R-74*G+112*B+128)>>8)+128;int v=((112*R-94*G-18*B+128)>>8)+128;yuv[y++]=(byte)clamp(yy);if((j&1)==0&&(i&1)==0){if(color==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar||color==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible){int q=(j/2)*(w/2)+(i/2);int base=w*h;int uvSize=w*h/4;yuv[base+q]=(byte)clamp(u);yuv[base+uvSize+q]=(byte)clamp(v);}else{int q=uv+((j/2)*w+i);yuv[q]=(byte)clamp(u);yuv[q+1]=(byte)clamp(v);}}}
        return yuv;
    }
    private static int clamp(int x){return x<0?0:(x>255?255:x);}
}
