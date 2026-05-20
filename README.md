# ZYYH 远程测试控制系统

## 系统架构

```
┌─────────────────┐     WebSocket      ┌──────────────────┐     WebSocket      ┌──────────────────┐
│  控制端手机      │ ◄──────────────►   │  中继服务器       │ ◄──────────────►   │  被控端手机       │
│  (操作者)        │                    │  (云服务器/VPS)   │                    │  (测试机)         │
│                  │                    │  port 8080       │                    │                   │
│  显示UI布局      │                    │                   │                    │  Shizuku + ADB    │
│  发送点击命令    │                    │  消息转发         │                    │  执行input命令    │
│  发送按键命令    │                    │  房间配对         │                    │  uiautomator dump │
└─────────────────┘                    └──────────────────┘                    └──────────────────┘
```

### 技术原理

- **UI 获取**: 通过 `uiautomator dump` 获取界面布局 XML（无需截屏，不受 FLAG_SECURE 限制）
- **触控模拟**: 通过 `input tap/swipe/text` 命令模拟用户操作（需 ADB Shell 权限）
- **权限提升**: 使用 Shizuku 授予应用 Shell 级权限（无需 root）
- **远程通信**: 通过 WebSocket 中继服务器实现互联网远程连接

---

## 一、前置准备

### 1.1 硬件要求
| 设备 | 要求 |
|------|------|
| 被控手机 | Android 7.0+，未 root，可安装 Shizuku |
| 控制手机 | Android 7.0+ |
| 中继服务器 | 有公网 IP 的云服务器/VPS（或同一局域网内可用内网 IP） |

### 1.2 软件安装

