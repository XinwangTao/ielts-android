#!/bin/bash
# 把 MNN 模型推送到雅思 App 的外部投递目录。
# 用法：bash push_mnn_model.sh [device] [model_dir] [model_name]
#   device     默认平板 192.168.236.61:5555（手机填序列号/无线地址）
#   model_dir  默认 F:/cache/models/qwen3-4b-instruct-mnn
#   model_name 默认取 model_dir 的目录名
# 说明：App 首次本地评分时会把外部目录拷贝到内部 filesDir（/sdcard 是 FUSE，mmap 不可靠）。
#       .git / .gitattributes 等开发文件不推送，只推模型必需文件。
set -e
export MSYS_NO_PATHCONV=1

DEV=${1:-192.168.236.61:5555}
SRC=${2:-F:/cache/models/qwen3-4b-instruct-mnn}
NAME=${3:-$(basename "$SRC")}
DST=/sdcard/Android/data/com.txw.ielts/files/models/mnn/$NAME

[ -f "$SRC/config.json" ] || { echo "模型目录不存在或缺少 config.json: $SRC" >&2; exit 1; }

echo "==> 推送到 $DEV:$DST"
adb -s "$DEV" shell "mkdir -p $DST"
adb -s "$DEV" push "$SRC"/config.json "$SRC"/llm.mnn "$SRC"/llm_config.json "$SRC"/tokenizer.txt "$DST"/
# 可选文件（存在才推）
for f in llm.mnn.weight llm.mnn.json configuration.json visual.mnn visual.mnn.weight; do
  [ -f "$SRC/$f" ] && adb -s "$DEV" push "$SRC/$f" "$DST"/
done
adb -s "$DEV" shell "ls -la $DST"
echo "==> 完成。App → 设置 → 本地评分模型 里选择 $NAME"
