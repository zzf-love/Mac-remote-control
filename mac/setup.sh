#!/bin/bash
# Mac 端一键体检/配置：屏幕共享、VNC 密码、Tailscale、防睡眠
#
# 用法（在 Mac 的终端里）：
#   bash setup.sh
#
# 能自动做的自动做（会逐项征求同意，部分需要 sudo）；
# macOS 出于安全设计不允许脚本代劳的（如设置 VNC 密码），
# 会直接帮你打开对应的设置页面并告诉你点哪里。
#
# 还原：首次运行前会把原始状态快照到 ~/.macremote-original-state，
# 之后任何时候运行 `bash restore.sh` 都可以按快照精确回滚。
set -u

GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
ok()   { echo "${GREEN}✔${RESET} $1"; }
warn() { echo "${YELLOW}➜${RESET} $1"; }
bad()  { echo "${RED}✘${RESET} $1"; }
ask()  { printf "%s [y/N] " "$1"; read -r REPLY; [ "$REPLY" = "y" ] || [ "$REPLY" = "Y" ]; }

[ "$(uname)" = "Darwin" ] || { bad "这个脚本需要在 Mac 上运行"; exit 1; }

BACKUP_FILE="$HOME/.macremote-original-state"

open_sharing_pane() {
    open "x-apple.systempreferences:com.apple.Sharing-Settings.extension" 2>/dev/null \
        || open "/System/Library/PreferencePanes/SharingPref.prefPane" 2>/dev/null \
        || open -b com.apple.systempreferences 2>/dev/null
}

vnc_listening() { nc -z 127.0.0.1 5900 >/dev/null 2>&1; }

detect_tailscale() {
    TS=""
    command -v tailscale >/dev/null 2>&1 && TS="tailscale"
    [ -z "$TS" ] && [ -x "/Applications/Tailscale.app/Contents/MacOS/Tailscale" ] \
        && TS="/Applications/Tailscale.app/Contents/MacOS/Tailscale"
}

# 取接电源（AC）档的 pmset 值；台式机没有电池档时同样适用
get_ac_pmset() {
    pmset -g custom 2>/dev/null | awk -v k="$1" '
        /Battery Power:/{inbat=1} /AC Power:/{inbat=0}
        !inbat && $1==k {v=$2} END{print v}'
}

# ---------- 0/4 原始状态快照（只在第一次运行时记录） ----------
if [ ! -f "$BACKUP_FILE" ]; then
    detect_tailscale
    {
        echo "# Mac 遥控 setup.sh 首次运行前的原始状态快照"
        echo "# restore.sh 依据本文件精确回滚；请勿手工修改"
        echo "CREATED_AT=$(date '+%Y-%m-%d %H:%M:%S')"
        if vnc_listening; then echo "SCREEN_SHARING_WAS_ON=yes"; else echo "SCREEN_SHARING_WAS_ON=no"; fi
        if [ -f "/Library/Preferences/com.apple.VNCSettings.txt" ]; then echo "VNC_PASSWORD_FILE_EXISTED=yes"; else echo "VNC_PASSWORD_FILE_EXISTED=no"; fi
        if [ -n "$TS" ]; then echo "TAILSCALE_WAS_INSTALLED=yes"; else echo "TAILSCALE_WAS_INSTALLED=no"; fi
        echo "PMSET_AC_SLEEP=$(get_ac_pmset sleep)"
        echo "PMSET_AC_TCPKEEPALIVE=$(get_ac_pmset tcpkeepalive)"
        echo "PMSET_AC_WOMP=$(get_ac_pmset womp)"
    } > "$BACKUP_FILE"
    ok "已把原始状态快照到 ${BACKUP_FILE}（还原时用）"
    echo
fi

echo "${BOLD}===== Mac 遥控 · 服务端体检 =====${RESET}"
echo

# ---------- 1/4 屏幕共享 ----------
echo "${BOLD}[1/4] 屏幕共享（VNC 服务）${RESET}"
if vnc_listening; then
    ok "5900 端口在监听，屏幕共享已开启"
