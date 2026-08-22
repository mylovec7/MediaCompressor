package com.vr3th.mediacompressor;

import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Platform-only PCM decode/AAC encode path, including real sample reversal. */
final class NativeAudioEngine {
    private NativeAudioEngine(){}
    static void trim(File input, File output, long fromUs, long toUs, boolean reverse, AtomicBoolean cancel)throws Exception{
        MediaExtractor ex=new MediaExtractor();MediaCodec dec=null,enc=null;
        try{
            ex.setDataSource(input.getAbsolutePath());int track=-1;MediaFormat sf=null;
            for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String m=f.getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("audio/")){track=i;sf=f;break;}}
            if(track<0)throw new IOException("audio track unavailable");ex.selectTrack(track);
            String inMime=sf.getString(MediaFormat.KEY_MIME);dec=MediaCodec.createDecoderByType(inMime);dec.configure(sf,null,null,0);dec.start();
            int rate=sf.containsKey(MediaFormat.KEY_SAMPLE_RATE)?sf.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
            int ch=sf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?Math.max(1,Math.min(2,sf.getInteger(MediaFormat.KEY_CHANNEL_COUNT))):2;
            ByteArrayOutputStream pcm=new ByteArrayOutputStream();long duration=sf.containsKey(MediaFormat.KEY_DURATION)?sf.getLong(MediaFormat.KEY_DURATION):0;
            boolean inputEos=false,outputEos=false;MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();
            while(!outputEos){
                if(!inputEos){int ii=dec.dequeueInputBuffer(10_000);if(ii>=0){ByteBuffer ib=dec.getInputBuffer(ii);int n=ex.readSampleData(ib,0);if(n<0){dec.queueInputBuffer(ii,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEos=true;}else{dec.queueInputBuffer(ii,0,n,Math.max(0,ex.getSampleTime()),ex.getSampleFlags());ex.advance();}}}
                int oi=dec.dequeueOutputBuffer(bi,10_000);if(oi==MediaCodec.INFO_TRY_AGAIN_LATER)continue;if(oi==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)continue;if(oi>=0){ByteBuffer ob=dec.getOutputBuffer(oi);if(ob!=null&&bi.size>0){ob.position(bi.offset);ob.limit(bi.offset+bi.size);byte[] b=new byte[bi.size];ob.get(b);pcm.write(b);}outputEos=(bi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;dec.releaseOutputBuffer(oi,false);}if(cancel.get())throw new IOException("cancelled");
            }
            byte[] all=pcm.toByteArray();int bytesPerFrame=2*ch;int first=(int)Math.max(0,Math.min(Integer.MAX_VALUE,fromUs*rate/1_000_000L))*bytesPerFrame;int last=(int)Math.max(0,Math.min((long)all.length/bytesPerFrame,toUs*rate/1_000_000L))*bytesPerFrame;if(toUs<=0)last=all.length;first=Math.min(first,all.length);last=Math.min(Math.max(first,last),all.length);byte[] slice=Arrays.copyOfRange(all,first,last);if(reverse)reverseFrames(slice,bytesPerFrame);
            MediaFormat ef=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,ch);ef.setInteger(MediaFormat.KEY_BIT_RATE,128000);
            enc=MediaCodec.createEncoderByType("audio/mp4a-latm");enc.configure(ef,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);enc.start();
            MediaMuxer mux=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int at=-1;boolean started=false;int pos=0;long pts=0;MediaCodec.BufferInfo eo=new MediaCodec.BufferInfo();int chunk=4096*bytesPerFrame;
            while(pos<slice.length){
                if(cancel.get())throw new IOException("cancelled");
                int ii=enc.dequeueInputBuffer(10_000);
                if(ii>=0){ByteBuffer ib=enc.getInputBuffer(ii);int n=Math.min(chunk,slice.length-pos);ib.clear();ib.put(slice,pos,n);enc.queueInputBuffer(ii,0,n,pts,0);pts+=(long)(n/bytesPerFrame)*1_000_000L/rate;pos+=n;}
                while(true){int oo=enc.dequeueOutputBuffer(eo,0);if(oo==MediaCodec.INFO_TRY_AGAIN_LATER)break;if(oo==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(!started){at=mux.addTrack(enc.getOutputFormat());mux.start();started=true;}}else if(oo>=0){if(started&&eo.size>0)write(enc,mux,at,eo,oo);enc.releaseOutputBuffer(oo,false);}}
            }
            int ii;while((ii=enc.dequeueInputBuffer(10_000))<0){}enc.queueInputBuffer(ii,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            boolean eos=false;while(!eos){int oo=enc.dequeueOutputBuffer(eo,10_000);if(oo==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(!started){at=mux.addTrack(enc.getOutputFormat());mux.start();started=true;}}else if(oo>=0){if(started&&eo.size>0)write(enc,mux,at,eo,oo);eos=(eo.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;enc.releaseOutputBuffer(oo,false);}}
            if(!started)throw new IOException("AAC encoder produced no output");
            mux.stop();mux.release();
        }finally{try{ex.release();}catch(Exception ignored){}try{if(dec!=null){dec.stop();dec.release();}}catch(Exception ignored){}try{if(enc!=null){enc.stop();enc.release();}}catch(Exception ignored){}}
    }
    private static void reverseFrames(byte[] b,int frame){for(int l=0,r=b.length-frame;l<r;l+=frame,r-=frame){for(int i=0;i<frame;i++){byte t=b[l+i];b[l+i]=b[r+i];b[r+i]=t;}}}
    private static void write(MediaCodec e,MediaMuxer m,int tr,MediaCodec.BufferInfo bi,int index){ByteBuffer b=e.getOutputBuffer(index);if(b==null||bi.size<=0)return;b.position(bi.offset);b.limit(bi.offset+bi.size);m.writeSampleData(tr,b,bi);}
}
