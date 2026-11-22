package com.xq.treadmill;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 语音播报：播放 App 内置音频，完全离线，不依赖设备 TTS 引擎。
 *
 * 音频由 tools/gen_voice.py 用 zh-CN-YunxiNeural（中文男声）预生成，
 * assets/voice/index.tsv 是「播报文案 -> mp3」的索引；
 * 文案常量由 VoiceLines.java 提供，保证与音频一一对应。
 */
public class VoiceCoach {

    private static VoiceCoach instance;

    private final Context ctx;
    private final Map<String, String> index = new HashMap<String, String>();
    private MediaPlayer player;
    private boolean enabled = true;

    public static synchronized VoiceCoach get(Context c) {
        if (instance == null) instance = new VoiceCoach(c.getApplicationContext());
        return instance;
    }

    private VoiceCoach(Context c) {
        this.ctx = c;
        loadIndex();
    }

    private void loadIndex() {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(ctx.getAssets().open("voice/index.tsv"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.lastIndexOf('\t');
                if (tab > 0) index.put(line.substring(0, tab), line.substring(tab + 1));
            }
            r.close();
            Logs.add("内置语音索引：" + index.size() + " 句（离线男声）");
        } catch (Exception e) {
            Logs.add("语音索引读取失败：" + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return !index.isEmpty();
    }

    public String info() {
        if (index.isEmpty()) return "内置语音缺失，请检查 assets/voice";
        return "内置中文男声 · " + index.size() + " 句 · 完全离线";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) stop();
    }

    /** 传 VoiceLines 生成的文案；未收录时会记日志，不会静默跳过 */
    public void speak(String text) {
        if (!enabled || text == null || text.isEmpty()) return;
        String file = index.get(text);
        if (file == null) {
            Logs.add("无内置语音：" + text);
            return;
        }
        play(file);
    }

    private void play(String file) {
        stop();
        try {
            AssetFileDescriptor afd = ctx.getAssets().openFd("voice/" + file);
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    m.release();
                    if (player == m) player = null;
                }
            });
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            Logs.add("语音播放失败：" + e.getMessage());
        }
    }

    public void stop() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    public static synchronized void shutdownIfAny() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    public void shutdown() {
        stop();
        instance = null;
    }
}
