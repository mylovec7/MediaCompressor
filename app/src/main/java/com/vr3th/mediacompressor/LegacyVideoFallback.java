package com.vr3th.mediacompressor;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.FFprobeSession;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.File;
import java.util.Locale;

/**
 * Legacy-container fallback for Video Compress only.
 * It is deliberately invoked after the lightweight Media3/MediaCodec path fails.
 */
final class LegacyVideoFallback {
    private final MainActivity a;
    LegacyVideoFallback(MainActivity a){this.a=a;}

    static boolean hasAudioTrack(File input) {
        if(input==null || !input.isFile()) return false;
        try {
            String path = quoteStatic(input.getAbsolutePath());
            FFprobeSession s = FFprobeKit.execute("-v error -select_streams a:0 -show_entries stream=index -of csv=p=0 " + path);
            if(s != null && ReturnCode.isSuccess(s.getReturnCode())) {
                String out = s.getOutput();
                return out != null && !out.trim().isEmpty();
            }
        } catch(Throwable ignored) {}
        return false;
    }

    boolean transcode(File input, File output, long sourceBytes, long durationUs) {
        if(input==null || output==null || !input.isFile()) return false;
        safe(output);
        long targetVideoKbps = targetVideoBitrate(input, sourceBytes, durationUs);
        String in=quote(input.getAbsolutePath());
        String out=quote(output.getAbsolutePath());

        // Legacy path: let FFmpeg do the difficult container/codec decode, but
        // keep the encode hardware-first. Large probe values are intentional for
        // old containers whose headers often arrive late.
        String common = "-hide_banner -loglevel error -nostdin -y -threads 1" +
                " -probesize 100M -analyzeduration 100M -fflags +genpts -i " + in +
                " -map 0:v:0 -map 0:a:0? -sn -dn" +
                " -vf format=yuv420p -b:v " + targetVideoKbps +
                "k -max_muxing_queue_size 2048 -avoid_negative_ts make_zero";

        // The maintained FULL LGPL build includes the OpenH264 encoder where enabled.
        // Use it for legacy inputs instead of asking FFmpeg to reach Android
        // MediaCodec: that bridge is not guaranteed to be present in this artifact.
        String h264 = common +
                " -c:v libopenh264 -pix_fmt yuv420p -b:v " + targetVideoKbps +
                "k -c:a aac -b:a 96k -movflags +faststart " + out;
        if(run(h264) && validOutput(output)) return true;
        safe(output);

        // Last-resort LGPL built-in encoder. It is intentionally only a fallback
        // for legacy containers; the normal Media3 path remains H.264 hardware.
        String mpeg4 = common +
                " -c:v mpeg4 -pix_fmt yuv420p -b:v " + targetVideoKbps +
                "k -c:a aac -b:a 96k -movflags +faststart " + out;
        return run(mpeg4) && validOutput(output);
    }

    private boolean run(String command){
        try{
            com.arthenica.ffmpegkit.Session s=FFmpegKit.execute(command);
            return ReturnCode.isSuccess(s.getReturnCode());
        }catch(Throwable ignored){return false;}
    }

    private long targetVideoBitrate(File input,long bytes,long durationUs){
        long srcKbps=0;
        try{
            if(durationUs>0 && bytes>0) srcKbps=Math.max(1,(bytes*8L*1000000L/durationUs)/1000L);
        }catch(Exception ignored){}
        if(srcKbps<=0)srcKbps=1500;
        // Legacy fallback aims for a materially smaller candidate while retaining
        // enough bitrate for low-resolution material. Size guard below remains final authority.
        return Math.max(180,Math.min(5000,(long)(srcKbps*0.72)));
    }

    private boolean validOutput(File f){
        return f.isFile() && f.length()>0;
    }
    private String quote(String s){return quoteStatic(s);}
    private static String quoteStatic(String s){return "'"+s.replace("'","'\\''")+"'";}
    private void safe(File f){if(f!=null)try{f.delete();}catch(Exception ignored){}}
}
