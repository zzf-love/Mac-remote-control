#!/bin/bash
# 还原 setup.sh 对这台 Mac 做过的改动
#
# 用法（在 Mac 的终端里）：
#   bash restore.sh
#
# 原则：按 ~/.macremote-original-state 的快照精确回滚——
#   - 原本就开着的东西（如你早就开了屏幕共享）不会被关掉
#   - 原本是关的才关回去；pmset 恢复成记录的原值而非默认值
#   - 每一步都先征求同意；找不到快照时退化为引导式手动还原
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

read_state() { grep "^$1=" "$BACKUP_FILE" 2>/dev/null | head -1 | cut -d= -f2-; }

echo "${BOLD}===== Mac 遥控 · 还原 =====${RESET}"
echo

HAVE_BACKUP=no
if [ -f "$BACKUP_FILE" ]; then
    HAVE_BACKUP=yes
    ok "找到原始状态快照：${BACKUP_FILE}（记录于 $(read_state CREATED_AT)）"
else
    warn "没找到快照文件 ${BACKUP_FILE}（可能 setup.sh 是旧版本时跑的）。"
    warn "将按保守方式引导还原：每一步都问你，拿不准的不动。"
fi
echo

# ---------- 1/5 屏幕共享 ----------
echo "${BOLD}[1/5] 屏幕共享${RESET}"
WAS_ON=$(read_state SCREEN_SHARING_WAS_ON)
if ! vnc_listening; then
    ok "屏幕共享当前未开启，无需处理"
elif [ "$HAVE_BACKUP" = "yes" ] && [ "$WAS_ON" = "yes" ]; then
    ok "运行 setup 之前它就是开着的，按快照保持原样（不关闭）"
else
    if ask "  关闭屏幕共享（需要 sudo）？"; then
        sudo launchctl unload -w /System/Library/LaunchDaemons/com.apple.screensharing.plist 2>/dev/null
        sleep 2
        if vnc_listening; then
            sudo launchctl bootout system/com.apple.screensharing 2>/dev/null
            sleep 2
        fi
        if vnc_listening; then
            warn "命令行未能关闭，已为你打开设置页：系统设置 → 通用 → 共享 → 关闭「屏幕共享」"
            open_sharing_pane
        else
            ok "已关闭"
        fi
    else
        warn "保留屏幕共享"
    fi
fi
echo

# ---------- 2/5 VNC 密码 ----------
echo "${BOLD}[2/5] VNC 密码${RESET}"
FILE_EXISTED=$(read_state VNC_PASSWORD_FILE_EXISTED)
if [ ! -f "/Library/Preferences/com.apple.VNCSettings.txt" ]; then
    ok "没有 VNC 密码残留，无需处理"
elif [ "$HAVE_BACKUP" = "yes" ] && [ "$FILE_EXISTED" = "yes" ]; then
    ok "运行 setup 之前就设置过 VNC 密码，按快照保持原样"
else
    warn "VNC 密码是配置过程中设置的。取消勾选需要手动："
    echo "    系统设置 → 通用 → 共享 → 屏幕共享 ⓘ → 取消「VNC 检视程序可以使用密码控制屏幕」"
    if ask "  打开设置页去取消勾选？"; then open_sharing_pane; fi
    if ask "  同时删除密码文件 /Library/Preferences/com.apple.VNCSettings.txt（需要 sudo）？"; then
        sudo rm -f /Library/Preferences/com.apple.VNCSettings.txt && ok "已删除"
    fi
fi
echo

# ---------- 3/5 Tailscale ----------
echo "${BOLD}[3/5] Tailscale${RESET}"
WAS_INSTALLED=$(read_state TAILSCALE_WAS_INSTALLED)
TS_PRESENT=no
{ command -v tailscale >/dev/null 2>&1 || [ -d "/Applications/Tailscale.app" ]; } && TS_PRESENT=yes
if [ "$TS_PRESENT" = "no" ]; then
    ok "未安装 Tailscale，无需处理"
