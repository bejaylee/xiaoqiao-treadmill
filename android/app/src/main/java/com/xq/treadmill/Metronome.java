package com.xq.treadmill;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

/**
 * 步频节拍器。
 *
 * 两种模式：
 *   跟随速度（默认）—— 按当前速度推算步频：慢走 120、快走 136、慢跑 152、快跑 168+
 *   手动 —— 用户自己加减，不再随速度变
 *
 * 声音用内置 WAV（beat_accent.wav / beat_tick.wav）经 MediaPlayer 播放，
 * 走 STREAM_MUSIC 且音量拉满——比旧的 ToneGenerator 短音明显得多。
 */
public class Metronome {

    public static final int MIN_BPM = 80;
    public static final int MAX_BPM = 200;

    private static Metronome instance;

    public static synchronized Metronome get(Context c) {
        if (instance == null) instance = new Metronome(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Metronome(Context c) {
        this.ctx = c;
    }

    private MediaPlayer player;
    private boolean running;
    private boolean auto = true;
    private volatile int bpm = 120;
    private volatile double speedKmh;
    private int beats;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (bpm > 0) {
                playTick(beats % 4 == 0);
                beats++;
                ui.postDelayed(this, Math.max(120, 60000 / bpm));
            } else {
                ui.postDelayed(this, 500);   // 速度为 0 时不发声
            }
        }
    };

    private void playTick(boolean accent) {
        String asset = accent ? "voice/beat_accent.wav" : "voice/beat_tick.wav";
        try {
            if (player != null) {
                try { player.release(); } catch (Exception ignored) { }
                player = null;
            }
            AssetFileDescriptor afd = ctx.getAssets().openFd(asset);
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setVolume(1.0f, 1.0f);          // 满音量
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    m.release();
                }
            });
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception ignored) {
        }
    }

    /** 速度 → 步频 */
    public static int bpmForSpeed(double kmh) {
        if (kmh <= 0.05) return 0;
        int v = (int) Math.round(88 + kmh * 8);
        if (v < 100) v = 100;
        if (v > 180) v = 180;
        return v;
    }

    public void start() {
        if (running) return;
        running = true;
        beats = 0;
        ui.post(tick);
    }

    public void stop() {
        running = false;
        ui.removeCallbacks(tick);
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isAuto() {
        return auto;
    }

    public void setAuto(boolean a) {
        auto = a;
        if (auto) {
            int target = bpmForSpeed(speedKmh);
            if (target > 0) bpm = target;
            restartIfRunning();
        }
    }

    /** 课程/自由跑把当前速度喂进来；自动模式下据此调整节拍 */
    public void setSpeed(double kmh) {
        speedKmh = kmh;
        if (!auto) return;
        int target = bpmForSpeed(kmh);
        if (target == 0) {
            bpm = 0;
            return;
        }
        if (Math.abs(target - bpm) >= 2) {
            bpm = target;
            restartIfRunning();
        }
    }

    public int getBpm() {
        return bpm;
    }

    /** 手动设定（自动切到手动模式） */
    public void setBpm(int v) {
        auto = false;
        bpm = Math.max(MIN_BPM, Math.min(MAX_BPM, v));
        restartIfRunning();
    }

    private void restartIfRunning() {
        if (!running) return;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    public void release() {
        stop();
    }
}
