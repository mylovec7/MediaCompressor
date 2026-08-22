package com.vr3th.mediacompressor;

import com.arthenica.ffmpegkit.FFmpegKit;
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

    boolean transcode(File input, File output, long sourceBytes, long durationUs) {
        if(input==null || output==null || !input.isFile()) return false;
        safe(output);
        long targetVideoKbps = targetVideoBitrate(input, sourceBytes, durationUs);
        String in=quote(input.getAbsolutePath());
        String out=quote(output.getAbsolutePath());

        // Hardware-first FFmpeg path. If the device exposes no MediaCodec encoder,
        // fall back to the LGPL H.264 encoder shipped by the selected FFmpeg build.
        String hw = "-hide_banner -loglevel error -nostdin -y -threads 1 -i "+in+
                " -map 0:v:0 -map 0:a:0? -sn -dn -c:v h264_mediacodec -b:v "+targetVideoKbps+
                "k -pix_fmt yuv420p -c:a aac -b:a 96k -movflags +faststart "+out;
        if(run(hw) && validOutput(output)) return true;
        safe(output);

        String sw = "-hide_banner -loglevel error -nostdin -y -threads 1 -i "+in+
                " -map 0:v:0 -map 0:a:0? -sn -dn -c:v libopenh264 -b:v "+targetVideoKbps+
                "k -pix_fmt yuv420p -c:a aac -b:a 96k -movflags +faststart "+out;
        return run(sw) && validOutput(output);
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
    private String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    private void safe(File f){if(f!=null)try{f.delete();}catch(Exception ignored){}}
}
