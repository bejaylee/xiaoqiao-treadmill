package com.xq.treadmill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 预设课程库（由 tools/gen_courses.py 从 courses/recommended.json 生成，勿手改）。
 * 数据来源：推荐课程的分段表，逐段复刻。
 */
public class CourseLibrary {

    public static class Step {
        public final String phase;
        public final String action;
        public final double speed;
        public final int seconds;

        Step(String phase, String action, double speed, int seconds) {
            this.phase = phase;
            this.action = action;
            this.speed = speed;
            this.seconds = seconds;
        }
    }

    public static class Course {
        public final String id;
        public final String name;
        public final String level;
        public final String kcal;
        public final List<Step> steps;

        Course(String id, String name, String level, String kcal, List<Step> steps) {
            this.id = id;
            this.name = name;
            this.level = level;
            this.kcal = kcal;
            this.steps = Collections.unmodifiableList(steps);
        }

        public int totalSeconds() {
            int n = 0;
            for (Step s : steps) n += s.seconds;
            return n;
        }

        public int minutes() {
            return Math.round(totalSeconds() / 60f);
        }

        public double peakSpeed() {
            double m = 0;
            for (Step s : steps) m = Math.max(m, s.speed);
            return m;
        }

        /** 转成下发用的分段（秒粒度，速度已按设备上限夹到 12） */
        public List<PlanRunner.Seg> segments() {
            List<PlanRunner.Seg> list = new ArrayList<PlanRunner.Seg>();
            for (Step s : steps) list.add(PlanRunner.Seg.ofSeconds(s.speed, s.seconds));
            return list;
        }

        /** 按阶段汇总，用于详情页分组显示，如 [热身, 5:00, 2] */
        public List<String[]> phases() {
            List<String[]> out = new ArrayList<String[]>();
            for (Step s : steps) {
                if (!out.isEmpty() && out.get(out.size() - 1)[0].equals(s.phase)) {
                    String[] last = out.get(out.size() - 1);
                    last[1] = String.valueOf(Integer.parseInt(last[1]) + s.seconds);
                    last[2] = String.valueOf(Integer.parseInt(last[2]) + 1);
                } else {
                    out.add(new String[]{s.phase, String.valueOf(s.seconds), "1"});
                }
            }
            return out;
        }
    }

    public static final List<Course> ALL;

