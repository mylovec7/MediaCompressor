package com.vr3th.mediacompressor;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Session;
import java.io.File;
import java.util.Locale;

/**
 * Legacy-only decode/transcode path.
 *
 * Media3 remains the fast path. This class is used only after Media3 cannot
 * open the input. The encoder is selected from the actual FFmpeg build at
 * runtime instead of assuming that a particular encoder exists.
 */
final class LegacyVideoFallback {
    private final MainActivity a;
    private String lastError = "";

    LegacyVideoFallback(MainActivity a){this.a=a;}

    static boolean hasAudioTrack(File input) {
        if(input==null || !input.isFile()) return false;
        try {
            Session s = FFprobeKit.execute(
                    "-v error -select_streams a:0 -show_entries stream=index -of csv=p=0 " + q(input));
            return s != null && ReturnCode.isSuccess(s.getReturnCode())
                    && s.getOutput()!=null && !s.getOutput().trim().isEmpty();
        } catch(Throwable ignored) { return false; }
    }

    String getLastError(){ return lastError; }

    boolean transcode(File input, File output, long sourceBytes, long durationUs) {
        lastError = "";
        if(input==null || output==null || !input.isFile()) {
            lastError = "input missing";
            return false;
        }
        delete(output);

        long kbps = targetVideoBitrate(sourceBytes, durationUs);
        String encoder = chooseVideoEncoder();
        if(encoder==null){
            lastError = "No compatible video encoder is available in the legacy engine.";
            return false;
        }

        String in=q(input), out=q(output);

        // Keep audio when the source has it. FFmpeg decodes legacy audio (MP2,
        // AC3, etc.) and encodes it to AAC; Android is never asked to decode
        // the legacy audio stream.
        boolean audio = hasAudioTrack(input);
        String map = audio ? " -map 0:v:0 -map 0:a:0" : " -map 0:v:0 -an";
        String audioArgs = audio ? " -c:a aac -b:a 96k -ar 44100 -ac 2" : "";

        // Candidate order: hardware H.264 when the FFmpeg build exposes it,
        // then LGPL OpenH264, then the built-in MPEG-4 Part 2 encoder.
        String[] candidates = new String[]{encoder, "libopenh264", "mpeg4", "h264"};
        for(String candidate : candidates){
            if(candidate==null || !hasEncoder(candidate)) continue;
            delete(output);

            String videoArgs;
            if("libopenh264".equals(candidate)) {
                videoArgs = " -c:v libopenh264 -pix_fmt yuv420p -b:v " + kbps + "k -profile:v main";
            } else if("h264".equals(candidate)) {
                videoArgs = " -c:v h264 -pix_fmt yuv420p -b:v " + kbps + "k -profile:v main";
            } else {
                videoArgs = " -c:v mpeg4 -pix_fmt yuv420p -b:v " + kbps + "k";
            }

            String command = "-hide_banner -nostdin -y -loglevel error" +
                    " -threads 1 -probesize 100M -analyzeduration 100M" +
                    " -i " + in + map + " -sn -dn" +
                    videoArgs + audioArgs +
                    " -movflags +faststart -avoid_negative_ts make_zero " + out;

            if(run(command, output)) return true;
        }

        // Last compatibility attempt: let FFmpeg choose its normal H.264
        // encoder if one is available, without forcing a profile/pix_fmt.
        if(hasEncoder("h264")){
            delete(output);
            String command = "-hide_banner -nostdin -y -loglevel error" +
                    " -threads 1 -probesize 100M -analyzeduration 100M" +
                    " -i " + in + map + " -sn -dn" +
                    " -c:v h264 -b:v " + kbps + "k" + audioArgs +
                    " -movflags +faststart -avoid_negative_ts make_zero " + out;
            if(run(command, output)) return true;
        }

        if(lastError.isEmpty()) lastError="legacy transcode failed";
        delete(output);
        return false;
    }

    private String chooseVideoEncoder(){
        // Only select encoders that are actually listed by the bundled FFmpeg.
        // h264_mediacodec is an Android hardware codec name, not something we
        // should assume exists inside this FFmpeg build.
        if(hasEncoder("libopenh264")) return "libopenh264";
        if(hasEncoder("mpeg4")) return "mpeg4";
        if(hasEncoder("h264")) return "h264";
        return null;
    }

    private boolean hasEncoder(String encoder){
        try{
            // IMPORTANT: -encoders is an informational listing. Using
            // '-loglevel error' suppresses the very output we need to inspect.
            Session s=FFmpegKit.execute("-hide_banner -v quiet -encoders");
            if(s==null || !ReturnCode.isSuccess(s.getReturnCode())) return false;
            String text=s.getOutput()==null?"":s.getOutput();
            String needle=encoder.toLowerCase(Locale.US);
            String[] lines=text.toLowerCase(Locale.US).split("\\r?\\n");
            for(String line:lines){
                line=line.trim();
                // FFmpeg encoder rows look like: ' V..... h264 ...'
                if(!line.startsWith("v") && !line.startsWith("a") && !line.startsWith("s")) continue;
                String[] parts=line.split("\\s+");
                if(parts.length>=2 && parts[1].equals(needle)) return true;
            }
            return false;
        }catch(Throwable ignored){return false;}
    }

    private boolean run(String command, File output){
        try{
            Session s=FFmpegKit.execute(command);
            if(s!=null){
                String o=s.getOutput()==null?"":s.getOutput();
                String e=s.getFailStackTrace()==null?"":s.getFailStackTrace();
                if(!ReturnCode.isSuccess(s.getReturnCode())){
                    lastError=lastUsefulLine((o+"\n"+e).trim());
                    return false;
                }
            }
            if(output.isFile() && output.length()>0) return true;
            lastError="FFmpeg produced no output.";
            return false;
        }catch(Throwable t){
            lastError=t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();
            return false;
        }
    }

    private String lastUsefulLine(String s){
        String[] lines=s.replace('\r','\n').split("\\n");
        for(int i=lines.length-1;i>=0;i--){
            String x=lines[i].trim();
            if(!x.isEmpty()) return x.length()>220?x.substring(0,220)+"…":x;
        }
        return "legacy transcode failed";
    }

    private long targetVideoBitrate(long bytes,long durationUs){
        long src=0;
        try { if(bytes>0 && durationUs>0) src=Math.max(1,(bytes*8L*1000000L/durationUs)/1000L); }
        catch(Exception ignored) {}
        if(src<=0) src=1500;
        // Keep enough headroom for AAC/container overhead while targeting a
        // genuinely smaller result.
        return Math.max(240,Math.min(4500,(long)(src*0.68)));
    }

    private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static String q(File f){return q(f.getAbsolutePath());}
    private static void delete(File f){try{if(f!=null)f.delete();}catch(Exception ignored){}}
}
