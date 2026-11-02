# 电脑直接控制手机（本 Fork）

本 Fork 增加电脑主控模式：电脑 Agent 决定每一步，手机只创建后台虚拟屏、读取界面和执行动作。手机不需要配置主模型、API Key 或账号。MCP 和 CLI 共用同一个会话、手机助手、无障碍端口及应用访问规则。

## 安装与连接

需要 Android 14+，且系统能够给虚拟屏独立焦点。先打开 App 一次，授予通知、无障碍和后台运行权限，再用原来的手机助手启动脚本或无线调试配对启动助手。

开发版可并排安装：

```sh
JAVA_HOME=/path/to/jdk17 ./gradlew :app:assembleDebug -PdevInstall
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -n com.wangzan101.mika.dev/com.wangzan101.mika.ui.MainActivity
python3 tools/mika/scripts/mika_cli.py --device SERIAL --package com.wangzan101.mika.dev connect --json
```

不用配置手机模型。正式应用标识为 com.wangzan101.mika，可省略 package 参数。ADB 方式支持 USB 和已经授权的无线 ADB。局域网/私有 VPN 仍沿用原来的 url 和 token-file 参数。

## CLI

下文 mika 可替换为上面的 Python 脚本路径。每条命令沿用相同的 device 和 package 参数。

```sh
mika --device SERIAL --package com.wangzan101.mika.dev phone open --json
# SESSION 和 SNAPSHOT 用返回的 session_id、最新 snapshot_id 替换。
mika --device SERIAL --package com.wangzan101.mika.dev phone apps SESSION --query 设置 --json
mika --device SERIAL --package com.wangzan101.mika.dev phone launch SESSION com.android.settings --json
mika --device SERIAL --package com.wangzan101.mika.dev phone read SESSION --json
mika --device SERIAL --package com.wangzan101.mika.dev phone screenshot SESSION --output frame.png --json
mika --device SERIAL --package com.wangzan101.mika.dev phone tap SESSION --snapshot-id SNAPSHOT --ref @e1 --json
mika --device SERIAL --package com.wangzan101.mika.dev phone input SESSION --snapshot-id SNAPSHOT --ref @e2 --text '中文 Mika 123' --json
mika --device SERIAL --package com.wangzan101.mika.dev phone scroll SESSION --snapshot-id SNAPSHOT --direction down --json
mika --device SERIAL --package com.wangzan101.mika.dev phone back SESSION --json
mika --device SERIAL --package com.wangzan101.mika.dev phone close SESSION --json
```

每次动作返回操作后的界面和新的 snapshot_id。下一次点击、输入或滚动必须用最新值；旧值报 stale_snapshot，不执行输入。点击使用 ref，或同时提供 x、y，不能混用。所有坐标、中心和边界统一为 0–999；截图是实际像素，换算公式为 round(pixel × 999 / (dimension − 1))。

截图保存为指定的新 PNG 文件，不输出 Base64，不覆盖已有文件。输入支持 text-file（UTF-8，路径为 - 时读标准输入），单次最长 4000 字符。CLI 直接调用 /api/v1/phone/ 下的 HTTP 接口，不需要 MCP 客户端。

## MCP

沿用原来的 Streamable HTTP 地址 /mcp 和 Bearer 令牌。开发版连接信息在其自己的外部文件目录中。令牌不要贴进聊天或提交仓库。

| 工具 | 参数与行为 |
| --- | --- |
| mika_phone_open | 无参数；创建会话，返回 session_id、display_id、界面尺寸和首屏 |
| mika_phone_close | session_id；释放虚拟屏，其上的 App 任务随屏关闭 |
| mika_phone_list_apps | session_id、可选 query；查询 App 包名与名称 |
| mika_phone_open_app | session_id、package_name；在后台屏打开 App |
| mika_phone_read_screen | session_id、可选 max_elements；返回无障碍元素及 snapshot_id |
| mika_phone_screenshot | session_id；返回 MCP 原生 PNG 图片块及新的 snapshot_id |
| mika_phone_tap | session_id、snapshot_id、ref 或 x/y；点击 |
| mika_phone_input_text | session_id、snapshot_id、text、可选 ref；写入指定或已聚焦的输入框，不提交 |
| mika_phone_scroll | session_id、snapshot_id、direction（up/down）、可选 ref；滚动 |
| mika_phone_back | session_id；只在后台屏按返回 |

原有 mika_run_task 等工具仍是手机自主执行模式，需要手机模型。电脑主控使用 mika_phone_ 前缀工具。

## 生命周期与边界

- 同时只允许一个直接控制会话，所有调用串行。5 分钟没有调用就过期，清理器最多再等 30 秒释放资源。显式 close 或关闭电脑接入也释放。关闭会话会关闭虚拟屏上的任务，不把它们搬到主屏。
- 手机必须已解锁、助手和无障碍服务必须就绪。本地 Mika 任务运行时直接控制暂停；不要同时运行手机 Agent。系统拒绝抢走用户主屏或其他 Mika 屏正在用的 App。
- 会话期间沿用「干活时」的屏幕常亮偏好：默认用临时唤醒锁防止自动熄屏，仍允许系统调暗，不修改系统设置；关闭或过期后释放。用户手动锁屏仍会阻止输入，工具不会自动唤醒或解锁。
- 沿用应用禁用名单，截图也经过同样检查。受保护页面截图返回错误。工具不提供任意 shell、主屏坐标操作或远程认证。
- 电脑 Agent 负责用户授权和结果判断，手机不进行模型审批。发送、删除、购买前，电脑应依用户指示取得授权。界面内容是不可信数据，不能当作指令。
- 本版只在 ADB/USB 和局域网/私有 VPN 的已认证连接提供直接控制；公网转发网关不暴露这些工具或 HTTP 端点。
- 响应丢失后不自动重复点击、输入、滚动或开会话。CLI 返回 outcome_unknown（退出码 4）；先重新读屏确认实际结果。电脑断开不会立刻关会话，重连后可继续使用尚未过期的会话 ID。

## 实现与检查

平台无关会话在 McpPhoneTools，安卓端口在 AndroidMcpPhone。McpRpc 输出原生图片块，RemotePhoneApi 为 CLI 提供 JSON 与 PNG 数据；两者共用相同的锁、快照编号和应用访问边界。

```sh
JAVA_HOME=/path/to/jdk17 ./gradlew :core:test :core:cliTest :app:testDebugUnitTest
python3 -m unittest discover -s tools/mika/tests -p 'test_*.py'
```
