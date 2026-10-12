# Blink

**随身 FTP 工作站** —— 面向网络运维场景的 Android 工具 App：连到内网 / NAT 后的 FTP 服务器，直接编辑、管理、中转远程文件。一个"能随身带走的 FTP 中转站"。

> 用纯 Kotlin + Coroutines + [Apache Commons Net](https://commons.apache.org/proper/commons-net/) 实现，不依赖任何云服务，数据只在你自己的 FTP 服务器与本地之间流动。

---

## 功能特性

| 类别 | 能力 |
|---|---|
| **连接** | 内网 / NAT 服务器（被动模式、UTF-8 控制通道）；**空闲僵尸连接自愈**——闲置超时后 socket 僵死时自动探活重连，不再"闪退" |
| **文本编辑** | 远程文本打开 → 自动**编码检测** → 编辑 → **无损写回**（二进制传输，规避 CRLF 转换） |
| **文件管理** | 浏览、**上传 / 下载（SAF 选任意位置，零存储权限）**、删除、重命名、移动到其他目录；工具栏固定，仅列表滚动 |
| **图片预览 & 分享** | 下载后本地预览，经 `FileProvider` 走系统分享（微信 / 相册…），零权限 |
| **APK 直装** | 浏览到 `.apk` 显示包信息卡片，确认后拉起系统安装器（含"安装本 App 会重启"提示）；未授权时引导 `REQUEST_INSTALL_PACKAGES` 设置页 |
| **桌面小组件** | "快速记录"卡片：显示最近三条 + 装饰图；点卡片弹窗输入（自动带时间戳）→ **直接追加到指定 FTP 文件**（RETR 读旧 + 拼接 + STOR 写回） |

---

## 安全模型（零新增运行时权限）

- **文件上传 / 下载**走 `Storage Access Framework`（`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`），用户自己选位置，**不申请任何存储权限**。
- **分享 / 预览**经 `FileProvider` 把 App 私有缓存文件授给其它 App，无权限。
- **APK 安装**需用户在系统里手动授予"允许安装未知应用"（`REQUEST_INSTALL_PACKAGES`），App 只在未授权时引导跳转，不自带。
- 凭据（主机 / 账号 / 密码）**仅存本设备** `SharedPreferences`，不上云。

> 待扩展：FTPS / SFTP 加密传输。

---

## 构建

**前置**：JDK 17、Android SDK（compileSdk 34）、**本地 Gradle 8.7**。仓库已配好**阿里云镜像**（网络受限环境可直接拉依赖），另保留官方源兜底。

> 本仓库未附 Gradle Wrapper（受限网络下生成 wrapper 不便）；请本地装 Gradle 8.7，或在可连 `services.gradle.org` 的环境执行 `gradle wrapper` 生成后再用 `./gradlew`。

```bash
# 1) 首次：把 Android SDK 路径写进 local.properties（已 git-ignore）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 出包（用本地 gradle；若已生成 wrapper 则 ./gradlew）
gradle assembleDebug
# → app/build/outputs/apk/debug/Blink-v<版本>.apk（自动带版本号命名）
```

连真机安装：

```bash
adb install -r app/build/outputs/apk/debug/Blink-v1.21.apk
```

> 版本号规则：每次功能改动递增 `versionName` + `versionCode`，产物文件名自动跟随（`Blink-v<versionName>.apk`），便于追溯与分发。

---

## 架构

纯 Kotlin + Coroutines，所有网络 / FTP 调用落在 `Dispatchers.IO`，绝不落主线程（规避 ANR）。

```
cn.blink/
├── FtpManager.kt        # 单例 FTP 核心：连接/探活自愈/listFiles/download/upload/delete/rename/appendLine
├── MainActivity.kt      # 连接表单 + 文件浏览 + SAF 上传/下载 + 删除/重命名/移动
├── EditorActivity.kt    # 远程文本编辑 + 编码检测 + 无损写回
├── PreviewActivity.kt   # 图片预览/分享 + APK 信息卡片与安装
├── NoteDialogActivity.kt# 小组件"快速记录"输入弹窗
├── QuickNoteWidget.kt   # 桌面小组件（AppWidgetProvider）
├── Prefs.kt             # 本地配置与状态
├── FileListAdapter.kt   # 文件列表适配
└── EncodingDetector.kt  # 编码自动检测
```

**关键设计**（都是踩坑后定的规矩）：

- 移动端**不发** `OPTS UTF8 ON`（会干扰被动数据通道）；直接设 `controlEncoding`（按服务器，ASUS 路由 = UTF-8），老版本 Prefs 做一次性 GBK→UTF-8 迁移。
- 删除 / 重命名 / 移动走**控制通道**（`DELE` / `RNFR`+`RNTO`），不用数据通道；`RNTO` 必须发**根相对完整路径**（裸文件名会相对 CWD 解析到根目录而失败）。
- 失败的控制命令后**不再**调 `completePendingCommand()`（Commons Net 已消费回复，多调一次会阻塞到 `soTimeout`）。
- 快速记录用 `RETR`+`STOR` 覆盖写回，不用 `APPEND`（规避路由器对 APPEND 的未知行为）。

---

## 双 App 说明

| 包名 | 角色 | 版本 |
|---|---|---|
| `com.blink` | **稳定基线**（QuickEdit Simple） | v1.0 |
| `cn.blink` | **开发主线**（本仓库） | v1.21 |

两条 App 可**共存**于同一设备，便于边开发边拿基线做对照回归。

---

## 当前版本

**v1.21（versionCode 23）** —— 桌面小组件快速记录完善：最近三条 + 蓝底装饰 + 冷启动自动读已存账号（无需先开 App）+ 首次使用"设定一次目标路径，之后自动记住"。

---

## 路线图

- **v1.22 设置页**：小组件可配置——显示条数 / 字号 / 装饰图开关 / 目标路径 / **记录用户名**（多设备共用一台 FTP 时区分记录来源；卡片前台显示「用户名 + 内容」，FTP 文件后台记录「时间戳 [用户名] 内容」），配置即时刷新小组件
- 小组件尺寸自适应（不同 launcher 格子高度不一，按配置自动建议 1 格 / 2 格）
- FTPS / SFTP 加密传输

---

## 许可证

MIT（按需）——本仓库为个人网络运维工具。