    static {
        List<Course> list = new ArrayList<Course>();
        {
            List<Step> steps = new ArrayList<Step>();
            steps.add(new Step("热身", "快走", 5.0, 180));
            steps.add(new Step("热身", "快走", 6.0, 120));
            steps.add(new Step("有氧", "慢跑", 8.0, 120));
            steps.add(new Step("有氧", "慢跑", 9.0, 90));
            steps.add(new Step("有氧", "慢跑", 7.5, 180));
            steps.add(new Step("有氧", "慢跑", 8.0, 180));
            steps.add(new Step("有氧", "慢跑", 8.5, 180));
            steps.add(new Step("有氧", "慢跑", 9.0, 120));
            steps.add(new Step("有氧", "慢跑", 8.0, 180));
            steps.add(new Step("有氧", "慢跑", 7.5, 240));
            steps.add(new Step("放松", "快走", 7.0, 180));
            steps.add(new Step("放松", "快走", 6.5, 120));
            steps.add(new Step("放松", "快走", 6.0, 90));
            list.add(new Course("preset_aerobic_jog", "有氧慢跑", "K2 初学", "77-115", steps));
        }
        {
            List<Step> steps = new ArrayList<Step>();
            steps.add(new Step("热身激活", "慢走", 4.0, 60));
            steps.add(new Step("热身激活", "快走", 6.0, 60));
            steps.add(new Step("热身激活", "慢跑", 9.0, 60));
            steps.add(new Step("第一段燃脂跑", "快跑", 11.0, 120));
            steps.add(new Step("第一段燃脂跑", "慢跑", 9.0, 120));
            steps.add(new Step("第二段燃脂跑", "快跑", 12.0, 120));
            steps.add(new Step("第二段燃脂跑", "慢跑", 9.0, 120));
            steps.add(new Step("第三段燃脂跑", "快跑", 11.0, 120));
            steps.add(new Step("第三段燃脂跑", "慢跑", 10.0, 120));
            steps.add(new Step("第四段燃脂跑", "快跑", 11.0, 120));
            steps.add(new Step("第四段燃脂跑", "慢跑", 9.0, 120));
            steps.add(new Step("第五段燃脂跑", "快跑", 12.0, 120));
            steps.add(new Step("第五段燃脂跑", "慢跑", 8.0, 120));
            steps.add(new Step("放松恢复", "快走", 5.5, 120));
            list.add(new Course("preset_hiit_fatburn", "HIIT强化燃脂跑", "K3 进阶", "189-283", steps));
        }
        {
            List<Step> steps = new ArrayList<Step>();
            steps.add(new Step("热身激活", "慢走", 4.0, 60));
            steps.add(new Step("热身激活", "快走", 5.5, 120));
            steps.add(new Step("训练", "疾速走", 7.0, 120));
            steps.add(new Step("训练", "快走", 6.0, 120));
            steps.add(new Step("训练", "疾速走", 7.0, 180));
            steps.add(new Step("训练", "快走", 6.0, 120));
            steps.add(new Step("训练", "疾速走", 7.5, 120));
            steps.add(new Step("训练", "快走", 6.0, 60));
            steps.add(new Step("训练", "疾速走", 7.5, 60));
            steps.add(new Step("训练", "快走", 6.0, 60));
            steps.add(new Step("训练", "疾速走", 7.5, 60));
            steps.add(new Step("放松恢复", "快走", 6.0, 60));
            steps.add(new Step("放松恢复", "慢走", 4.0, 60));
            list.add(new Course("preset_fatburn_walk20", "20分钟燃脂走", "K1 零基础", "120-180", steps));
        }
        {
            List<Step> steps = new ArrayList<Step>();
            steps.add(new Step("热身", "慢走", 4.0, 60));
            steps.add(new Step("热身", "快走", 5.5, 120));
            steps.add(new Step("训练", "慢跑", 7.0, 120));
            steps.add(new Step("训练", "快走", 5.5, 60));
            steps.add(new Step("训练", "慢跑", 7.0, 180));
            steps.add(new Step("训练", "快走", 5.5, 60));
            steps.add(new Step("训练", "慢跑", 7.0, 120));
            steps.add(new Step("训练", "快走", 5.5, 60));
            steps.add(new Step("训练", "慢跑", 7.0, 120));
            steps.add(new Step("训练", "快走", 5.5, 60));
            steps.add(new Step("训练", "慢跑", 7.0, 60));
            steps.add(new Step("放松", "快走", 5.5, 180));
            list.add(new Course("preset_miit_beginner", "MIIT初级燃脂跑", "K1 零基础", "117-175", steps));
        }
        {
            List<Step> steps = new ArrayList<Step>();
            steps.add(new Step("热身激活", "慢走", 4.0, 60));
            steps.add(new Step("热身激活", "快走", 5.5, 180));
            steps.add(new Step("训练", "疾速走", 7.0, 240));
            steps.add(new Step("训练", "快走", 6.0, 120));
            steps.add(new Step("训练", "疾速走", 7.0, 180));
            steps.add(new Step("训练", "快走", 5.5, 60));
            steps.add(new Step("训练", "疾速走", 7.5, 120));
            steps.add(new Step("训练", "快走", 6.0, 60));
            steps.add(new Step("训练", "疾速走", 7.5, 180));
            steps.add(new Step("训练", "快走", 6.0, 120));
            steps.add(new Step("训练", "疾速走", 7.5, 120));
            steps.add(new Step("训练", "快走", 6.0, 60));
            steps.add(new Step("训练", "疾速走", 7.5, 120));
            steps.add(new Step("放松恢复", "快走", 5.5, 120));
            steps.add(new Step("放松恢复", "慢走", 4.0, 60));
            list.add(new Course("preset_fatburn_walk30", "30分钟燃脂走", "K2 初学", "159-239", steps));
        }
        {
            List<Step> steps = new ArrayList<Step>();
            steps.add(new Step("热身", "快走", 6.0, 60));
            steps.add(new Step("热身", "慢跑", 9.0, 240));
            steps.add(new Step("上身动作练习", "快跑", 11.0, 180));
            steps.add(new Step("上身动作练习", "慢跑", 10.0, 120));
            steps.add(new Step("上身动作练习", "快走", 6.0, 60));
            steps.add(new Step("摆臂练习", "慢跑", 9.0, 120));
            steps.add(new Step("摆臂练习", "快跑", 12.0, 180));
            steps.add(new Step("摆臂练习", "慢跑", 9.0, 120));
            steps.add(new Step("摆臂练习", "快走", 6.0, 60));
            steps.add(new Step("呼吸练习", "慢跑", 10.0, 120));
            steps.add(new Step("呼吸练习", "快跑", 12.0, 180));
            steps.add(new Step("呼吸练习", "慢跑", 9.0, 120));
            steps.add(new Step("呼吸练习", "快走", 6.0, 60));
            steps.add(new Step("步频练习", "慢跑", 10.0, 120));
            steps.add(new Step("步频练习", "快跑", 12.0, 180));
            steps.add(new Step("步频练习", "慢跑", 10.0, 120));
            steps.add(new Step("步频练习", "快走", 5.5, 60));
            steps.add(new Step("综合复习", "慢跑", 10.0, 120));
            steps.add(new Step("综合复习", "快跑", 11.0, 180));
            steps.add(new Step("综合复习", "慢跑", 10.0, 120));
            steps.add(new Step("放松恢复", "快走", 5.5, 180));
            list.add(new Course("preset_fatburn_challenge45", "综合燃脂挑战跑", "K4 强化", "353-529", steps));
        }
        {
            List<Step> steps = new ArrayList<Step>();
            steps.add(new Step("热身", "快走", 5.5, 180));
            steps.add(new Step("热身", "慢跑", 7.0, 120));
            steps.add(new Step("高强度间歇训练", "冲刺跑", 11.0, 60));
            steps.add(new Step("高强度间歇训练", "慢跑", 7.0, 120));
            steps.add(new Step("高强度间歇训练", "冲刺跑", 11.0, 60));
            steps.add(new Step("高强度间歇训练", "慢跑", 7.0, 120));
            steps.add(new Step("高强度间歇训练", "冲刺跑", 11.0, 60));
            steps.add(new Step("高强度间歇训练", "慢跑", 7.0, 120));
            steps.add(new Step("高强度间歇训练", "冲刺跑", 11.0, 60));
            steps.add(new Step("高强度间歇训练", "慢跑", 7.0, 120));
            steps.add(new Step("放松", "快走", 6.0, 180));
            steps.add(new Step("放松", "快走", 5.5, 180));
            steps.add(new Step("放松", "快走", 5.0, 180));
            list.add(new Course("preset_hiit_varied", "HIIT变速跑", "K3 进阶", "159-239", steps));
        }
        ALL = Collections.unmodifiableList(list);
    }
}
