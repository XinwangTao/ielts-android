# dict-assets

`ecdict.db`（约 92MB）不入版本库——它是可重新下载的第三方词库，不是本工程源码。

## 用途

阅读页划词查询的离线词典数据源。App 启动时从外部目录读取：

```
/sdcard/Android/data/com.txw.ielts/files/dict/ecdict.db
```

（沿用「外部目录投递」模式，不打进 APK。）

## 重新获取

```bash
adb push dict-assets/ecdict.db /sdcard/Android/data/com.txw.ielts/files/dict/ecdict.db
```

词库本体来自 ECDICT（https://github.com/skywind3000/ECDICT ，MIT）。
