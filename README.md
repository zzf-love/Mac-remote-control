# Mac 遥控（Mac-remote-control）

自己写的安卓 App，随时随地全屏远程控制自己的 Mac。

## 架构：把力气花在刀刃上

```
┌─────────────── 安卓手机 ───────────────┐      ┌──────────────── Mac ────────────────┐
│  本仓库的专属 App（Kotlin）              │      │  macOS 自带「屏幕共享」服务            │
│  ├─ app  : 触摸手势/软键盘/⌘⌥⌃⇧ 工具栏  │ VNC  │  （系统内置的 VNC 服务器，零代码）      │
│  └─ core : 自研 RFB/VNC 协议栈          │─────▶│  屏幕采集 / 鼠标键盘注入由系统完成      │
│            （握手/认证/ZRLE 解码）       │ 5900 │                                      │
└────────────────────────────────────────┘      └──────────────────────────────────────┘
                    └──────────── Tailscale 虚拟组网（任何网络下手机直达 Mac）────────────┘
```

三个决定，三个理由：

1. **客户端完全自研**（这是"你自己的软件"的核心）：连接页、手势、键盘、工具栏、
   协议栈都是本仓库的代码，想加什么功能直接改。
2. **服务端用 macOS 自带的屏幕共享**：屏幕采集、H 速编码、键鼠注入、权限，这些是
   远程桌面最难啃的部分，系统已经做好且开机自启，不必重造。后续想要更低延迟，
   再按 [docs/roadmap.md](docs/roadmap.md) 升级为自研 Mac agent。
3. **网络层用 Tailscale**：解决"随时"二字——手机在 4G/别处 WiFi 也能直连家里
   路由器后面的 Mac，免端口映射、免公网暴露。

## 仓库结构

```
android/
├── core/   纯 JVM 的 RFB/VNC 协议库：版本协商(兼容 macOS 的 003.889)、
│           VNC 密码认证(DES)、Raw/CopyRect/ZRLE 解码、keysym 映射。
│           不依赖 Android，可独立测试：cd android && ./gradlew -p core test
└── app/    安卓壳：连接页 + 远程画布(平移/缩放/点击/拖拽/滚轮手势) +
            软键盘(支持中文 commitText) + ⌘⌥⌃⇧ 粘滞修饰键工具栏。零三方依赖。
docs/
├── mac-setup.md   Mac 端一次性配置（屏幕共享、VNC 密码、Tailscale、防睡眠）
└── roadmap.md     升级路线（自研 agent、WebRTC、快捷任务面板、AWS 自建中继……）
mac/
└── com.macremote.keepawake.plist   防睡眠 launchd 配置（可选）
.github/workflows/android.yml       CI：跑测试 + 自动构建 APK
```

## 快速开始

### 1. 配置 Mac（一次性，约 10 分钟）

在 Mac 终端里跑一键体检脚本，它能自动做的自动做、不能自动的会打开对应设置页：

```bash
bash mac/setup.sh
```

详细说明与排错见 [docs/mac-setup.md](docs/mac-setup.md)。

### 2. 获取 APK（按方便程度排序）

- **仓库首页右侧 [Releases](../../releases/latest)** → 下载 `MacRemote.apk`
  装到手机（需允许安装未知来源应用）。每次推送代码后 CI 自动更新。
- 备选：**Actions** 页签 → 最近一次运行 → 底部 Artifacts。
- 本地构建：Android Studio 打开 `android/` 目录，或
  `cd android && ./gradlew :app:assembleDebug`，产物在
  `android/app/build/outputs/apk/debug/`。

### 3. 连接

手机装好 Tailscale 并登录同一账号 → 打开本 App → 填 Mac 的 Tailscale IP
（形如 `100.x.y.z`）、端口 5900、VNC 密码 → 连接。

## 操作手势

| 手势 | 动作 |
| --- | --- |
| 单击 | 左键点击 |
| 双击 | 左键双击 |
| 长按后拖动 | 按住左键拖拽（窗口/选中文本） |
| 双指轻点 | 右键 |
| 单指拖动 | 平移画面 |
| 双指捏合 | 缩放画面 |
| 双指上下滑 | 滚轮 |
| 工具栏 ⌘⌥⌃⇧ | 粘滞键：点亮后敲字母即组成快捷键（再点熄灭） |

## 安全须知

- **永远不要**把 5900 端口映射到公网；只经 Tailscale 访问。
- VNC 密码认证（DES）按现代标准很弱，这是 macOS 的历史限制（≤8 字符）——
  安全性由 Tailscale 的 WireGuard 加密链路兜底，VNC 密码只是第二道门。
- 连接页的密码保存在应用私有 SharedPreferences（明文）。个人设备够用；
  介意的话留空密码、每次手输。

## 当前状态

- ✅ core 协议栈 + 15 个单元测试（含假 VNC 服务器端到端握手测试）全部通过
- ⏳ app 壳已完成，待真机首次联调（本仓库 CI 会出 APK）
- 📋 后续计划见 [docs/roadmap.md](docs/roadmap.md)