#### 被控手机
1. 安装 **Shizuku** 应用（[GitHub Releases](https://github.com/RikkaApps/Shizuku/releases) 或酷安）
2. 安装本项目编译的 **ZYYH被控端.apk**
3. 安装 **ADB 调试工具**（电脑端，用于激活 Shizuku）

#### 控制手机
1. 安装本项目编译的 **ZYYH控制端.apk**

#### 中继服务器
1. Node.js 16+ 运行环境
2. 本项目 `relay-server/` 目录

---

## 二、部署步骤

### Step 1: 部署中继服务器

#### 方式一：云服务器部署（推荐）

```bash
# 1. 上传 relay-server 目录到服务器
scp -r relay-server user@your-server:/opt/zyyh-relay/

# 2. SSH 登录服务器
ssh user@your-server

# 3. 进入目录并安装依赖
cd /opt/zyyh-relay
npm install

# 4. 启动（建议使用 pm2 保持后台运行）
npm install -g pm2
AUTH_TOKEN="zyyh_remote_test_2024" PORT=8080 pm2 start index.js --name zyyh-relay

# 5. 设置开机自启
pm2 startup
pm2 save

# 6. 配置防火墙（如果使用）
# Ubuntu: ufw allow 8080
# CentOS: firewall-cmd --add-port=8080/tcp --permanent && firewall-cmd --reload
```

#### 方式二：同一局域网内临时测试

```bash
# 在局域网内的任何电脑上运行
cd relay-server
npm install
PORT=8080 node index.js
# 记下这台电脑的局域网 IP（如 192.168.1.100）
```

#### 方式三：Docker 部署

```dockerfile
# Dockerfile
FROM node:18-alpine
WORKDIR /app
COPY package.json index.js ./
RUN npm install --production
EXPOSE 8080
ENV AUTH_TOKEN=zyyh_remote_test_2024
CMD ["node", "index.js"]
```

```bash
docker build -t zyyh-relay .
docker run -d -p 8080:8080 -e AUTH_TOKEN=zyyh_remote_test_2024 zyyh-relay
```

---

### Step 2: 激活被控手机 Shizuku

**方式 A：通过 ADB（推荐，无需 root）**

```bash
# 1. 手机开启"开发者选项"和"USB调试"
# 设置 → 关于手机 → 连续点击"版本号"7次
# 设置 → 开发者选项 → USB调试

# 2. 手机连接电脑，授权 USB 调试

# 3. 安装 Shizuku APK（如已安装可跳过）
adb install Shizuku_*.apk

# 4. 激活 Shizuku（关键步骤）
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh

# 5. 验证：打开 Shizuku 应用 → 显示"正在运行"
```

> 注意：每次手机重启后都需要重新执行第 4 步激活 Shizuku

**方式 B：通过无线 ADB（Android 11+）**

```bash
# 1. 手机开启"无线调试"
# 开发者选项 → 无线调试 → 开启

# 2. 使用配对码连接
adb pair 192.168.1.xxx:39723  # 输入手机上显示的配对码

# 3. 连接后激活 Shizuku
adb connect 192.168.1.xxx:37867
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh
```

**方式 C：root 用户（直接授权）**

```bash
# 在 Shizuku 应用中直接点击"启动"即可
```

---

### Step 3: 编译并安装被控端应用

#### 使用 Android Studio 编译

```bash
# 1. 用 Android Studio 打开 controlled-app/ 目录
# 2. 等待 Gradle 同步完成
# 3. Build → Build Bundle(s) / APK(s) → Build APK(s)
# 4. 生成的 APK 在 app/build/outputs/apk/debug/app-debug.apk
```

#### 或使用命令行编译

```bash
# 在 controlled-app/ 目录下
export ANDROID_HOME=/path/to/android/sdk
./gradlew assembleDebug
# 生成: app/build/outputs/apk/debug/app-debug.apk
```

#### 安装到被控手机

```bash
adb install app-debug.apk
# 或直接传输 APK 到手机手动安装
```

#### 授权 Shizuku 权限

1. 打开 **Shizuku** 应用
2. 在"已授权应用"列表中，找到 **ZYYH被控端**
3. 点击开关，授予权限
4. 或者直接打开 ZYYH被控端，会弹窗请求授权

---

### Step 4: 编译并安装控制端应用

```bash
# 1. 用 Android Studio 打开 controlling-app/ 目录
# 2. Build → Build APK(s)
# 3. 安装生成的 APK 到控制手机
```

---

### Step 5: 配置并连接

#### 被控端操作

1. 确保 Shizuku 已在运行状态
2. 打开 **ZYYH被控端** 应用
   - 检查 Shizuku 状态是否为"运行中"（绿色）
   - 如显示"未运行"，请检查 Step 2
3. 点击 **"连接到中继服务器"**
4. 填入：
   - 中继服务器地址：云服务器公网 IP 或局域网 IP
   - 端口：默认 8080
   - 房间ID：自定义（两端相同即可，如 `test_001`）
   - Token：与中继服务器设置一致（默认 `zyyh_remote_test_2024`）
5. 点击"连接"，等待显示"控制端已连接"

> **重要**：被控端不能使用截屏/录屏功能（因为目标 app 禁止），但 uiautomator dump 获取布局 XML 不受此限制。

#### 控制端操作

1. 打开 **ZYYH控制端** 应用
2. 点击 **"设置"**
3. 填入与被控端相同的配置（服务器地址、端口、房间ID、Token）
4. 点击"连接"
5. 状态显示"双方已连接"（绿色）后，点击 **"刷新UI"**
6. 此时将显示被控手机的界面布局

---

## 三、使用说明

### 3.1 界面操作

```
┌─────────────────────────────┐
│ [设置] [刷新UI] [返回] [主页] │  ← 工具栏
├─────────────────────────────┤
│ 状态: 双方已连接 ✓          │  ← 状态栏
├─────────────────────────────┤
│                             │
│   ┌───────────────────┐     │
│   │                   │     │
│   │   手机界面布局     │     │  ← RemoteView 区域
│   │   (可点击的元素     │     │     蓝色边框 = 可点击元素
│   │    用蓝色边框标出)  │     │     绿色边框 = 普通元素
│   │                   │     │
│   └───────────────────┘     │
├─────────────────────────────┤
│ Text: 确定                   │  ← 选中元素信息
│ ID: com.example:id/btn_ok   │
│ Bounds: [100,500][300,600]  │
│ [点击] [长按]               │
└─────────────────────────────┘
```

### 3.2 操作方式

| 操作 | 方法 | 说明 |
|------|------|------|
| **点击元素** | 点击蓝色边框的元素 | 元素高亮为红色边框 |
| **自定义点击** | 直接点击空白区域 | 发送精确坐标点击 |
| **刷新布局** | 点击"刷新UI"按钮 | 重新获取界面布局 |
| **返回键** | 点击"返回"按钮 | 发送 KEYCODE_BACK |
| **主页键** | 点击"主页"按钮 | 发送 KEYCODE_HOME |
| **输入文本** | 暂通过点击输入框后发送文本命令 | 功能待扩展 |
| **长按** | 选中元素后点击"长按"按钮 | 模拟长按操作 |

### 3.3 支持的指令

被控端 ShizukuShell 支持以下指令集：

| Action | 参数 | 说明 |
|--------|------|------|
| `dump_ui` | 无 | 获取界面布局 XML |
| `tap` | x, y | 点击坐标 |
| `swipe` | x1, y1, x2, y2, duration | 滑动操作 |
| `text` | text | 输入文本 |
| `keyevent` | key (int 或 String) | 按键事件 |
| `shell` | command | 任意 Shell 命令 |
| `get_screen_size` | 无 | 获取屏幕分辨率 |

---

## 四、高级用法

### 4.1 通过 ADB 直接调试（无需控制端应用）

可作为命令验证：

```bash
# 连接到被控手机
adb shell

# 获取界面布局（即使 FLAG_SECURE 也能工作）
uiautomator dump /data/local/tmp/dump.xml
cat /data/local/tmp/dump.xml

# 模拟点击
input tap 500 1000

# 输入文本
input text "hello"

# 按键
input keyevent KEYCODE_BACK
```

### 4.2 使用 PC 作为控制端

如果控制端想在 PC 上操作，可以使用浏览器作为控制端。需要扩展 relay-server 支持 HTTP 页面。

---

## 五、常见问题

### Q1: Shizuku 显示"未运行"
- 手机重启后需要重新激活 Shizuku
- 执行: `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh`

### Q2: uiautomator dump 返回空结果
- 部分 Android 13+ 设备需要额外授权
- 尝试使用 `cmd uiautomator dump /data/local/tmp/dump.xml`
- 检查目标 app 是否在前台运行

### Q3: 连接不上中继服务器
- 检查服务器防火墙是否开放了端口
- 云服务器需检查安全组规则
- 局域网测试建议先关闭防火墙验证

### Q4: 点击位置不准确
- RemoteView 会自动缩放适配屏幕
- 检查被控端屏幕分辨率是否正确获取
- 可在控制端点击"刷新UI"后查看元素 bounds 是否合理

### Q5: uiautomator dump 对 FLAG_SECURE 无效？
- **uiautomator dump 获取的是 View 层级树而非屏幕截图，不受 FLAG_SECURE 影响**
- 但某些 WebView 或 SurfaceView 内容的文本可能无法获取
- 这种情况下仍可通过坐标点击进行操作

### Q6: Android 14 兼容性
- 已验证 Android 14 上 Shizuku + uiautomator dump 正常工作
- 确保 Shizuku 是最新版本（13.x+）

---

## 六、项目文件说明

```
zyyh_remote_test/
├── relay-server/                    # 中继服务器 (Node.js)
│   ├── package.json
│   ├── index.js                     # WebSocket 中继服务
│   └── Dockerfile                   # Docker 部署文件
│
├── controlled-app/                  # 被控端 Android 应用
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/
│           └── java/.../
│               ├── ControlledApp.kt      # Application
│               ├── MainActivity.kt       # 主界面 + 连接管理
│               └── ShizukuShell.kt       # Shizuku Shell 执行器
│
├── controlling-app/                 # 控制端 Android 应用
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/
│           └── java/.../
│               ├── MainActivity.kt       # 主界面 + 控制逻辑
│               └── RemoteView.kt         # 自定义UI渲染视图
│
└── README.md                        # 本文件
```

---

## 七、安全提示

1. **Token 安全**：生产环境中请修改默认 Token，避免被未授权连接
2. **中继服务器**：建议部署在受控的网络环境中，或添加 SSL/WSS 加密
3. **房间ID**：使用唯一且不易猜测的房间 ID，防止他人串入
4. **敏感信息**：被控端可执行的 Shell 命令具有较高权限，谨慎使用 `shell` 指令

---

## 八、扩展开发

### 添加更多触控手势

在 `ShizukuShell.kt` 中添加：

```kotlin
suspend fun longPress(x: Int, y: Int) = swipe(x, y, x, y, 1000)
suspend fun pinchZoom(...) = ...
suspend fun scroll(direction: String) = ...
```

### 支持更多按键

通用按键码：`KEYCODE_VOLUME_UP`, `KEYCODE_POWER`, `KEYCODE_APP_SWITCH`,
`KEYCODE_NOTIFICATION`, `KEYCODE_SETTINGS`

完整列表见 [Android KeyEvent](https://developer.android.com/reference/android/view/KeyEvent)

---

## 九、技术总结

本方案的核心优势：

| 项目 | 方案 | 说明 |
|------|------|------|
| 截图限制 | uiautomator dump | 获取 View 层级 XML，无需截屏 |
| 无 root | Shizuku | 通过 ADB 授权获得 Shell 权限 |
| 无无障碍 | Shell 命令 | 直接调用系统 input 命令 |
| 远程连接 | WebSocket 中继 | 支持公网/内网传输 |
| 实时控制 | 按需刷新 | 点击"刷新UI"获取最新布局 |
