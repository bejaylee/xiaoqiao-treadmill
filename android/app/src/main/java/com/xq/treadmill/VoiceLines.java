package com.xq.treadmill;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 由 tools/gen_voice.py 生成，请勿手改；文本与内置音频一一对应 */
public class VoiceLines {
    public static final String START_CUSTOM = "开始自定义课程，按计划节奏进行";
    public static final String FINISH = "训练完成，今天也坚持下来了";
    public static final String STOP = "训练已停止";
    public static final String SAMPLE = "语音播报已就绪，开始训练，加油";

    private static final Map<String, String> START = new HashMap<String, String>();
    private static final Map<String, String> SEG = new HashMap<String, String>();
    static {
        START.put("preset_aerobic_jog", "开始有氧慢跑，共 33 分钟，13 个分段");
        START.put("preset_fatburn_challenge45", "开始综合燃脂挑战跑，共 45 分钟，21 个分段");
        START.put("preset_fatburn_walk20", "开始20分钟燃脂走，共 20 分钟，13 个分段");
        START.put("preset_fatburn_walk30", "开始30分钟燃脂走，共 30 分钟，15 个分段");
        START.put("preset_hiit_fatburn", "开始HIIT强化燃脂跑，共 25 分钟，14 个分段");
        START.put("preset_hiit_varied", "开始HIIT变速跑，共 26 分钟，13 个分段");
        START.put("preset_miit_beginner", "开始MIIT初级燃脂跑，共 20 分钟，12 个分段");
        SEG.put("preset_aerobic_jog:0", "第 1 段，快走，5.0 公里每小时");
        SEG.put("preset_aerobic_jog:1", "第 2 段，快走，6.0 公里每小时");
        SEG.put("preset_aerobic_jog:10", "第 11 段，快走，7.0 公里每小时");
        SEG.put("preset_aerobic_jog:11", "第 12 段，快走，6.5 公里每小时");
        SEG.put("preset_aerobic_jog:12", "第 13 段，快走，6.0 公里每小时");
        SEG.put("preset_aerobic_jog:2", "第 3 段，慢跑，8.0 公里每小时");
        SEG.put("preset_aerobic_jog:3", "第 4 段，慢跑，9.0 公里每小时");
        SEG.put("preset_aerobic_jog:4", "第 5 段，慢跑，7.5 公里每小时");
        SEG.put("preset_aerobic_jog:5", "第 6 段，慢跑，8.0 公里每小时");
        SEG.put("preset_aerobic_jog:6", "第 7 段，慢跑，8.5 公里每小时");
        SEG.put("preset_aerobic_jog:7", "第 8 段，慢跑，9.0 公里每小时");
        SEG.put("preset_aerobic_jog:8", "第 9 段，慢跑，8.0 公里每小时");
        SEG.put("preset_aerobic_jog:9", "第 10 段，慢跑，7.5 公里每小时");
        SEG.put("preset_fatburn_challenge45:0", "第 1 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:1", "第 2 段，慢跑，9.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:10", "第 11 段，快跑，12.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:11", "第 12 段，慢跑，9.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:12", "第 13 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:13", "第 14 段，慢跑，10.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:14", "第 15 段，快跑，12.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:15", "第 16 段，慢跑，10.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:16", "第 17 段，快走，5.5 公里每小时");
        SEG.put("preset_fatburn_challenge45:17", "第 18 段，慢跑，10.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:18", "第 19 段，快跑，11.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:19", "第 20 段，慢跑，10.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:2", "第 3 段，快跑，11.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:20", "第 21 段，快走，5.5 公里每小时");
        SEG.put("preset_fatburn_challenge45:3", "第 4 段，慢跑，10.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:4", "第 5 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:5", "第 6 段，慢跑，9.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:6", "第 7 段，快跑，12.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:7", "第 8 段，慢跑，9.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:8", "第 9 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_challenge45:9", "第 10 段，慢跑，10.0 公里每小时");
        SEG.put("preset_fatburn_walk20:0", "第 1 段，慢走，4.0 公里每小时");
        SEG.put("preset_fatburn_walk20:1", "第 2 段，快走，5.5 公里每小时");
        SEG.put("preset_fatburn_walk20:10", "第 11 段，疾速走，7.5 公里每小时");
        SEG.put("preset_fatburn_walk20:11", "第 12 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_walk20:12", "第 13 段，慢走，4.0 公里每小时");
        SEG.put("preset_fatburn_walk20:2", "第 3 段，疾速走，7.0 公里每小时");
        SEG.put("preset_fatburn_walk20:3", "第 4 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_walk20:4", "第 5 段，疾速走，7.0 公里每小时");
        SEG.put("preset_fatburn_walk20:5", "第 6 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_walk20:6", "第 7 段，疾速走，7.5 公里每小时");
        SEG.put("preset_fatburn_walk20:7", "第 8 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_walk20:8", "第 9 段，疾速走，7.5 公里每小时");
        SEG.put("preset_fatburn_walk20:9", "第 10 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_walk30:0", "第 1 段，慢走，4.0 公里每小时");
        SEG.put("preset_fatburn_walk30:1", "第 2 段，快走，5.5 公里每小时");
        SEG.put("preset_fatburn_walk30:10", "第 11 段，疾速走，7.5 公里每小时");
        SEG.put("preset_fatburn_walk30:11", "第 12 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_walk30:12", "第 13 段，疾速走，7.5 公里每小时");
        SEG.put("preset_fatburn_walk30:13", "第 14 段，快走，5.5 公里每小时");
        SEG.put("preset_fatburn_walk30:14", "第 15 段，慢走，4.0 公里每小时");
        SEG.put("preset_fatburn_walk30:2", "第 3 段，疾速走，7.0 公里每小时");
        SEG.put("preset_fatburn_walk30:3", "第 4 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_walk30:4", "第 5 段，疾速走，7.0 公里每小时");
        SEG.put("preset_fatburn_walk30:5", "第 6 段，快走，5.5 公里每小时");
        SEG.put("preset_fatburn_walk30:6", "第 7 段，疾速走，7.5 公里每小时");
        SEG.put("preset_fatburn_walk30:7", "第 8 段，快走，6.0 公里每小时");
        SEG.put("preset_fatburn_walk30:8", "第 9 段，疾速走，7.5 公里每小时");
        SEG.put("preset_fatburn_walk30:9", "第 10 段，快走，6.0 公里每小时");
        SEG.put("preset_hiit_fatburn:0", "第 1 段，慢走，4.0 公里每小时");
        SEG.put("preset_hiit_fatburn:1", "第 2 段，快走，6.0 公里每小时");
        SEG.put("preset_hiit_fatburn:10", "第 11 段，慢跑，9.0 公里每小时");
        SEG.put("preset_hiit_fatburn:11", "第 12 段，快跑，12.0 公里每小时");
        SEG.put("preset_hiit_fatburn:12", "第 13 段，慢跑，8.0 公里每小时");
        SEG.put("preset_hiit_fatburn:13", "第 14 段，快走，5.5 公里每小时");
        SEG.put("preset_hiit_fatburn:2", "第 3 段，慢跑，9.0 公里每小时");
        SEG.put("preset_hiit_fatburn:3", "第 4 段，快跑，11.0 公里每小时");
        SEG.put("preset_hiit_fatburn:4", "第 5 段，慢跑，9.0 公里每小时");
        SEG.put("preset_hiit_fatburn:5", "第 6 段，快跑，12.0 公里每小时");
        SEG.put("preset_hiit_fatburn:6", "第 7 段，慢跑，9.0 公里每小时");
        SEG.put("preset_hiit_fatburn:7", "第 8 段，快跑，11.0 公里每小时");
        SEG.put("preset_hiit_fatburn:8", "第 9 段，慢跑，10.0 公里每小时");
        SEG.put("preset_hiit_fatburn:9", "第 10 段，快跑，11.0 公里每小时");
        SEG.put("preset_hiit_varied:0", "第 1 段，快走，5.5 公里每小时");
        SEG.put("preset_hiit_varied:1", "第 2 段，慢跑，7.0 公里每小时");
        SEG.put("preset_hiit_varied:10", "第 11 段，快走，6.0 公里每小时");
        SEG.put("preset_hiit_varied:11", "第 12 段，快走，5.5 公里每小时");
        SEG.put("preset_hiit_varied:12", "第 13 段，快走，5.0 公里每小时");
        SEG.put("preset_hiit_varied:2", "第 3 段，冲刺跑，11.0 公里每小时");
        SEG.put("preset_hiit_varied:3", "第 4 段，慢跑，7.0 公里每小时");
        SEG.put("preset_hiit_varied:4", "第 5 段，冲刺跑，11.0 公里每小时");
        SEG.put("preset_hiit_varied:5", "第 6 段，慢跑，7.0 公里每小时");
        SEG.put("preset_hiit_varied:6", "第 7 段，冲刺跑，11.0 公里每小时");
        SEG.put("preset_hiit_varied:7", "第 8 段，慢跑，7.0 公里每小时");
        SEG.put("preset_hiit_varied:8", "第 9 段，冲刺跑，11.0 公里每小时");
        SEG.put("preset_hiit_varied:9", "第 10 段，慢跑，7.0 公里每小时");
        SEG.put("preset_miit_beginner:0", "第 1 段，慢走，4.0 公里每小时");
        SEG.put("preset_miit_beginner:1", "第 2 段，快走，5.5 公里每小时");
        SEG.put("preset_miit_beginner:10", "第 11 段，慢跑，7.0 公里每小时");
        SEG.put("preset_miit_beginner:11", "第 12 段，快走，5.5 公里每小时");
        SEG.put("preset_miit_beginner:2", "第 3 段，慢跑，7.0 公里每小时");
        SEG.put("preset_miit_beginner:3", "第 4 段，快走，5.5 公里每小时");
        SEG.put("preset_miit_beginner:4", "第 5 段，慢跑，7.0 公里每小时");
        SEG.put("preset_miit_beginner:5", "第 6 段，快走，5.5 公里每小时");
        SEG.put("preset_miit_beginner:6", "第 7 段，慢跑，7.0 公里每小时");
        SEG.put("preset_miit_beginner:7", "第 8 段，快走，5.5 公里每小时");
        SEG.put("preset_miit_beginner:8", "第 9 段，慢跑，7.0 公里每小时");
        SEG.put("preset_miit_beginner:9", "第 10 段，快走，5.5 公里每小时");
    }

    public static String startFor(String courseId) {
        if (courseId == null) return START_CUSTOM;
        String t = START.get(courseId);
        return t != null ? t : START_CUSTOM;
    }

    public static String segFor(String courseId, int index) {
        if (courseId == null) return null;
        return SEG.get(courseId + ":" + index);
    }

    /** 自定义课程 / 自由跑：速度量化到 0.5，保证命中内置音频 */
    public static String segCustom(double speed) {
        double v = Math.round(Math.max(1.0, Math.min(12.0, speed)) * 2) / 2.0;
        return "进入新的分段，" + String.format(Locale.US, "%.1f", v) + " 公里每小时";
    }

    public static int count() {
        return START.size() + SEG.size();
    }
}
