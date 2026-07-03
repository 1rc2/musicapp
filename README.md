# MusicFlow

轻量级 Android 音乐播放器，干净简洁，打开即用。

## 功能

- 导入本地音乐 / 自动扫描手机音乐
- 最近播放、本地音乐、歌单管理、收藏
- 创建/删除自定义歌单
- 播放模式：列表播放 / 单曲循环 / 随机播放
- 内置浏览器（快速跳转音乐网站）
- 远程更新（通过 GitHub Releases）

## 技术栈

- 前端：HTML + CSS + JavaScript（零依赖）
- 原生：Android WebView + JS Bridge
- 构建：Android SDK (aapt2 + d8 + apksigner)

## 远程更新

APP 支持通过 GitHub Releases 远程更新：

1. 设置 -> 更新服务器 -> 切换为「GitHub Releases」
2. 点击「检查更新」，APP 自动检测新版本
3. 如有新版本，自动下载 APK 并安装

发布新版本时，在 GitHub Releases 上传新的 APK 文件即可。

## 构建

```bash
# 需要 Android SDK (platforms;android-34, build-tools;34.0.0) 和 JDK 17+

# 编译资源
aapt2 compile --dir res -o bin/resources.zip
aapt2 link -o bin/app-resources.apk -I $PLATFORM --manifest AndroidManifest.xml --java gen -A assets bin/resources.zip

# 编译 Java
javac -source 11 -target 11 -classpath $PLATFORM -d bin/classes src/com/musicflow/app/MainActivity.java

# 打包 DEX
cd bin/classes && jar cf ../classes.jar com/
d8 --output bin/ bin/classes.jar

# 组装 + 签名
zip -j app-unsigned.apk classes.dex
zipalign -f 4 app-unsigned.apk app-aligned.apk
apksigner sign --ks debug.keystore --ks-key-alias musicflow --ks-pass pass:musicflow123 --key-pass pass:musicflow123 --out MusicFlow.apk app-aligned.apk
```
