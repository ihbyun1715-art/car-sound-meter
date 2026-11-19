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
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        boolean unp = supportsUnprocessed(ctx);
        String srcName = unp ? "UNPROCESSED(가공 없음)" : "VOICE_RECOGNITION(자동 보정 최소)";
        AudioRecord rec = openRecorder(unp ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION);
        if (rec == null && unp) {
            rec = openRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            srcName = "VOICE_RECOGNITION(자동 보정 최소)";
        }
        if (rec == null) throw new IllegalStateException("마이크를 열 수 없습니다. 다른 앱이 마이크를 쓰고 있는지 확인하세요.");

        AudioDeviceInfo mic = builtinMic(am);
        if (mic != null) rec.setPreferredDevice(mic);

        List<AudioEffect> fx = new ArrayList<>();
        int sid = rec.getAudioSessionId();
        try {
            if (AutomaticGainControl.isAvailable()) { AudioEffect e = AutomaticGainControl.create(sid); if (e != null) { e.setEnabled(false); fx.add(e); } }
            if (NoiseSuppressor.isAvailable()) { AudioEffect e = NoiseSuppressor.create(sid); if (e != null) { e.setEnabled(false); fx.add(e); } }
            if (AcousticEchoCanceler.isAvailable()) { AudioEffect e = AcousticEchoCanceler.create(sid); if (e != null) { e.setEnabled(false); fx.add(e); } }
        } catch (RuntimeException ignored) { }

        float[][] st = Dsp.stimulus(FS, lr);
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
                if (left != lastSec) { lastSec = left; cb.on("측정 중… 조용히 해 주세요 " + left + "초"); }
            }
            reader.join(6000);
            stop[0] = true;
            reader.join(1000);

            AudioDeviceInfo in = rec.getRoutedDevice(), outDev = tr.getRoutedDevice();
            lastOutputIsPhoneSpeaker = outDev != null && outDev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            lastInputNotBuiltin = in != null && in.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC;
            lastInfo = "입력: " + typeName(in) + " · " + srcName
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
