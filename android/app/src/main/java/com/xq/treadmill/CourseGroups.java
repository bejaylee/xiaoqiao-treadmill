package com.xq.treadmill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 课程分类：按 K1→K4 分组，组内按时长从短到长 */
public class CourseGroups {

    public static class Group {
        public final String code;
        public final String label;
        public final int color;
        public final List<CourseLibrary.Course> courses;

        Group(String code, String label, int color, List<CourseLibrary.Course> courses) {
            this.code = code;
            this.label = label;
            this.color = color;
            this.courses = courses;
        }
    }

    private static final String[] CODES = {"K1", "K2", "K3", "K4"};
    private static final String[] LABELS = {"零基础", "初学", "进阶", "强化"};
    private static final int[] COLORS = {
            0xFF3E8E6E,   // K1 绿：轻松入门
            0xFF3C6FB0,   // K2 蓝：稳定提升
            0xFFC07A22,   // K3 橙：强度上来
            0xFFB23B3B    // K4 红：挑战
    };

    private static int indexOf(String level) {
        if (level == null) return 0;
        for (int i = 0; i < CODES.length; i++) {
            if (level.startsWith(CODES[i])) return i;
        }
        return 0;
    }

    public static List<Group> groups() {
        List<List<CourseLibrary.Course>> buckets = new ArrayList<List<CourseLibrary.Course>>();
        for (int i = 0; i < CODES.length; i++) buckets.add(new ArrayList<CourseLibrary.Course>());
        for (CourseLibrary.Course c : CourseLibrary.ALL) {
            buckets.get(indexOf(c.level)).add(c);
        }
        Comparator<CourseLibrary.Course> byTime = new Comparator<CourseLibrary.Course>() {
            @Override
            public int compare(CourseLibrary.Course a, CourseLibrary.Course b) {
                int d = a.totalSeconds() - b.totalSeconds();
                return d != 0 ? d : a.name.compareTo(b.name);
            }
        };
        List<Group> out = new ArrayList<Group>();
        for (int i = 0; i < CODES.length; i++) {
            List<CourseLibrary.Course> list = buckets.get(i);
            if (list.isEmpty()) continue;
            Collections.sort(list, byTime);
            out.add(new Group(CODES[i], LABELS[i], COLORS[i], list));
        }
        return out;
    }

    /** 详情页等只需要一个有序一维列表的场景 */
    public static List<CourseLibrary.Course> flat() {
        List<CourseLibrary.Course> out = new ArrayList<CourseLibrary.Course>();
        for (Group g : groups()) out.addAll(g.courses);
        return out;
    }
}
