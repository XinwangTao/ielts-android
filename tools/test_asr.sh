#!/usr/bin/env bash
# 用途：验证用户的 OpenAI 兼容中转是否支持 /v1/audio/transcriptions
# 用法：先把 key 写进 .secret/key.env（格式见下），再执行本脚本，并把待测音频路径作为第 1 个参数
#   ASR_BASE_URL=https://your-relay.example.com/v1
#   ASR_API_KEY=sk-xxxx
#   ASR_MODEL=whisper-1
# 注意：脚本只读取变量，绝不打印 key 本身；输出中的 key 一律脱敏。

set -euo pipefail

ENV_FILE="$(cd "$(dirname "$0")/.." && pwd)/.secret/key.env"
AUDIO="${1:-}"

if [ ! -f "$ENV_FILE" ]; then
  echo "缺少 $ENV_FILE，请按脚本头部注释创建（不要提交到 git）"
  exit 2
fi
# shellcheck disable=SC1090
. "$ENV_FILE"

: "${ASR_BASE_URL:?ASR_BASE_URL 未设置}"
: "${ASR_API_KEY:?ASR_API_KEY 未设置}"
ASR_MODEL="${ASR_MODEL:-whisper-1}"

if [ -z "$AUDIO" ] || [ ! -f "$AUDIO" ]; then
  echo "用法：$0 <音频文件路径>（m4a/mp3/wav 均可）"
  exit 2
fi

echo "== 目标: $ASR_BASE_URL/audio/transcriptions  模型: $ASR_MODEL  文件: $AUDIO ($(wc -c <"$AUDIO") bytes)"

HTTP=$(curl -sS -o /tmp/asr_test_out.json -w "%{http_code}" --max-time 120 \
  -H "Authorization: Bearer $ASR_API_KEY" \
  -F "file=@$AUDIO" \
  -F "model=$ASR_MODEL" \
  -F "language=en" \
  -F "response_format=verbose_json" \
  "$ASR_BASE_URL/audio/transcriptions" || echo "000")

echo "== HTTP $HTTP"
head -c 800 /tmp/asr_test_out.json; echo
if [ "$HTTP" != "200" ]; then
  echo "== 结论：该通道不支持或不接受本次请求（见上方响应体，已脱敏）"
  exit 1
fi
echo "== 结论：通道可用，主链路成立"
