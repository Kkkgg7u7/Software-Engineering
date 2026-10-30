# -*- coding: utf-8 -*-
"""
一键启动脚本（Windows PowerShell 版请用 start-lan.ps1）。
本脚本给 bash / Git Bash / WSL 用户使用。

作用：用"文件库 + 监听局域网"的方式同时拉起后端与前端，并打印别人该用的地址。
"""
import os
import socket
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
BACKEND = os.path.join(ROOT, "backend")
FRONTEND = os.path.join(ROOT, "frontend")


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def main() -> int:
    ip = lan_ip()
    print("=" * 70)
    print(" AI 狼人杀 · 局域网联机启动")
    print("=" * 70)

    jar = os.path.join(BACKEND, "target", "werewolf-server.jar")
    if not os.path.exists(jar):
        print("× 未找到后端 jar，请先执行：")
        print("    cd backend && mvn -DskipTests package")
        return 1

    env = dict(os.environ)
    # 文件库：注册的账号在重启后仍然存在
    env["WEREWOLF_DB_URL"] = (
        "jdbc:h2:file:./data/werewolf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"
    )

    print("\n[1/2] 启动后端（文件库模式，端口 8080）...")
    backend = subprocess.Popen(
        ["java", "-Dfile.encoding=UTF-8", "-jar", jar, "--spring.profiles.active=h2"],
        cwd=BACKEND, env=env,
    )

    print("      等待后端就绪 ...")
    ready = False
    for _ in range(40):
        time.sleep(1)
        try:
            s = socket.create_connection(("127.0.0.1", 8080), timeout=1)
            s.close()
            ready = True
            break
        except OSError:
            continue
    if not ready:
        print("× 后端启动超时，请检查是否已占用 8080 端口")
        backend.terminate()
        return 1
    print("      √ 后端已就绪")

    print("\n[2/2] 启动前端（监听 0.0.0.0:5173）...")
    frontend = subprocess.Popen(
        ["pnpm", "dev", "--host", "--port", "5173"], cwd=FRONTEND, env=env
    )

    print()
    print("=" * 70)
    print(" 启动完成！")
    print("=" * 70)
    print(f"  你自己打开      : http://localhost:5173")
    print(f"  发给别人打开    : http://{ip}:5173        <-- 把这个发给他们")
    print()
    print("  演示账号        : demo / player1 / player2 / player3（密码 demo1234）")
    print()
    print("  如果别人打不开，请确认已放行防火墙端口 8080 与 5173：")
    print("    管理员 PowerShell 执行一次即可：")
    print('    New-NetFirewallRule -DisplayName "Werewolf 8080" -Direction Inbound '
          '-Protocol TCP -LocalPort 8080 -Action Allow')
    print('    New-NetFirewallRule -DisplayName "Werewolf 5173" -Direction Inbound '
          '-Protocol TCP -LocalPort 5173 -Action Allow')
    print()
    print("  按 Ctrl+C 停止全部服务")
    print("=" * 70)

    try:
        frontend.wait()
    except KeyboardInterrupt:
        print("\n正在停止服务 ...")
    finally:
        frontend.terminate()
        backend.terminate()
        print("已停止。")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
