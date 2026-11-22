# -*- coding: utf-8 -*-
"""
一次性生成 App 内置语音（运行时完全离线，不调用设备 TTS）。

用法：
    python tools/gen_voice.py

产出：
    android/app/src/main/assets/voice/*.mp3        内置音频（去重）
    android/app/src/main/assets/voice/index.tsv    文本 -> 文件名
    android/app/src/main/java/com/xq/treadmill/VoiceLines.java   播报文案常量
    tools/voice_lines.json                        可复现清单

音色：zh-CN-YunxiNeural（中文男声 / 阳光自然），语速 -5%。
"""
import asyncio
import hashlib
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COURSES = os.path.join(ROOT, "courses", "recommended.json")
ASSETS = os.path.join(ROOT, "android", "app", "src", "main", "assets", "voice")
JAVA = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "xq", "treadmill", "VoiceLines.java")
MANIFEST = os.path.join(ROOT, "tools", "voice_lines.json")

VOICE = "zh-CN-YunxiNeural"
RATE = "-5%"
FORCE = False

def parse_args(argv):
    """--voice <name> 换音色；--force 强制重新合成（换音色时必须带）"""
    global VOICE, RATE, FORCE
    i = 0
    while i < len(argv):
        if argv[i] == "--voice" and i + 1 < len(argv):
            VOICE = argv[i + 1]; i += 2
        elif argv[i] == "--rate" and i + 1 < len(argv):
            RATE = argv[i + 1]; i += 2
        elif argv[i] == "--force":
            FORCE = True; i += 1
        else:
            i += 1
    return VOICE, RATE

START_CUSTOM = "开始自定义课程，按计划节奏进行"
FINISH = "训练完成，今天也坚持下来了"
STOP = "训练已停止"
SAMPLE = "语音播报已就绪，开始训练，加油"


def fmt_speed(v):
    return "%.1f" % v


def build_lines():
    with io.open(COURSES, encoding="utf-8") as f:
        data = json.load(f)
    lines = {}          # key -> text
    starts = {}         # courseId -> text
    segs = {}           # "courseId:idx" -> text

    for c in data["courses"]:
        cid, name = c["id"], c["name"]
        parts = c["segments"]
        total = sum(int(s["seconds"]) for s in parts)
        minutes = total // 60
        text = "开始%s，共 %d 分钟，%d 个分段" % (name, minutes, len(parts))
        starts[cid] = text
        lines["start:" + cid] = text
        for i, s in enumerate(parts):
            speed = max(1.0, min(12.0, float(s["speed"])))
            speed = round(speed * 10) / 10.0
            action = s.get("action", "")
            t = "第 %d 段%s，%s 公里每小时" % (
                i + 1, ("，" + action) if action else "", fmt_speed(speed))
            key = "seg:%s:%d" % (cid, i)
            segs["%s:%d" % (cid, i)] = t
            lines[key] = t

    lines["start:custom"] = START_CUSTOM
    # 自定义课程 / 自由跑按 0.5 km/h 量化后播报，覆盖 1.0–12.0
    v = 1.0
    while v <= 12.0001:
        t = "进入新的分段，%s 公里每小时" % fmt_speed(v)
        lines["seg:custom:%.1f" % v] = t
        v += 0.5
    lines["finish"] = FINISH
    lines["stop"] = STOP
    lines["sample"] = SAMPLE
    return lines, starts, segs


