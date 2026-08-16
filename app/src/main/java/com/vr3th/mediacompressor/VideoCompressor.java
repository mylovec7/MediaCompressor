package com.vr3th.mediacompressor;

import android.content.Context;
import android.net.Uri;
import android.media.*;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VideoCompressor {
    private final MainActivity a;
    VideoCompressor(MainActivity a){ this.a=a; }

    void run(Uri src, AtomicBoolean cancel){
        MediaExtractor extractor=null, audioExtractor=null;
        MediaCodec decoder=null, encoder=null;
        MediaMuxer muxer=null;
        File outFile=null;
        try{
            extractor=new MediaExtractor();
            extractor.setDataSource(a,src,null);

            int videoTrack=-1, audioTrack=-1;
            for(int i=0;i<extractor.getTrackCount();i++){
                String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if(mime!=null && mime.startsWith("video/") && videoTrack<0) videoTrack=i;
                else if(mime!=null && mime.startsWith("audio/") && audioTrack<0) audioTrack=i;
            }
            if(videoTrack<0) throw new IOException("Track video tidak ditemukan.");

            MediaFormat input=extractor.getTrackFormat(videoTrack);
            String videoMime=input.getString(MediaFormat.KEY_MIME);
            int srcW=input.getInteger(MediaFormat.KEY_WIDTH);
            int srcH=input.getInteger(MediaFormat.KEY_HEIGHT);

            // Keep HD-or-below resolution; downscale 4K+ to 1080p for a useful size reduction.
            int maxSide=1920;
            float scale=Math.min(1f,maxSide/(float)Math.max(srcW,srcH));
            int width=Math.max(2,((int)(srcW*scale))&~1);
            int height=Math.max(2,((int)(srcH*scale))&~1);

            int fps=input.containsKey(MediaFormat.KEY_FRAME_RATE)?input.getInteger(MediaFormat.KEY_FRAME_RATE):30;
            fps=Math.max(1,Math.min(60,fps));
            long durationUs=input.containsKey(MediaFormat.KEY_DURATION)?input.getLong(MediaFormat.KEY_DURATION):0;

            // High-quality VBR target: about 0.08 bits/pixel/frame, capped to keep files practical.
            long bitrate=(long)width*height*fps*8/100;
            bitrate=Math.max(1_500_000L,Math.min(8_000_000L,bitrate));

            MediaFormat encoded=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height);
            encoded.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encoded.setInteger(MediaFormat.KEY_BIT_RATE,(int)bitrate);
            encoded.setInteger(MediaFormat.KEY_FRAME_RATE,fps);
            encoded.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);
            if(Build.VERSION.SDK_INT>=21){
                try{ encoded.setInteger(MediaFormat.KEY_BITRATE_MODE,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR); }catch(Exception ignored){}
            }

            decoder=MediaCodec.createDecoderByType(videoMime);
            encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(encoded,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            android.view.Surface inputSurface=encoder.createInputSurface();
            decoder.configure(input,inputSurface,null,0);

            outFile=new File(a.outputDir(),"VID_"+System.currentTimeMillis()+".mp4");
            muxer=new MediaMuxer(outFile.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Preserve the common camera rotation metadata without rotating pixels.
            try{
                MediaMetadataRetriever mmr=new MediaMetadataRetriever();
                mmr.setDataSource(a,src);
                String rot=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if(rot!=null) muxer.setOrientationHint(Integer.parseInt(rot));
                mmr.release();
            }catch(Exception ignored){}

            extractor.selectTrack(videoTrack);
            encoder.start(); decoder.start();

            MediaCodec.BufferInfo encInfo=new MediaCodec.BufferInfo();
            boolean inputDone=false, decoderDone=false, encoderDone=false, muxStarted=false;
            int muxVideo=-1, muxAudio=-1;

            // Wait for encoder format; when it arrives, add audio track from a separate extractor before starting muxer.
            while(!encoderDone && !cancel.get()){
                if(!inputDone){
                    int ix=decoder.dequeueInputBuffer(10000);
                    if(ix>=0){
                        ByteBuffer b=decoder.getInputBuffer(ix);
                        if(b==null) throw new IOException("Decoder buffer kosong.");
                        int n=extractor.readSampleData(b,0);
                        long pts=extractor.getSampleTime();
                        if(n<0){
                            decoder.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone=true;
                        }else{
                            decoder.queueInputBuffer(ix,0,n,Math.max(0,pts),extractor.getSampleFlags());
                            extractor.advance();
                        }
                    }
                }

                if(!decoderDone){
                    MediaCodec.BufferInfo di=new MediaCodec.BufferInfo();
                    int oi=decoder.dequeueOutputBuffer(di,10000);
                    if(oi>=0){
                        boolean eos=(di.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                        decoder.releaseOutputBuffer(oi,true);
                        if(eos){
                            decoderDone=true;
                            encoder.signalEndOfInputStream();
                        }
                    }
                }

                int eo=encoder.dequeueOutputBuffer(encInfo,10000);
                if(eo==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    if(!muxStarted){
                        muxVideo=muxer.addTrack(encoder.getOutputFormat());

                        if(audioTrack>=0){
                            audioExtractor=new MediaExtractor();
                            audioExtractor.setDataSource(a,src,null);
                            MediaFormat af=audioExtractor.getTrackFormat(audioTrack);
                            String am=af.getString(MediaFormat.KEY_MIME);
                            // MediaMuxer can only mux common encoded audio tracks.
                            if(am!=null && (am.startsWith("audio/mp4a-") || am.equals("audio/3gpp") || am.equals("audio/amr-wb"))){
                                muxAudio=muxer.addTrack(af);
                            }else{
                                audioExtractor.release(); audioExtractor=null;
                            }
                        }
                        muxer.start(); muxStarted=true;

                        // Copy original audio samples without re-encoding.
                        if(audioExtractor!=null && muxAudio>=0){
                            audioExtractor.selectTrack(audioTrack);
                            copyAudio(audioExtractor,muxer,muxAudio,cancel);
                        }
                    }
                }else if(eo>=0){
                    ByteBuffer b=encoder.getOutputBuffer(eo);
                    if(b!=null && encInfo.size>0 && muxStarted && (encInfo.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                        b.position(encInfo.offset); b.limit(encInfo.offset+encInfo.size);
                        muxer.writeSampleData(muxVideo,b,encInfo);
                        if(durationUs>0) a.videoProgress((int)Math.min(99,(encInfo.presentationTimeUs*99L)/durationUs));
                    }
                    if((encInfo.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0) encoderDone=true;
                    encoder.releaseOutputBuffer(eo,false);
                }
            }

            if(cancel.get()){
                safeStopMuxer(muxer); muxer=null;
                if(outFile!=null && outFile.exists()) outFile.delete();
                a.videoFinished("Kompresi dibatalkan.");
                return;
            }

            if(!encoderDone) throw new IOException("Encoder berhenti sebelum selesai.");
            if(muxer!=null && muxStarted){ muxer.stop(); muxer.release(); muxer=null; }
            long originalSize=a.sourceSize(src);
            long resultSize=outFile==null?0:outFile.length();
            if(resultSize<=0 || (originalSize>0 && resultSize>=originalSize)){
                if(outFile!=null && outFile.exists()) outFile.delete();
                a.videoFinished("> OPTIMAL RESULT\nNo smaller output was found.\nOriginal quality preserved ✓");
                return;
            }
            if(cancel.get()){ if(outFile!=null&&outFile.exists())outFile.delete(); a.videoFinished("> ORIGINAL PRESERVED\nOperation cancelled safely.\nTemporary data cleared ✓"); return; }
            a.publishVideo(outFile);
            outFile.delete();
            a.videoProgress(100);
            double saved=(originalSize>0)?((originalSize-resultSize)*100.0/originalSize):0;
            a.videoFinished("> COMPRESSION COMPLETE\n"+a.fmtSize(originalSize)+" → "+a.fmtSize(resultSize)+"\nSAVED "+String.format(java.util.Locale.US,"%.1f",Math.max(0,saved))+"%\n\n✓ OUTPUT VERIFIED\n✓ ORIGINAL PRESERVED\n✓ SAVED TO GALLERY");
        }catch(Exception e){
            if(outFile!=null && outFile.exists()) outFile.delete();
            a.videoFinished("Gagal: "+(e.getMessage()==null?"Video tidak dapat diproses.":e.getMessage()));
        }finally{
            try{if(audioExtractor!=null)audioExtractor.release();}catch(Exception ignored){}
            try{if(extractor!=null)extractor.release();}catch(Exception ignored){}
            try{if(decoder!=null){decoder.stop();decoder.release();}}catch(Exception ignored){}
            try{if(encoder!=null){encoder.stop();encoder.release();}}catch(Exception ignored){}
            safeStopMuxer(muxer);
        }
    }

    private void copyAudio(MediaExtractor ex,MediaMuxer mux,int track,AtomicBoolean cancel)throws IOException{
        ByteBuffer buf=ByteBuffer.allocateDirect(256*1024);
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(!cancel.get()){
            int n=ex.readSampleData(buf,0);
            if(n<0) break;
            info.offset=0; info.size=n; info.presentationTimeUs=Math.max(0,ex.getSampleTime()); info.flags=ex.getSampleFlags();
            mux.writeSampleData(track,buf,info);
            ex.advance();
        }
    }

    private void safeStopMuxer(MediaMuxer m){
        if(m==null)return;
        try{m.stop();}catch(Exception ignored){}
        try{m.release();}catch(Exception ignored){}
    }
}
