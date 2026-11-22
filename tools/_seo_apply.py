# -*- coding: utf-8 -*-
"""SEO 配置一键应用：git push + topics + GitHub Pages。
前置：token 写入 C:/Users/bejay/gh-token.txt
"""
import io, json, os, subprocess, sys, urllib.request, urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = r"C:\Users\bejay\gh-token.txt"
OWNER, REPO = "bejaylee", "xiaoqiao-treadmill"

token = io.open(TOKEN_FILE, encoding="utf-8").read().strip()
if not (token.startswith("github_pat_") or token.startswith("ghp_")):
    sys.exit("token 文件内容不像 GitHub token")

H = {
    "Authorization": "Bearer " + token,
    "Accept": "application/vnd.github+json",
    "Content-Type": "application/json",
    "User-Agent": "xq-seo",
    "X-GitHub-Api-Version": "2022-11-28",
}

def call(method, url, data=None):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method, headers=H)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400]

# 1) push
remote = "https://x-access-token:%s@github.com/%s/%s.git" % (token, OWNER, REPO)
r = subprocess.run(["git", "-C", ROOT, "push", "-q", remote, "main"],
                   capture_output=True, text=True)
print("PUSH", "OK" if r.returncode == 0 else "FAIL " + r.stderr[:200])
if r.returncode != 0:
    sys.exit(1)

# 2) topics
TOPICS = ["xiaoqiao", "treadmill", "smartrun", "qiaoports", "android",
          "reverse-engineering", "offline", "iot", "esp-touch", "fitness"]
st, body = call("PUT", "https://api.github.com/repos/%s/%s/topics" % (OWNER, REPO),
                {"names": TOPICS})
print("TOPICS", st, body.get("names") if isinstance(body, dict) else body)

# 3) Pages: main /docs
st, body = call("POST", "https://api.github.com/repos/%s/%s/pages" % (OWNER, REPO),
                {"source": {"branch": "main", "path": "/docs"}})
if st in (201, 409):   # 409 = 已存在，改为更新
    if st == 409:
        st, body = call("PUT", "https://api.github.com/repos/%s/%s/pages" % (OWNER, REPO),
                        {"source": {"branch": "main", "path": "/docs"}})
    print("PAGES", st, body.get("html_url") if isinstance(body, dict) else body)
else:
    print("PAGES", st, body)

# 4) 回读确认
st, body = call("GET", "https://api.github.com/repos/%s/%s/pages" % (OWNER, REPO))
if isinstance(body, dict):
    print("PAGES_STATE", st, body.get("status"), body.get("html_url"), body.get("source"))
st, body = call("GET", "https://api.github.com/repos/%s/%s/topics" % (OWNER, REPO))
if isinstance(body, dict):
    print("TOPICS_STATE", st, len(body.get("names", [])), "tags")