def java_escape(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')


def write_java(starts, segs):
    out = []
    out.append("package com.xq.treadmill;\n")
    out.append("import java.util.HashMap;")
    out.append("import java.util.Locale;")
    out.append("import java.util.Map;\n")
    out.append("/** 由 tools/gen_voice.py 生成，请勿手改；文本与内置音频一一对应 */")
    out.append("public class VoiceLines {")
    out.append('    public static final String START_CUSTOM = "%s";' % java_escape(START_CUSTOM))
    out.append('    public static final String FINISH = "%s";' % java_escape(FINISH))
    out.append('    public static final String STOP = "%s";' % java_escape(STOP))
    out.append('    public static final String SAMPLE = "%s";' % java_escape(SAMPLE))
    out.append("")
    out.append("    private static final Map<String, String> START = new HashMap<String, String>();")
    out.append("    private static final Map<String, String> SEG = new HashMap<String, String>();")
    out.append("    static {")
    for k in sorted(starts):
        out.append('        START.put("%s", "%s");' % (java_escape(k), java_escape(starts[k])))
    for k in sorted(segs):
        out.append('        SEG.put("%s", "%s");' % (java_escape(k), java_escape(segs[k])))
    out.append("    }\n")
    out.append("    public static String startFor(String courseId) {")
    out.append("        if (courseId == null) return START_CUSTOM;")
    out.append("        String t = START.get(courseId);")
    out.append("        return t != null ? t : START_CUSTOM;")
    out.append("    }\n")
    out.append("    public static String segFor(String courseId, int index) {")
    out.append("        if (courseId == null) return null;")
    out.append("        return SEG.get(courseId + \":\" + index);")
    out.append("    }\n")
    out.append("    /** 自定义课程 / 自由跑：速度量化到 0.5，保证命中内置音频 */")
    out.append("    public static String segCustom(double speed) {")
    out.append("        double v = Math.round(Math.max(1.0, Math.min(12.0, speed)) * 2) / 2.0;")
    out.append('        return "进入新的分段，" + String.format(Locale.US, "%.1f", v) + " 公里每小时";')
    out.append("    }\n")
    out.append("    public static int count() {")
    out.append("        return START.size() + SEG.size();")
    out.append("    }")
    out.append("}")
    io.open(JAVA, "w", encoding="utf-8").write("\n".join(out) + "\n")


async def synthesize(lines):
    import edge_tts

    os.makedirs(ASSETS, exist_ok=True)
    # 文本去重：多门课相同句子只合成一次
    unique = {}
    for key, text in lines.items():
        h = hashlib.md5(text.encode("utf-8")).hexdigest()[:10]
        unique[text] = h + ".mp3"

    sem = asyncio.Semaphore(5)
    done = [0]
    total = len(unique)

    async def one(text, fname):
        path = os.path.join(ASSETS, fname)
        if (not FORCE) and os.path.exists(path) and os.path.getsize(path) > 1024:
            done[0] += 1
            return text, fname, True
        async with sem:
            for attempt in range(3):
                try:
                    comm = edge_tts.Communicate(text, VOICE, rate=RATE)
                    await comm.save(path)
                    if os.path.getsize(path) > 1024:
                        done[0] += 1
                        print("  [%d/%d] %s" % (done[0], total, text[:34]), flush=True)
                        return text, fname, True
                except Exception as e:
                    if attempt == 2:
                        print("  FAIL %s : %s" % (text[:30], e), flush=True)
                    await asyncio.sleep(1 + attempt)
        return text, fname, False

    tasks = [one(t, f) for t, f in unique.items()]
    results = await asyncio.gather(*tasks)
    ok = dict((t, f) for t, f, good in results if good)
    failed = [t for t, f, good in results if not good]
    return ok, failed


def write_index(ok, lines):
    path = os.path.join(ASSETS, "index.tsv")
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        for text in sorted(ok):
            f.write("%s\t%s\n" % (text, ok[text]))
    io.open(MANIFEST, "w", encoding="utf-8").write(
        json.dumps({"voice": VOICE, "rate": RATE, "lines": lines, "files": ok},
                   ensure_ascii=False, indent=2))


def main():
    voice, rate = parse_args(sys.argv[1:])
    print("音色: %s  语速: %s  强制重生成: %s" % (voice, rate, FORCE))
    lines, starts, segs = build_lines()
    print("文案数: %d（课程开始 %d，分段 %d）" % (len(lines), len(starts), len(segs)))
    write_java(starts, segs)
    print("已生成 VoiceLines.java")
    ok, failed = asyncio.run(synthesize(lines))
    write_index(ok, lines)
    print("音频文件: %d 个，失败: %d" % (len(ok), len(failed)))
    size = sum(os.path.getsize(os.path.join(ASSETS, f)) for f in set(ok.values()))
    print("总大小: %.2f MB" % (size / 1024.0 / 1024.0))
    if failed:
        print("失败文案:", failed[:5])
        sys.exit(1)


if __name__ == "__main__":
    main()
