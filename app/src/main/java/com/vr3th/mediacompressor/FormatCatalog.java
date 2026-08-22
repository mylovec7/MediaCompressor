package com.vr3th.mediacompressor;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.*;

/** Device capability catalog. This never claims an output format is encodable unless
 * an actual Android encoder for the mapped MIME exists on this device. */
final class FormatCatalog {
    static final class Item { final String name, mime, kind; Item(String n,String m,String k){name=n;mime=m;kind=k;} }

    static final Item[] VIDEO_CODECS = {
        new Item("H.264 / AVC","video/avc","VIDEO"), new Item("H.265 / HEVC","video/hevc","VIDEO"),
        new Item("AV1","video/av01","VIDEO"), new Item("VP8","video/x-vnd.on2.vp8","VIDEO"),
        new Item("VP9","video/x-vnd.on2.vp9","VIDEO"), new Item("MPEG-4 Part 2","video/mp4v-es","VIDEO"),
        new Item("H.263","video/3gpp","VIDEO"), new Item("MPEG-2","video/mpeg2","VIDEO"),
        new Item("MPEG-1","video/mpeg","VIDEO"), new Item("H.261","video/h261","VIDEO"),
        new Item("Theora","video/x-theora","VIDEO")
    };
    static final Item[] AUDIO_CODECS = {
        new Item("AAC","audio/mp4a-latm","AUDIO"), new Item("Opus","audio/opus","AUDIO"),
        new Item("Vorbis","audio/vorbis","AUDIO"), new Item("MP3","audio/mpeg","AUDIO"),
        new Item("FLAC","audio/flac","AUDIO"), new Item("AMR-NB","audio/3gpp","AUDIO"),
        new Item("AMR-WB","audio/amr-wb","AUDIO"), new Item("PCM","audio/raw","AUDIO")
    };
    static final String[] VIDEO_FORMATS = {
        "MP4","M4V","FMP4","MOV / QT","MKV / MK3D","WebM","3GP","3G2",
        "OGV / OGM","MPEG / MPG / MPE","M1V / M2V","TS / M2T","MTS / M2TS",
        "AVI","FLV / F4V","VOB / EVO","ASF / WMV","MXF","RM / RMVB","WTV"
    };
    static final String[] AUDIO_FORMATS = {
        "MP3","WAV / W64","OGG / OGA","M4A / M4R","FLAC","OPUS","AAC","WMA","MP2",
        "AMR","AIFF / AIF","CAF","VOC","AU","AC3","E-AC3","DTS","MKA","RA","WV","TTA",
        "SPX","SPH","NIST","APE","SHN","TAK","DSF / DFF"
    };

    static boolean hasEncoder(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo i : list.getCodecInfos()) {
                if (i.isEncoder()) for (String t : i.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static String report() {
        StringBuilder s=new StringBuilder();
        s.append("VIDEO FORMATS // INPUT FAMILIES\n");
        for(String x:VIDEO_FORMATS)s.append("• ").append(x).append("\n");
        s.append("\nAUDIO FORMATS // INPUT FAMILIES\n");
        for(String x:AUDIO_FORMATS)s.append("• ").append(x).append("\n");
        s.append("\nDEVICE VIDEO ENCODERS\n");
        for(Item x:VIDEO_CODECS)s.append(hasEncoder(x.mime)?"✓ ":"— ").append(x.name).append(" :: ").append(x.mime).append("\n");
        s.append("\nDEVICE AUDIO ENCODERS\n");
        for(Item x:AUDIO_CODECS)s.append(hasEncoder(x.mime)?"✓ ":"— ").append(x.name).append(" :: ").append(x.mime).append("\n");
        s.append("\n✓ = encoder actually exposed by this device\n— = no Android encoder exposed; not offered as output\n");
        return s.toString();
    }
}
