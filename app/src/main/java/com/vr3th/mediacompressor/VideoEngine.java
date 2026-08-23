package com.vr3th.mediacompressor;

import android.media.*;
import java.util.*;

/** Platform-only codec capability helper. No Media3 dependency. */
final class VideoEngine {
    private VideoEngine() {}
    static String bestVideoMime(){
        if(hasEncoder("video/hevc")) return "video/hevc";
        return "video/avc";
    }
    static boolean hasEncoder(String mime){
        for(MediaCodecInfo i:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(!i.isEncoder())continue;
            for(String t:i.getSupportedTypes())if(mime.equalsIgnoreCase(t))return true;
        }
        return false;
    }
}
