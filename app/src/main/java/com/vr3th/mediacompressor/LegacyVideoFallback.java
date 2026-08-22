package com.vr3th.mediacompressor;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Session;
import java.io.File;
import java.util.Locale;

/**
 * Legacy-only decoder/transcoder for containers that Android/Media3 cannot
 * reliably open. The native Media3 path remains the fast path.
 */
final class LegacyVideoFallback {
    private final MainActivity a;
    LegacyVideoFallback(MainActivity a){this.a=a;}

    static boolean hasAudioTrack(File input) {
        if(input==null || !input.isFile()) return false;
        try {
            Session s = FFprobeKit.execute("-v error -select_streams a:0 -show_entries stream=index -of csv=p=0 " + q(input));
            return s != null && ReturnCode.isSuccess(s.getReturnCode()) && s.getOutput()!=null && !s.getOutput().trim().isEmpty();
        } catch(Throwable ignored) { return false; }
    }

    boolean transcode(File input, File output, long sourceBytes, long durationUs) {
        if(input==null || output==null || !input.isFile()) return false;
        delete(output);

        long kbps = targetVideoBitrate(sourceBytes, durationUs);
        String in=q(input), out=q(output);

        // Candidate 1: simplest broadly-compatible LGPL encode path. Do not use
        // FFmpeg's MediaCodec wrapper here; Android hardware capability is not
        // guaranteed to be exposed through the FFmpeg command-line layer.
        String base = "-hide_banner -nostdin -y -loglevel error -threads 1" +
                " -probesize 100M -analyzeduration 100M -i " + in +
                " -map 0:v:0 -map 0:a:0? -sn -dn" +
                " -c:v mpeg4 -pix_fmt yuv420p -b:v " + kbps + "k" +
                " -c:a aac -b:a 96k -ar 44100 -ac 2" +
                " -movflags +faststart " + out;
        if(run(base, output)) return true;
        delete(output);

        // Candidate 2: some old streams fail when the output is forced through
        // the first filter/mux settings. Keep the fallback deliberately simple.
        String simple = "-hide_banner -nostdin -y -loglevel error -threads 1 -i " + in +
                " -map 0:v:0 -map 0:a:0? -sn -dn" +
                " -c:v mpeg4 -q:v 5 -c:a aac -b:a 96k -movflags +faststart " + out;
        if(run(simple, output)) return true;
        delete(output);

        return false;
    }

    private boolean run(String command, File output){
        try {
            Session s=FFmpegKit.execute(command);
            if(!ReturnCode.isSuccess(s.getReturnCode())) return false;
            return output.isFile() && output.length()>0;
        } catch(Throwable ignored) { return false; }
    }

    private long targetVideoBitrate(long bytes,long durationUs){
        long src=0;
        try { if(bytes>0 && durationUs>0) src=Math.max(1,(bytes*8L*1000000L/durationUs)/1000L); }
        catch(Exception ignored) {}
        if(src<=0) src=1500;
        return Math.max(180,Math.min(5000,(long)(src*0.72)));
    }

    private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static String q(File f){return q(f.getAbsolutePath());}
    private static void delete(File f){try{if(f!=null)f.delete();}catch(Exception ignored){}}
}
