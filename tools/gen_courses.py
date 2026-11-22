#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 courses/recommended.json 生成为 Android 工程里的 CourseLibrary.java。

构建脚本会在 javac 之前调用它，避免运行时解析 JSON、也不引第三方库。
"""

import json
import os
import sys


def java_str(s):
    return '"' + str(s).replace('\\', '\\\\').replace('"', '\\"') + '"'


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "courses/recommended.json"
    dst = sys.argv[2] if len(sys.argv) > 2 else \
        "android/app/src/main/java/com/xq/treadmill/CourseLibrary.java"

    data = json.load(open(src, encoding="utf-8"))
    courses = data.get("courses", [])

    out = []
    out.append("package com.xq.treadmill;")
    out.append("")
    out.append("import java.util.ArrayList;")
    out.append("import java.util.Collections;")
    out.append("import java.util.List;")
    out.append("")
    out.append("/**")
    out.append(" * 预设课程库（由 tools/gen_courses.py 从 courses/recommended.json 生成，勿手改）。")
    out.append(" * 数据来源：推荐课程的分段表，逐段复刻。")
    out.append(" */")
    out.append("public class CourseLibrary {")
    out.append("")
    out.append("    public static class Step {")
    out.append("        public final String phase;")
    out.append("        public final String action;")
    out.append("        public final double speed;")
    out.append("        public final int seconds;")
    out.append("")
    out.append("        Step(String phase, String action, double speed, int seconds) {")
    out.append("            this.phase = phase;")
    out.append("            this.action = action;")
    out.append("            this.speed = speed;")
    out.append("            this.seconds = seconds;")
    out.append("        }")
    out.append("    }")
    out.append("")
    out.append("    public static class Course {")
    out.append("        public final String id;")
    out.append("        public final String name;")
    out.append("        public final String level;")
    out.append("        public final String kcal;")
    out.append("        public final List<Step> steps;")
    out.append("")
    out.append("        Course(String id, String name, String level, String kcal, List<Step> steps) {")
    out.append("            this.id = id;")
    out.append("            this.name = name;")
    out.append("            this.level = level;")
    out.append("            this.kcal = kcal;")
    out.append("            this.steps = Collections.unmodifiableList(steps);")
    out.append("        }")
    out.append("")
    out.append("        public int totalSeconds() {")
    out.append("            int n = 0;")
    out.append("            for (Step s : steps) n += s.seconds;")
    out.append("            return n;")
    out.append("        }")
    out.append("")
    out.append("        public int minutes() {")
    out.append("            return Math.round(totalSeconds() / 60f);")
    out.append("        }")
    out.append("")
    out.append("        public double peakSpeed() {")
    out.append("            double m = 0;")
    out.append("            for (Step s : steps) m = Math.max(m, s.speed);")
    out.append("            return m;")
    out.append("        }")
    out.append("")
    out.append("        /** 转成下发用的分段（秒粒度，速度已按设备上限夹到 12） */")
    out.append("        public List<PlanRunner.Seg> segments() {")
    out.append("            List<PlanRunner.Seg> list = new ArrayList<PlanRunner.Seg>();")
    out.append("            for (Step s : steps) list.add(PlanRunner.Seg.ofSeconds(s.speed, s.seconds));")
    out.append("            return list;")
    out.append("        }")
    out.append("")
    out.append("        /** 按阶段汇总，用于详情页分组显示，如 [热身, 5:00, 2] */")
    out.append("        public List<String[]> phases() {")
    out.append("            List<String[]> out = new ArrayList<String[]>();")
    out.append("            for (Step s : steps) {")
    out.append("                if (!out.isEmpty() && out.get(out.size() - 1)[0].equals(s.phase)) {")
    out.append("                    String[] last = out.get(out.size() - 1);")
    out.append("                    last[1] = String.valueOf(Integer.parseInt(last[1]) + s.seconds);")
    out.append("                    last[2] = String.valueOf(Integer.parseInt(last[2]) + 1);")
    out.append("                } else {")
    out.append("                    out.add(new String[]{s.phase, String.valueOf(s.seconds), \"1\"});")
    out.append("                }")
    out.append("            }")
    out.append("            return out;")
    out.append("        }")
    out.append("    }")
    out.append("")
    out.append("    public static final List<Course> ALL;")
    out.append("")
    out.append("    static {")
    out.append("        List<Course> list = new ArrayList<Course>();")

    for c in courses:
        steps = c["segments"]
        out.append("        {")
        out.append("            List<Step> steps = new ArrayList<Step>();")
        for s in steps:
            out.append("            steps.add(new Step(%s, %s, %.1f, %d));" % (
                java_str(s["phase"]), java_str(s["action"]), float(s["speed"]), int(s["seconds"])))
        out.append("            list.add(new Course(%s, %s, %s, %s, steps));" % (
            java_str(c["id"]), java_str(c["name"]), java_str(c["level"]), java_str(c.get("kcal", ""))))
        out.append("        }")

    out.append("        ALL = Collections.unmodifiableList(list);")
    out.append("    }")
    out.append("}")
    out.append("")

    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        f.write("\n".join(out))

    total_seg = sum(len(c["segments"]) for c in courses)
    print("生成 %s：%d 个课程 / %d 段" % (dst, len(courses), total_seg))


if __name__ == "__main__":
    main()
