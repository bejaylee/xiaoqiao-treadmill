package com.xq.treadmill;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 课程会话（进程级单例）：持有正在运行的 PlanRunner 与课程状态。
 *
 * 目的：Activity 切换 / 重建 / 返回主页时课程不中断——
 * Activity 只注册监听刷新 UI，真正跑计划的是这里，进程活着课程就活着。
 */
public class CourseSession implements PlanRunner.Listener {

    private static CourseSession instance;

    public static synchronized CourseSession get() {
        if (instance == null) instance = new CourseSession();
        return instance;
    }

    public interface Listener {
        void onProgress(int segIndex, int segTotal, double targetKmh,
                        int countedSec, int totalSec, int percent, boolean frozen);
        void onFinish(String reason);
    }

    private final List<Listener> listeners = new ArrayList<Listener>();
    private PlanRunner runner;
    private ConnectionManager conn;
    private Metronome metro;

    public String courseId;
    public String courseName;
    public List<PlanRunner.Seg> segs;
    public List<CourseLibrary.Step> steps;
    public int lastCounted;
    public int activeSeg = -1;
    public int lastAnnounced = -1;
    public boolean previewMode;

    private CourseSession() {
    }    public void attach(Context c) {
        conn = ConnectionManager.get();
        metro = Metronome.get(c);
    }

    public void register(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void unregister(Listener l) {
        listeners.remove(l);
    }

    public boolean isRunning() {
        return previewMode || (runner != null && runner.isRunning());
    }

    public void start(String id, String name, List<PlanRunner.Seg> plan,
                      List<CourseLibrary.Step> stepsList) {
        courseId = id;
        courseName = name;
        segs = plan;
        steps = stepsList;
        lastCounted = 0;
        activeSeg = -1;
        lastAnnounced = -1;
        runner = new PlanRunner(conn.client(), this);
        runner.start(plan, 1, 0);
    }

    public void stop() {
        if (runner != null) {
            runner.stop();
            runner = null;
        }
        if (metro != null) metro.stop();
    }

    // -------------------------------------------------- PlanRunner.Listener
    @Override
    public void onProgress(int segIndex, int segTotal, double targetKmh,
                           int countedSec, int totalSec, int percent, boolean frozen) {
        lastCounted = countedSec;
        activeSeg = segIndex;
        if (metro != null) metro.setSpeed(targetKmh);
        List<Listener> copy = new ArrayList<Listener>(listeners);
        for (Listener l : copy) {
            try {
                l.onProgress(segIndex, segTotal, targetKmh, countedSec, totalSec, percent, frozen);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onFinish(String reason) {
        List<Listener> copy = new ArrayList<Listener>(listeners);
        for (Listener l : copy) {
            try {
                l.onFinish(reason);
            } catch (Exception ignored) {
            }
        }
        runner = null;
    }
}
