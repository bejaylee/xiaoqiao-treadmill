package com.xq.treadmill;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然语言 → 计划分段。
 *
 * 支持的写法（尽量贴近日常说法）：
 *   热身 5 分钟，然后 6 公里快走 20 分钟，最后 放松 5 分钟
 *   先慢走 3 分钟，再 5 公里跑 10 分钟
 *   4 公里 30 分钟 / 跑 8 分钟
 *   3分半 4码          ← "分半"、"码"（= km/h）也认
 *
 * 规则：
 *   · 每个换行 = 一天（可写「第2天:」前缀）
 *   · 每段 = 时长 + 速度；速度可以是数字（3公里/6码），也可以是词（慢走/快走/慢跑/快跑/冲刺/放松）
 *   · 缺时长按 3 分钟，缺速度按 5.0 km/h，并在提示里说明
 *   · 速度夹到 1.0 – 12.0，时长夹到 1 – 60 分钟
 */
public class NaturalPlan {

    public static class Result {
        public final List<List<PlanRunner.Seg>> days = new ArrayList<List<PlanRunner.Seg>>();
        public final List<String> notes = new ArrayList<String>();

        public boolean ok() {
            return !days.isEmpty();
        }

        public int totalMinutes() {
            return totalSeconds() / 60;
        }

        public int totalSeconds() {
            int n = 0;
            for (List<PlanRunner.Seg> d : days)
                for (PlanRunner.Seg s : d) n += s.seconds;
            return n;
        }
    }

    private static final Pattern P_HOUR = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:个)?\\s*(?:小时|钟头)");
    private static final Pattern P_HALF = Pattern.compile("(\\d+)\\s*分\\s*半");
    private static final Pattern P_MIN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:分钟|分|min)");
    private static final Pattern P_SEC = Pattern.compile("(\\d+)\\s*(?:秒钟|秒)");
    private static final Pattern P_SPEED = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:公里|千米|km|码|迈)");

    private static final String CLAUSE_SPLIT = "然后|接着|之后|最后|再|，|,|、|；|;|。|\\|";
    private static final String DAY_PREFIX = "^(第\\s*\\d+\\s*天|[Dd]\\s*\\d+|[Dd]ay\\s*\\d+)\\s*[:：]\\s*";

    /** 常见说法 → 速度（km/h）；数组顺序即优先级 */
    private static final Object[][] WORDS = {
            {"冲刺", 11.0}, {"极速", 11.0},
            {"快跑", 10.0}, {"跑步", 8.0}, {"慢跑", 8.0},
            {"快走", 6.0}, {"慢走", 4.0}, {"散步", 3.5},
            {"放松", 3.0}, {"冷身", 3.0}, {"整理", 3.0},
            {"热身", 4.0},
            {"走", 4.0}, {"跑", 8.0},
    };

    public static Result parse(String text) {
        Result r = new Result();
        if (text == null) {
            r.notes.add("还没有输入内容");
            return r;
        }
        String[] lines = text.split("[\\n\\r]+");
        int dayNo = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            line = line.replaceFirst(DAY_PREFIX, "");
            List<PlanRunner.Seg> segs = parseDay(line, r.notes);
            if (!segs.isEmpty()) {
                dayNo++;
                r.days.add(segs);
            }
        }
        if (r.days.isEmpty()) r.notes.add("没看懂这段描述，试试「热身5分钟，然后6公里快走20分钟」");
        return r;
    }

    public static List<PlanRunner.Seg> parseDay(String line, List<String> notes) {
        List<PlanRunner.Seg> out = new ArrayList<PlanRunner.Seg>();
        String[] clauses = line.split(CLAUSE_SPLIT);
        for (String c : clauses) {
            String s = c.trim();
            if (s.isEmpty()) continue;
            if (s.matches("^[共总计约]+\\s*\\d+\\s*(组|次|轮).*")) continue;   // "共6组" 之类
            if (s.matches("^[开始结束完成]+$")) continue;

            double minutes = -1;
            Matcher mh = P_HOUR.matcher(s);
            Matcher mhalf = P_HALF.matcher(s);
            Matcher mm = P_MIN.matcher(s);
            Matcher ms = P_SEC.matcher(s);
            if (mh.find()) {
                minutes = Double.parseDouble(mh.group(1)) * 60;
            } else if (mhalf.find()) {
                minutes = Double.parseDouble(mhalf.group(1)) + 0.5;
            } else if (mm.find()) {
                minutes = Double.parseDouble(mm.group(1));
            } else if (ms.find()) {
                minutes = Double.parseDouble(ms.group(1)) / 60.0;
            }

            double speed = -1;
            Matcher msp = P_SPEED.matcher(s);
            if (msp.find()) {
                speed = Double.parseDouble(msp.group(1));
            } else {
                for (Object[] kv : WORDS) {
                    if (s.contains((String) kv[0])) {
                        speed = (Double) kv[1];
                        break;
                    }
                }
            }

            if (minutes < 0 && speed < 0) continue;     // 这句话里没有任何可用信息

            if (minutes < 0) {
                minutes = 3;
                if (notes != null) notes.add("「" + s + "」没写时长，按 3 分钟算");
            }
            if (speed < 0) {
                speed = 5.0;
                if (notes != null) notes.add("「" + s + "」没写速度，按 5.0 km/h 算");
            }

            // M11：按秒保真，3分半就是 210 秒，不再四舍五入成 4 分钟
            int seconds = (int) Math.round(minutes * 60);
            if (seconds < 60) seconds = 60;
            if (seconds > 3600) seconds = 3600;
            if (speed < 1) speed = 1;
            if (speed > 12) speed = 12;
            out.add(PlanRunner.Seg.ofSeconds(speed, seconds));
        }
        return out;
    }

    /** 生成人类可读的预览，例如 "3.0 km/h · 3 分钟" */

    public static int seconds(List<PlanRunner.Seg> segs) {
        int n = 0;
        for (PlanRunner.Seg s : segs) n += s.seconds;
        return n;
    }

    // ------------------------------------------------------------ 模板
    public static final String[] TEMPLATES = {
            "热身 5 分钟，然后 6 公里快走 20 分钟，最后 放松 5 分钟",
            "热身 5 分钟，9 公里跑 2 分钟，4 公里走 2 分钟，9 公里跑 2 分钟，4 公里走 2 分钟，放松 5 分钟",
            "4 公里走 5 分钟，3 公里慢走 5 分钟",
    };

    public static final String[] TEMPLATE_NAMES = {"燃脂 30 分钟", "间歇 20 分钟", "放松 10 分钟"};
}
