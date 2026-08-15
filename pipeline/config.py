"""讀取 .env 的本機設定。

刻意不使用 python-dotenv —— 整條管線目前零外部相依，維持這點讓任何人
clone 下來就能跑，不必先處理套件安裝。

金鑰只從環境或 .env 讀，絕不寫進程式碼或 log。
"""

from __future__ import annotations

import os
import pathlib

ENV_PATH = pathlib.Path(__file__).parent / ".env"


def _load() -> dict[str, str]:
    values: dict[str, str] = {}
    if not ENV_PATH.exists():
        return values
    for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


_ENV = _load()


def get(name: str, required: bool = True) -> str:
    """取得設定值。環境變數優先於 .env。"""
    value = os.environ.get(name) or _ENV.get(name, "")
    if required and not value:
        raise SystemExit(
            f"缺少 {name}。請在 {ENV_PATH} 填入該值，"
            f"或設為環境變數後重試。"
        )
    return value


def masked(value: str) -> str:
    """供 log 使用的遮蔽形式。完整金鑰永遠不該出現在輸出裡。"""
    if len(value) <= 8:
        return "*" * len(value)
    return f"{value[:4]}{'*' * 8}{value[-4:]}"
