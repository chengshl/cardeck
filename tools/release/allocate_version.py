#!/usr/bin/env python3
"""为 CarDeck 签名发布分配跨分支统一版本号。仅供发布工作流调用。"""
import json
import os
import re
import subprocess
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

PREFIX = "refs/tags/cardeck-build/v1/"
EPOCH = 1704067200
ALIASES = {"Buick-E5": "E5", "Buick-Test": "E5-TEST", "Buick-main": "BUICK"}
REF_RE = re.compile(r"^refs/tags/cardeck-build/v1/(\d{6})(\d{3})-c(\d+)$")


def choose_version(refs, now, branch):
    day = now.astimezone(ZoneInfo("Asia/Shanghai")).strftime("%y%m%d")
    numbers, codes = [], []
    for ref in refs:
        match = REF_RE.fullmatch(ref)
        if match:
            if match.group(1) == day:
                numbers.append(int(match.group(2)))
            codes.append(int(match.group(3)))
    sequence = max(numbers, default=0) + 1
    if sequence > 999:
        raise ValueError("北京时间当日构建流水号超过 999")
    code = max(int(now.timestamp()) - EPOCH, max(codes, default=0) + 1)
    if not 300000 <= code <= 2100000000:
        raise ValueError("Android versionCode 超出允许范围")
    alias = ALIASES.get(branch)
    if alias is None:
        slug = re.sub(r"[^A-Z0-9]+", "-", branch.upper()).strip("-")
        alias = "EXP-" + slug[-24:]
    name = f"{alias}-{day}{sequence:03d}"
    ref = f"{PREFIX}{day}{sequence:03d}-c{code}"
    return name, code, ref


def gh(*args, input_data=None):
    proc = subprocess.run(["gh", "api", *args], input=input_data,
                          text=True, capture_output=True, check=True)
    return json.loads(proc.stdout)


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    sha = os.environ["GITHUB_SHA"]
    branch = os.environ["GITHUB_REF_NAME"]
    for _ in range(12):
        now = datetime.now(timezone.utc)
        refs = gh(f"repos/{repo}/git/matching-refs/tags/cardeck-build/v1")
        name, code, ref = choose_version([item["ref"] for item in refs], now, branch)
        try:
            gh("-X", "POST", f"repos/{repo}/git/refs", "-f", f"ref={ref}", "-f", f"sha={sha}")
            break
        except subprocess.CalledProcessError as exc:
            if "422" not in (exc.stderr or ""):
                raise
    else:
        raise RuntimeError("连续分配冲突，未取得版本号")
    output = os.environ.get("GITHUB_ENV")
    lines = [f"CARDECK_BUILD_VERSION_CODE={code}",
             f"CARDECK_RELEASE_VERSION_NAME={name}",
             f"CARDECK_RELEASE_BRANCH={branch}"]
    if output:
        with open(output, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    print(f"已分配版本 {name}（内部版本号 {code}），登记 {ref}")


if __name__ == "__main__":
    main()
