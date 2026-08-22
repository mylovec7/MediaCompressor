package com.vr3th.mediacompressor;

import android.media.*;
import java.io.*;

final class NativeMuxer {
    private NativeMuxer(){}
    static void mux(File video, File audio, File out)throws Exception{
        MediaExtractor vx=new MediaExtractor(), ax=new MediaExtractor();MediaMuxer m=null;
        try{
            vx.setDataSource(video.getAbsolutePath());ax.setDataSource(audio.getAbsolutePath());
            int vt=-1,at=-1;MediaFormat vf=null,af=null;
            for(int i=0;i<vx.getTrackCount();i++){MediaFormat f=vx.getTrackFormat(i);String x=f.getString(MediaFormat.KEY_MIME);if(x!=null&&x.startsWith("video/")){vt=i;vf=f;break;}}
            for(int i=0;i<ax.getTrackCount();i++){MediaFormat f=ax.getTrackFormat(i);String x=f.getString(MediaFormat.KEY_MIME);if(x!=null&&x.startsWith("audio/")){at=i;af=f;break;}}
            if(vt<0||at<0)throw new IOException("tracks unavailable");
            m=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int vo=m.addTrack(vf),ao=m.addTrack(af);m.start();
            copy(vx,vt,m,vo);copy(ax,at,m,ao);
            m.stop();m.release();m=null;
        }finally{try{vx.release();}catch(Exception ignored){}try{ax.release();}catch(Exception ignored){}try{if(m!=null)m.release();}catch(Exception ignored){}}
    }
    private static void copy(MediaExtractor e,int track,MediaMuxer m,int out)throws IOException{
        e.selectTrack(track);ByteBufferPool pool=new ByteBufferPool(512*1024);while(true){java.nio.ByteBuffer b=pool.buffer();b.clear();int n=e.readSampleData(b,0);if(n<0)break;MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();bi.offset=0;bi.size=n;bi.presentationTimeUs=Math.max(0,e.getSampleTime());bi.flags=e.getSampleFlags();m.writeSampleData(out,b,bi);e.advance();}e.unselectTrack(track);
    }
    private static final class ByteBufferPool{final java.nio.ByteBuffer b;ByteBufferPool(int n){b=java.nio.ByteBuffer.allocateDirect(n);}java.nio.ByteBuffer buffer(){return b;}}
}
