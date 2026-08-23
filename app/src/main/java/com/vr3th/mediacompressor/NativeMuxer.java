package com.vr3th.mediacompressor;

import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

final class NativeMuxer {
    private NativeMuxer(){}

    static void mux(File video, File audio, File out)throws Exception{
        MediaExtractor vx=new MediaExtractor(), ax=new MediaExtractor(); MediaMuxer m=null;
        try{
            vx.setDataSource(video.getAbsolutePath()); ax.setDataSource(audio.getAbsolutePath());
            int vt=find(vx,"video/"), at=find(ax,"audio/");
            if(vt<0||at<0)throw new IOException("tracks unavailable");
            m=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vo=m.addTrack(vx.getTrackFormat(vt)), ao=m.addTrack(ax.getTrackFormat(at)); m.start();
            copy(vx,vt,m,vo,0); copy(ax,at,m,ao,0); m.stop(); m.release(); m=null;
        }finally{try{vx.release();}catch(Exception ignored){}try{ax.release();}catch(Exception ignored){}try{if(m!=null)m.release();}catch(Exception ignored){}}
    }

    /** Concatenates already-normalized MP4 segments. Video and audio timestamps are rebuilt. */
    static void concat(List<File> files, File out)throws Exception{
        if(files==null||files.size()<2)throw new IOException("need at least two segments");
        MediaMuxer mux=null; MediaExtractor firstV=new MediaExtractor(), firstA=new MediaExtractor();
        try{
            firstV.setDataSource(files.get(0).getAbsolutePath());
            int vt=find(firstV,"video/"), at=find(firstV,"audio/");
            if(vt<0)throw new IOException("normalized video track unavailable");
            MediaFormat vf=firstV.getTrackFormat(vt), af=at>=0?firstV.getTrackFormat(at):null;
            mux=new MediaMuxer(out.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vo=mux.addTrack(vf), ao=af==null?-1:mux.addTrack(af); mux.start();
            long offset=0;
            for(File f:files){
                MediaExtractor ex=new MediaExtractor(); ex.setDataSource(f.getAbsolutePath());
                int vi=find(ex,"video/"), ai=find(ex,"audio/"); if(vi<0)throw new IOException("segment video unavailable");
                long segDur=duration(ex,vi);
                copy(ex,vi,mux,vo,offset); if(ao>=0&&ai>=0)copy(ex,ai,mux,ao,offset); ex.release();
                offset+=Math.max(1,segDur);
            }
            mux.stop(); mux.release(); mux=null;
        }finally{try{firstV.release();}catch(Exception ignored){}try{firstA.release();}catch(Exception ignored){}try{if(mux!=null)mux.release();}catch(Exception ignored){}}
    }

    private static int find(MediaExtractor e,String prefix){for(int i=0;i<e.getTrackCount();i++){String m=e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith(prefix))return i;}return -1;}
    private static long duration(MediaExtractor e,int track){MediaFormat f=e.getTrackFormat(track);return f.containsKey(MediaFormat.KEY_DURATION)?f.getLong(MediaFormat.KEY_DURATION):0;}

    private static void copy(MediaExtractor e,int track,MediaMuxer m,int out,long offset)throws IOException{
        e.selectTrack(track); MediaFormat f=e.getTrackFormat(track);
        int max=4*1024*1024; if(f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))max=Math.max(max,f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)*2);
        ByteBuffer b=ByteBuffer.allocateDirect(Math.min(max,16*1024*1024)); long first=-1;
        while(true){b.clear();int n=e.readSampleData(b,0);if(n<0)break;long pts=e.getSampleTime();if(first<0)first=Math.max(0,pts);MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();bi.offset=0;bi.size=n;bi.presentationTimeUs=offset+Math.max(0,pts-first);bi.flags=e.getSampleFlags();m.writeSampleData(out,b,bi);e.advance();}
        e.unselectTrack(track);
    }
}
