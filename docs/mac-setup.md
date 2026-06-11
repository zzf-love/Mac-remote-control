# Mac 端一次性配置

目标：让 Mac 成为一台**随时可被你的手机连上**的 VNC 服务器。

## 推荐：跑一键脚本

```bash
bash mac/setup.sh
```

脚本会逐项体检并尽量代劳：开屏幕共享（launchctl）、装 Tailscale（brew）、
设防睡眠（pmset），并在结束时直接给出手机 App 要填的地址。唯一必须手动的
是勾选 VNC 密码选项——macOS 出于安全设计不允许脚本改这一项，脚本会帮你
打开对应设置页。

以下是各步骤的手动版说明与原理，脚本顺利的话可以跳过，出问题时回来查。

## 1. 开启屏幕共享 + VNC 密码

macOS Ventura 及更新（系统设置改版后）：

1. **系统设置 → 通用 → 共享**，打开 **屏幕共享**。
2. 点屏幕共享右侧的 **ⓘ**，勾选 **“VNC 检视程序可以使用密码控制屏幕”**，
   设置一个密码（**最长 8 个字符**，这是 VNC 协议的历史限制）。
3. （可选收紧）“允许访问”选择“仅这些用户：你自己”。

老版本 macOS：系统偏好设置 → 共享 → 屏幕共享 → 电脑设置… → 勾选
“VNC 显示程序可以使用密码控制屏幕”。

> 不勾 VNC 密码选项的话，Mac 只提供 Apple 私有认证（用 macOS 账户登录），
> 本 App 暂不支持（在路线图上）。连接时 App 会明确报这个错。

## 2. 安装 Tailscale（推荐的网络层）

1. Mac：App Store 装 **Tailscale**（或 `brew install --cask tailscale`），
   登录你的账号（可用 Google/GitHub 账号）。
2. 安卓手机：Play 商店装 **Tailscale**，登录**同一个账号**。
3. 在 Tailscale 菜单或 admin 控制台查看 Mac 拿到的 IP（形如 `100.x.y.z`），
   这就是 App 连接页要填的地址。

原理：两台设备组成一个 WireGuard 加密的虚拟局域网，手机在任何网络下都能
直达 Mac，无需路由器端口映射，5900 端口也不暴露在公网。

> 国内直连偶尔不佳时的优化见 docs/roadmap.md 的“自建中继（用你的 AWS）”一节。

## 3. 防睡眠（“随时”的关键，最容易踩的坑）

Mac 一旦深度睡眠，网络断开就连不上了。任选其一：

**方案 A：pmset（推荐，重启后仍生效）**

```bash
# 接电源时永不系统睡眠（显示器可以正常熄灭，不影响远程连接）
sudo pmset -c sleep 0
# 笔记本合盖仍保持网络可达性相关的小睡唤醒
sudo pmset -c tcpkeepalive 1
# 查看当前设置
pmset -g
```

**方案 B：launchd + caffeinate（不动系统设置，可随时卸载）**

```bash
cp mac/com.macremote.keepawake.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.macremote.keepawake.plist
# 卸载：launchctl unload ~/Library/LaunchAgents/com.macremote.keepawake.plist
```

另外建议：系统设置 → 显示器/电池 → 高级 →（接电源时）打开
**“网络访问时唤醒”（Wake for network access）**。

笔记本注意：以上设置都以**接着电源**为前提；用电池时长期防睡眠会很快耗光电。

## 4. 验证

先在局域网内验证服务端正常（排除变量）：

```bash
# 在 Mac 上确认 5900 在监听
sudo lsof -iTCP:5900 -sTCP:LISTEN
```

手机连 Mac 同一 WiFi，用本 App 填 Mac 的局域网 IP 试连；通了之后再换成
Tailscale IP，关掉手机 WiFi 用流量测“随时随地”是否成立。

## 还原 / 卸载

`setup.sh` **第一次运行时**会先把原始状态快照到 `~/.macremote-original-state`，
之后任何时候想撤销：

```bash
bash mac/restore.sh
```

还原脚本按快照**精确回滚**，原则是"只撤销 setup 造成的差异"：

| setup 改了什么 | restore 怎么处理 |
| --- | --- |
| 开启了屏幕共享 | 原本就开着 → 不动；原本关着 → 关回去（launchctl，失败则打开设置页引导） |
| 引导你设了 VNC 密码 | 原本就设过 → 不动；新设的 → 引导取消勾选，并可选删除密码文件 |
| 装了 Tailscale | 原本就装了 → 不动；脚本装的 → brew 卸载（非 brew 装的给手动步骤） |
| pmset 防睡眠三项 | 恢复成快照里的**原值**（不是出厂值）；无快照时才提供 `pmset restoredefaults` 兜底 |
| keepawake plist（若用过） | unload + 删除 |

每一步都会先征求同意；还原完成后快照归档为 `.restored-时间戳`，
下次再跑 setup.sh 会重新记录新快照。

手机端不想用了：卸载 App 和 Tailscale 即可，连接信息只存在手机本地。

## 常见问题

| 现象 | 原因 / 解决 |
| --- | --- |
| 连接立即被拒 | 屏幕共享没开，或被 Mac 防火墙拦了（系统设置→网络→防火墙，允许屏幕共享） |
| 报“只提供 macOS 账户认证” | 第 1 步的 VNC 密码选项没勾 |
| 密码总是错 | VNC 密码 ≠ Mac 登录密码；且只取前 8 个字符 |
| 白天能连晚上连不上 | Mac 睡眠了，回到第 3 步 |
| Tailscale IP ping 不通 | 两端任一 Tailscale 没登录/被系统杀后台，重新打开即可 |
