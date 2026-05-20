[![Build APKs](https://github.com/hlc890209/android-remote-control/actions/workflows/build.yml/badge.svg)](https://github.com/hlc890209/android-remote-control/actions/workflows/build.yml)

# 远程测试控制系统

## 系统架构

```
┌─────────────────┐         MQTT (pub/sub)          ┌──────────────────┐
│  控制端手机      │ ◄─────────────────────────────► │  被控端手机       │
│  (操作者)        │         公共 Broker              │  (测试机)         │
│                  │     broker.emqx.io:1883         │                   │
│  显示UI布局      │                                 │  Shizuku + ADB    │
│  发送点击命令    │   zyyh/{roomId}/command          │  执行input命令    │
│  接收UI数据      │   zyyh/{roomId}/result           │  uiautomator dump │
└─────────────────┘   zyyh/{roomId}/status           └──────────────────┘
```

### 技术原理

- **零服务器**：使用免费公共 MQTT Broker（broker.emqx.io）做消息中转，无需任何云服务器
- **UI 获取**：通过 `uiautomator dump` 获取界面布局 XML（无需截屏，不受 FLAG_SECURE 限制）
- **触控模拟**：通过 `input tap/swipe/text` 命令模拟用户操作（需 ADB Shell 权限）
- **权限提升**：使用 Shizuku 授予应用 Shell 级权限（无需 root）
- **消息协议**：MQTT pub/sub 模型，被控端订阅命令、发布结果，控制端反之

---

## 一、前置准备

### 1.1 硬件要求
| 设备 | 要求 |
|------|------|
| 被控手机 | Android 7.0+，未 root，可安装 Shizuku |
| 控制手机 | Android 7.0+ |

> **完全不需要服务器**，纯手机端运行。

### 1.2 软件安装

#### 被控手机
1. 安装 **Shizuku** 应用（GitHub: RikkaApps/Shizuku 或酷安搜索 Shizuku）
2. 安装本项目编译的 **ZYYH被控端.apk**
3. 电脑端安装 ADB 工具（用于激活 Shizuku）

#### 控制手机
1. 安装本项目编译的 **ZYYH控制端.apk**

---

## 二、部署步骤

### Step 1: 激活被控手机 Shizuku

**方式 A：通过 USB ADB（推荐）**

```bash
# 1. 手机开启"开发者选项"和"USB调试"
#    设置 → 关于手机 → 连续点击"版本号"7次
#    设置 → 开发者选项 → USB调试

# 2. 手机连接电脑，授权 USB 调试

# 3. 安装 Shizuku（如已安装可跳过）
adb install Shizuku_*.apk

# 4. 激活 Shizuku（关键步骤！每次重启后都需要重新执行）
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh

# 5. 验证：打开 Shizuku 应用 → 页面顶部显示"正在运行"
```

**方式 B：无线 ADB（Android 11+）**

```bash
# 1. 开发者选项 → 无线调试 → 开启
# 2. 使用配对码连接
adb pair 192.168.1.xxx:39723    # 输入手机显示的配对码
adb connect 192.168.1.xxx:37867
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh
```

---

### Step 2: 编译 Android 应用

#### 方式一：从 GitHub Actions 下载 APK（推荐，无需本地环境）

> 推荐！每次推送到 GitHub 都会自动编译，APK 可直接下载安装。

```
1. 打开 https://github.com/hlc890209/android-remote-control/actions
2. 点击最新一次的绿色勾 ✓ 的 workflow（或点击左侧 "Build APKs"）
3. 在页面底部 "Artifacts" 部分
4. 下载 ZYYH-Remote-APKs.zip
5. 解压后得到两个 APK：
   - controlled-app-debug.apk    → 安装到被控手机
   - controlling-app-debug.apk   → 安装到控制手机
```

#### 方式二：本地 Android Studio 编译

```
1. 用 Android Studio 打开 controlled-app/ 目录
2. 等待 Gradle 同步完成（首次会下载依赖，稍慢）
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. 生成路径: app/build/outputs/apk/debug/app-debug.apk

控制端同理打开 controlling-app/ 目录
```

#### 方式三：本地命令行编译（需安装 JDK 17 + Android SDK）

```bash
# 1. 安装 JDK 17
#    https://adoptium.net/temurin/releases/?version=17

# 2. 安装 Android SDK Command Line Tools
#    https://developer.android.com/studio#command-line-tools-only
#    解压到 C:\Android\cmdline-tools
#    运行 sdkmanager "platforms;android-34" "build-tools;34.0.0"

# 3. 设置环境变量
#    Windows:  set ANDROID_HOME=C:\Android
#    Linux:    export ANDROID_HOME=/opt/android-sdk

# 4. 编译被控端
cd controlled-app
gradle wrapper --gradle-version 8.5
.\gradlew assembleDebug
# APK: app\build\outputs\apk\debug\app-debug.apk

# 5. 编译控制端
cd ../controlling-app
gradle wrapper --gradle-version 8.5
.\gradlew assembleDebug
```

#### 安装到手机

```bash
# 被控端（通过 USB 连接电脑）
adb install controlled-app/app/build/outputs/apk/debug/app-debug.apk

# 控制端 APK 传输到手机手动安装（通过微信/QQ 发送文件后安装）
```

---

### Step 3: 授权 Shizuku 权限

1. 打开 **Shizuku** 应用
2. 在"已授权应用"列表中找到 **ZYYH被控端**
3. 打开开关授予权限
4. 或直接打开 ZYYH被控端，会弹出授权请求

---

### Step 4: 连接并开始使用

#### 被控端操作
1. 确保 Shizuku 已在运行状态
2. 打开 **ZYYH被控端**
   - 检查 Shizuku 状态
3. 点击 **"连接 MQTT Broker"**
4. 配置（通常保持默认即可）：
   - **MQTT Broker**: `broker.emqx.io`（免费公共）
   - **端口**: `1883`
   - **房间ID**: `test_001`（与控制端一致即可）
5. 点击"连接"，等待状态

#### 控制端操作
1. 打开 **ZYYH控制端**
2. 点击 **"设置"**
3. 填入与被控端相同的配置
4. 点击"连接"
5. 状态显示 "被控端在线" 后，点击 **"刷新UI"**
6. 被控手机界面将以布局图呈现

---

## 三、使用说明

### 操作方式

| 操作 | 方法 | 说明 |
|------|------|------|
| **点击元素** | 点击蓝色边框的元素 | 发送精确坐标点击 |
| **自定义点击** | 直接点击空白区域 | 点击任意位置 |
| **刷新布局** | 点击"刷新UI" | 重新获取界面布局 XML |
| **返回键** | 点击"返回" | 发送 KEYCODE_BACK |
| **主页键** | 点击"主页" | 发送 KEYCODE_HOME |
| **长按** | 选中元素 → "长按" | 模拟长按（1000ms） |
| **输入文本** | 选中输入框 → （功能待扩展） | 发送 text 命令 |

### UI 布局说明

```
┌──────────────────────────────┐
│ [设置] [刷新UI] [返回] [主页] │
├──────────────────────────────┤
│ 状态: 被控端在线 ✓           │
├──────────────────────────────┤
│                              │
│  ┌────────────────────┐      │
│  │                    │      │
│  │  手机界面布局图     │      │  ← 蓝色边框 = 可点击元素
│  │  (从 UI dump 渲染)  │      │     红色边框 = 当前选中
│  │                    │      │
│  └────────────────────┘      │
├──────────────────────────────┤
│ Text: 确定                    │
│ ID: com.example:id/btn_ok    │
│ [点击] [长按]                │
└──────────────────────────────┘
```

---

## 四、支持的指令

被控端 ShizukuShell 支持以下指令（通过 MQTT 发送 JSON 消息）：

| Action | 参数 | 说明 |
|--------|------|------|
| `dump_ui` | 无 | 获取界面布局 XML |
| `tap` | x, y | 点击指定坐标 |
| `swipe` | x1, y1, x2, y2, duration | 滑动 |
| `text` | text | 输入文本 |
| `keyevent` | key | 按键事件（如 KEYCODE_BACK） |
| `shell` | command | 任意 Shell 命令 |
| `get_screen_size` | 无 | 获取屏幕分辨率 |

---

## 五、常见问题

### Q1: Shizuku 显示"未运行"
```
手机重启后需重新激活：
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh
```

### Q2: uiautomator dump 获取不到 UI
- 确认目标 app 在前台运行
- Android 13+ 可尝试：`cmd uiautomator dump /data/local/tmp/dump.xml`
- 该命令获取的是 View 层级树，**不受 FLAG_SECURE 影响**

### Q3: 无法连接 MQTT Broker
- 检查手机网络是否正常（能正常上网）
- 默认使用 `broker.emqx.io:1883`，这是全球公共 Broker
- 备选：`test.mosquitto.org:1883`

### Q4: 点击位置偏移
- RemoteView 会自动缩放适配屏幕
- 点击 "刷新UI" 查看元素 bounds 是否正确
- 检查屏幕分辨率是否正常获取

### Q5: 被控端不在线
- 检查 MQTT 连接状态
- 确保两端 roomId 一致
- 公共 Broker 可能需要区分 clientId，代码已自动处理

---

## 六、项目文件

```
zyyh_remote_test/
├── controlled-app/                  # 被控端 Android 应用
│   ├── app/
│   │   ├── build.gradle.kts         # MQTT + Shizuku 依赖
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── res/layout/
│   │       └── java/.../
│   │           ├── ControlledApp.kt      # Application
│   │           ├── MainActivity.kt       # MQTT 连接 + 命令处理
│   │           └── ShizukuShell.kt       # Shizuku Shell 执行器
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── controlling-app/                 # 控制端 Android 应用
│   ├── app/
│   │   ├── build.gradle.kts         # MQTT 依赖
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── res/layout/
│   │       └── java/.../
│   │           ├── MainActivity.kt       # MQTT 连接 + 控制逻辑
│   │           └── RemoteView.kt         # UI 布局渲染
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
└── README.md                        # 本文件
```

---

## 七、MQTT 主题设计

| 主题 | 方向 | QoS | 说明 |
|------|------|-----|------|
| `zyyh/{roomId}/command` | 控制端→被控端 | 1 | 控制命令 |
| `zyyh/{roomId}/result` | 被控端→控制端 | 1 | 命令执行结果 |
| `zyyh/{roomId}/status` | 双向 | 1 | 在线状态（带 LWT） |

使用公共 Broker `broker.emqx.io:1883`，无需注册、无需认证。

---

## 八、安全提示

1. 公共 Broker 上所有消息都是明文传输，**不要用于生产环境敏感数据**
2. 房间ID 使用唯一值，防止同一 Broker 上的其他人串入
3. 如需要加密通信，可自行搭建私有 MQTT Broker（如 EMQX、Mosquitto）
4. 被控端 `shell` 命令具有较高权限，勿将 roomId 泄露给不可信方