elif [ "$HAVE_BACKUP" = "yes" ] && [ "$WAS_INSTALLED" = "yes" ]; then
    ok "运行 setup 之前就装了 Tailscale，按快照保持原样"
else
    if brew list --cask tailscale >/dev/null 2>&1; then
        if ask "  卸载 Tailscale（brew uninstall --cask tailscale）？"; then
            osascript -e 'quit app "Tailscale"' 2>/dev/null
            brew uninstall --cask tailscale && ok "已卸载"
            warn "可选：到 https://login.tailscale.com/admin/machines 把这台 Mac 从设备列表移除"
        else
            warn "保留 Tailscale"
        fi
    else
        warn "Tailscale 不是 Homebrew 装的（App Store 或手动安装）。手动卸载："
        echo "    退出菜单栏的 Tailscale → 把 /Applications/Tailscale.app 拖到废纸篓"
        echo "    可选：到 https://login.tailscale.com/admin/machines 移除这台设备"
    fi
fi
echo

# ---------- 4/5 电源设置（pmset） ----------
echo "${BOLD}[4/5] 电源设置${RESET}"
P_SLEEP=$(read_state PMSET_AC_SLEEP)
P_TCPKA=$(read_state PMSET_AC_TCPKEEPALIVE)
P_WOMP=$(read_state PMSET_AC_WOMP)
if [ "$HAVE_BACKUP" = "yes" ] && [ -n "$P_SLEEP$P_TCPKA$P_WOMP" ]; then
    echo "  快照原值（接电源档）：sleep=${P_SLEEP:-未记录} tcpkeepalive=${P_TCPKA:-未记录} womp=${P_WOMP:-未记录}"
    if ask "  恢复为以上原值（需要 sudo）？"; then
        [ -n "$P_SLEEP" ] && sudo pmset -c sleep "$P_SLEEP"
        [ -n "$P_TCPKA" ] && sudo pmset -c tcpkeepalive "$P_TCPKA"
        [ -n "$P_WOMP" ] && sudo pmset -c womp "$P_WOMP"
        ok "已恢复。当前生效值："
        pmset -g 2>/dev/null | awk '$1=="sleep"||$1=="tcpkeepalive"||$1=="womp"{printf "    %s %s\n",$1,$2}'
    else
        warn "保留当前电源设置"
    fi
else
    warn "快照里没有电源原值。两个选择："
    echo "    a) 手动设回：sudo pmset -c sleep <分钟数>"
    echo "    b) 恢复系统出厂电源设置（会清掉你所有的 pmset 自定义，不只本项目改的）"
    if ask "  执行 b) sudo pmset restoredefaults？"; then
        sudo pmset restoredefaults && ok "已恢复出厂电源设置"
    fi
fi
echo

# ---------- 5/5 keepawake launchd（如果用过 plist 方案） ----------
echo "${BOLD}[5/5] keepawake 常驻任务${RESET}"
PLIST="$HOME/Library/LaunchAgents/com.macremote.keepawake.plist"
if [ -f "$PLIST" ]; then
    if ask "  检测到 keepawake plist，卸载并删除？"; then
        launchctl unload "$PLIST" 2>/dev/null
        rm -f "$PLIST" && ok "已移除"
    fi
else
    ok "未使用 plist 防睡眠方案，无需处理"
fi
echo

# ---------- 收尾 ----------
echo "${BOLD}===== 还原汇总 =====${RESET}"
if vnc_listening; then warn "VNC 服务：仍在线"; else ok "VNC 服务：已关闭"; fi
if [ "$HAVE_BACKUP" = "yes" ]; then
    ARCHIVE="$BACKUP_FILE.restored-$(date '+%Y%m%d-%H%M%S')"
    mv "$BACKUP_FILE" "$ARCHIVE"
    ok "快照已归档为 ${ARCHIVE}（下次跑 setup.sh 会重新记录新快照）"
fi
echo "手机端如不再使用：直接卸载「Mac 遥控」App 和 Tailscale 即可，"
echo "App 的连接信息只存在手机本地，卸载即清除。"