else
    bad "屏幕共享未开启"
    if ask "  试着用 launchctl 直接开启（需要 sudo）？"; then
        sudo launchctl load -w /System/Library/LaunchDaemons/com.apple.screensharing.plist 2>/dev/null
        sleep 2
        if vnc_listening; then
            ok "已开启"
        else
            warn "这台系统版本不允许命令行开启，已为你打开设置页："
            echo "    系统设置 → 通用 → 共享 → 打开「屏幕共享」"
            open_sharing_pane
        fi
    else
        warn "请手动开启：系统设置 → 通用 → 共享 → 屏幕共享（已为你打开设置页）"
        open_sharing_pane
    fi
fi
echo

# ---------- 2/4 VNC 密码 ----------
echo "${BOLD}[2/4] VNC 密码（手机 App 登录用）${RESET}"
if [ -f "/Library/Preferences/com.apple.VNCSettings.txt" ]; then
    ok "检测到已设置过 VNC 密码"
else
    warn "尚未设置 VNC 密码。macOS 不允许脚本代设这一项（安全限制），请手动："
    echo "    屏幕共享右侧 ⓘ → 勾选「VNC 检视程序可以使用密码控制屏幕」"
    echo "    → 设置密码（注意：最长 8 位，这是 VNC 协议限制）"
fi
echo

# ---------- 3/4 Tailscale ----------
echo "${BOLD}[3/4] Tailscale 组网${RESET}"
detect_tailscale
TS_IP=""
if [ -n "$TS" ]; then
    TS_IP=$("$TS" ip -4 2>/dev/null | head -1)
    if [ -n "$TS_IP" ]; then
        ok "Tailscale 已就绪，本机地址：${BOLD}${TS_IP}${RESET}"
    else
        warn "Tailscale 已安装但未登录，已为你打开它，请完成登录"
        open -a Tailscale 2>/dev/null
    fi
else
    bad "未安装 Tailscale"
    if command -v brew >/dev/null 2>&1 && ask "  用 Homebrew 安装？（brew install --cask tailscale）"; then
        brew install --cask tailscale && open -a Tailscale
        warn "装好后登录账号，然后重跑本脚本拿 IP"
    else
        warn "请从 App Store 或 https://tailscale.com/download 安装并登录"
        warn "手机端也装 Tailscale，登录同一个账号"
    fi
fi
echo

# ---------- 4/4 防睡眠 ----------
echo "${BOLD}[4/4] 防睡眠（保证随时连得上）${RESET}"
SLEEP_MIN=$(pmset -g 2>/dev/null | awk '$1=="sleep"{print $2; exit}')
if [ "${SLEEP_MIN:-1}" = "0" ]; then
    ok "系统不会自动睡眠"
else
    warn "当前自动睡眠：${SLEEP_MIN:-未知} 分钟。Mac 睡着后手机就连不上了。"
    if ask "  设为接电源时永不睡眠？（显示器照常熄屏，不影响远程）"; then
        sudo pmset -c sleep 0
        sudo pmset -c tcpkeepalive 1
        sudo pmset -c womp 1
        ok "已设置（sleep=0, tcpkeepalive=1, 网络唤醒=1，仅接电源时生效）"
    else
        warn "跳过。也可以用 launchd 方案：见 mac/com.macremote.keepawake.plist"
    fi
fi
echo

# ---------- 汇总 ----------
echo "${BOLD}===== 体检汇总 =====${RESET}"
if vnc_listening; then ok "VNC 服务：在线"; else bad "VNC 服务：未开启（上面第 1、2 步没完成）"; fi
if [ -n "$TS_IP" ]; then
    ok "手机 App 连接信息 → 地址：${BOLD}${TS_IP}${RESET}   端口：${BOLD}5900${RESET}   密码：你设置的 VNC 密码"
else
    warn "Tailscale IP 未拿到，登录后重跑：bash setup.sh"
fi
echo
echo "提示：先让手机连 Mac 同一 WiFi，用局域网 IP 试连验证服务端没问题，"
echo "      再换 Tailscale IP + 手机流量验证“随时随地”。"
echo "还原：随时运行 bash restore.sh，会按 ${BACKUP_FILE} 的快照精确回滚。"
