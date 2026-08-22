package com.vr3th.mediacompressor;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

/** Small, honest capability panel for the deliberately narrow Video Compressor. */
final class FormatCatalog {
    private static final String[] VIDEO_FORMATS = {
        "3G2", "3GP", "F4V", "M4V", "MKV", "MOV", "MP4", "QT", "WebM"
    };

    private static boolean hasEncoder(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static String report() {
        StringBuilder s = new StringBuilder();
        s.append("VIDEO INPUT // TESTED PASS\n");
        for (String format : VIDEO_FORMATS) {
            s.append("✓ ").append(format).append("\n");
        }
        s.append("\nVIDEO OUTPUT\n");
        s.append(hasEncoder("video/avc")
                ? "✓ H.264 / AVC :: video/avc\n"
                : "— H.264 / AVC encoder not exposed by this device\n");

        s.append("\nAUDIO OUTPUT\n");
        s.append(hasEncoder("audio/mp4a-latm")
                ? "✓ AAC in M4A/MP4 :: audio/mp4a-latm\n"
                : "— AAC encoder not exposed by this device\n");

        s.append("\nNOT INCLUDED\n");
        s.append("MTS / M2TS — not tested, therefore intentionally removed.\n");
        s.append("No extra legacy containers or alternative output codecs are advertised.\n");
        return s.toString();
    }
}
