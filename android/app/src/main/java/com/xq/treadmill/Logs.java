package com.xq.treadmill;

import java.util.ArrayList;
import java.util.List;

/** 全局运行日志（主界面不再展示，收进「更多」里查看） */
public class Logs {

    private static final int MAX = 200;
    private static final List<String> LINES = new ArrayList<String>();

    public static void add(String s) {
        synchronized (LINES) {
            LINES.add(s);
            while (LINES.size() > MAX) LINES.remove(0);
        }
    }

    public static String dump() {
        synchronized (LINES) {
            StringBuilder sb = new StringBuilder();
            for (String s : LINES) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(s);
            }
            return sb.toString();
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}
