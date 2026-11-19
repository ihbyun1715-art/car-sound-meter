package com.carsound.meter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 테스트 신호를 재생하면서 휴대폰 내장 마이크로 동시에 녹음한다. */
public final class AudioEngine {
    public interface Progress { void on(String msg); }

    public static final int FS = 48000;

    /** 마지막 측정의 입출력 정보 */
    public static String lastInfo = "";
    public static boolean lastOutputIsPhoneSpeaker = false;
    public static boolean lastInputNotBuiltin = false;
    public static String lastSourceName = "";
    /** 진단으로 고른 입력 방식. -1이면 자동 */
    public static int chosenSource = -1;

    /** 진단해 볼 입력 방식들 */
    public static int[] candidateSources() {
        List<Integer> s = new ArrayList<>();
        s.add(MediaRecorder.AudioSource.UNPROCESSED);
        if (Build.VERSION.SDK_INT >= 29) s.add(MediaRecorder.AudioSource.VOICE_PERFORMANCE);
        s.add(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        s.add(MediaRecorder.AudioSource.CAMCORDER);
        s.add(MediaRecorder.AudioSource.MIC);
        int[] a = new int[s.size()];
        for (int i = 0; i < a.length; i++) a[i] = s.get(i);
        return a;
    }

    public static String sourceName(int src) {
        switch (src) {
            case MediaRecorder.AudioSource.UNPROCESSED: return "가공 없음";
            case MediaRecorder.AudioSource.VOICE_PERFORMANCE: return "공연 녹음";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "음성 인식";
            case MediaRecorder.AudioSource.CAMCORDER: return "영상 녹화";
            case MediaRecorder.AudioSource.MIC: return "일반 마이크";
            default: return "입력 " + src;
        }
    }

    static int autoSource(Context ctx) {
        return supportsUnprocessed(ctx) ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;
    }

    private AudioEngine() {}

    public static boolean supportsUnprocessed(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        return "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
    }

    static AudioDeviceInfo builtinMic(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return d;
        return null;
    }

    static String typeName(AudioDeviceInfo d) {
        if (d == null) return "알 수 없음";
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "내장 마이크";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "휴대폰 스피커";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "통화 스피커";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "블루투스 미디어";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "블루투스 통화";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "USB";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_AUX_LINE: return "유선(AUX)";
            default: return "기타(" + d.getType() + ")";
        }
    }

    @SuppressLint("MissingPermission")
    static AudioRecord openRecorder(int source) {
        try {
            return openRecorderUnsafe(source);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    static AudioRecord openRecorderUnsafe(int source) {
        int min = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        AudioRecord r = new AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(FS)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build())
                .setBufferSizeInBytes(Math.max(min * 4, FS * 4))
                .build();
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            return null;
        }
        return r;
    }

    /** 측정 한 번: 신호 재생 + 녹음. 녹음된 샘플(48kHz 모노)을 돌려준다. */
    public static float[] record(Context ctx, boolean lr, Progress cb) throws Exception {
        int src = chosenSource >= 0 ? chosenSource : autoSource(ctx);
        AudioRecord rec = openRecorder(src);
        if (rec == null && src != autoSource(ctx)) { src = autoSource(ctx); rec = openRecorder(src); }
        if (rec == null && src != MediaRecorder.AudioSource.VOICE_RECOGNITION) { src = MediaRecorder.AudioSource.VOICE_RECOGNITION; rec = openRecorder(src); }
        if (rec == null) throw new IllegalStateException("마이크를 열 수 없습니다. 다른 앱이 마이크를 쓰고 있는지 확인하세요.");
        return play(ctx, rec, src, Dsp.stimulus(FS, lr), "측정 중… 조용히 해 주세요 ", cb);
    }

    /** 진단용: 지정한 입력 방식으로 녹음. 열리지 않으면 null */
    public static float[] recordWith(Context ctx, int src, float[][] st, String label, Progress cb) throws Exception {
        AudioRecord rec = openRecorder(src);
        if (rec == null) return null;
        return play(ctx, rec, src, st, label, cb);
    }

    static float[] play(Context ctx, AudioRecord rec, int src, float[][] st, String label, Progress cb) throws Exception {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        String srcName = sourceName(src);

        AudioDeviceInfo mic = builtinMic(am);
        if (mic != null) rec.setPreferredDevice(mic);

        List<AudioEffect> fx = new ArrayList<>();
        int sid = rec.getAudioSessionId();
        try {
            if (AutomaticGainControl.isAvailable()) { AudioEffect e = AutomaticGainControl.create(sid); if (e != null) { e.setEnabled(false); fx.add(e); } }
            if (NoiseSuppressor.isAvailable()) { AudioEffect e = NoiseSuppressor.create(sid); if (e != null) { e.setEnabled(false); fx.add(e); } }
            if (AcousticEchoCanceler.isAvailable()) { AudioEffect e = AcousticEchoCanceler.create(sid); if (e != null) { e.setEnabled(false); fx.add(e); } }
        } catch (RuntimeException ignored) { }

        int len = st[0].length;
        float[] inter = new float[len * 2];
        for (int i = 0; i < len; i++) { inter[2 * i] = st[0][i]; inter[2 * i + 1] = st[1][i]; }

        int tmin = AudioTrack.getMinBufferSize(FS, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        AudioTrack tr = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(FS)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(tmin * 4, 16384))
                .build();

        final int total = len + (int) (2.0 * FS);
        final float[] out = new float[total];
        final int[] got = {0};
        final boolean[] stop = {false};
        final AudioRecord frec = rec;
        Thread reader = new Thread(() -> {
            float[] buf = new float[2048];
            int pos = 0;
            while (pos < total && !stop[0]) {
                int n = frec.read(buf, 0, Math.min(buf.length, total - pos), AudioRecord.READ_BLOCKING);
                if (n <= 0) break;
                System.arraycopy(buf, 0, out, pos, n);
                pos += n;
            }
            got[0] = pos;
        });
        try {
            rec.startRecording();
            reader.start();
            Thread.sleep(400);
            tr.play();
            int w = 0, lastSec = -1;
            double dur = len / (double) FS;
            while (w < inter.length) {
                int n = tr.write(inter, w, Math.min(8192, inter.length - w), AudioTrack.WRITE_BLOCKING);
                if (n < 0) throw new IllegalStateException("재생 오류 " + n);
                w += n;
                int left = (int) Math.ceil(dur - (w / 2.0) / FS) + 2;
                if (left != lastSec) { lastSec = left; cb.on(label + left + "초"); }
            }
            reader.join(6000);
            stop[0] = true;
            reader.join(1000);

            AudioDeviceInfo in = rec.getRoutedDevice(), outDev = tr.getRoutedDevice();
            lastOutputIsPhoneSpeaker = outDev != null && outDev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            lastInputNotBuiltin = in != null && in.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC;
            lastSourceName = srcName;
            lastInfo = "입력: " + typeName(in) + " · " + srcName + " 방식"
                    + "\n출력: " + typeName(outDev) + (outDev != null && outDev.getProductName() != null ? " (" + outDev.getProductName() + ")" : "")
                    + "\n샘플레이트: " + FS + " Hz";
        } finally {
            stop[0] = true;
            try { tr.stop(); } catch (IllegalStateException ignored) { }
            tr.release();
            try { rec.stop(); } catch (IllegalStateException ignored) { }
            rec.release();
            for (AudioEffect e : fx) e.release();
        }
        return Arrays.copyOf(out, got[0]);
    }
}
