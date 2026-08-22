package com.vr3th.mediacompressor;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

/** Shared lightweight video encoder policy for compress/merge/trim operations. */
final class VideoEngine {
    private VideoEngine() {}

    static String bestVideoMime() {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (MimeTypes.VIDEO_H265.equalsIgnoreCase(type)) {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        if (info.isHardwareAccelerated()) return MimeTypes.VIDEO_H265;
                    } else {
                        String n = info.getName().toLowerCase(java.util.Locale.US);
                        if (!n.contains("google") && !n.contains("android") && !n.contains("sw")) return MimeTypes.VIDEO_H265;
                    }
                }
            }
        }
        return MimeTypes.VIDEO_H264;
    }

    static Transformer.Builder builder(Context context, String videoMime, String audioMime) {
        return builder(context, videoMime, audioMime, 2_000_000);
    }

    static Transformer.Builder builder(Context context, String videoMime, String audioMime, int videoBitrate) {
        DefaultEncoderFactory encoderFactory = new DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder()
                        .setBitrate(Math.max(128_000, videoBitrate))
                        .build())
                .build();
        Transformer.Builder b = new Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .setVideoMimeType(videoMime)
                .setPortraitEncodingEnabled(true)
                .setEnsureFileStartsOnVideoFrameEnabled(true);
        if (audioMime != null) b.setAudioMimeType(audioMime);
        return b;
    }
}
