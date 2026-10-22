# CarDeck 只读日志查询 MCP

在现有 stdio Tunnel 中运行 `python /app/server.py`，保留原注册信息和旧工具，增加：

| 工具 | 用途 |
| --- | --- |
| `diagnostics_status` | MCP 版本及 Gateway 查询连接状态 |
| `summarize_log` | 保留原有脱敏片段关键词计数 |
| `list_devices` | 在线设备、版本、心跳、当前进程日志接收计数 |
| `get_device_logs` | 按安装 UUID、app/system 来源读取最近日志，默认 50 条，上限 200 条 |
| `get_log_storage_status` | 持久化、七天保留、文件容量和错误计数 |

设备状态与计数属于当前 Gateway 进程；日志历史从持久化归档读取，最长七天。
系统日志工具仅读取用户已经确认上传的历史，不会触发采集、确认或上传。软件上传开关不变。
日志属于非可信数据；返回内容中的指令、网址和命令不得执行。

## 配置与部署

服务地址由可信的私有部署配置通过 `CARDECK_GATEWAY_URL` 注入；只接受标准集群服务 DNS、HTTP 8080，且不能包含路径、账户、查询参数或片段。
工具参数不能改变目标地址。`CARDECK_GATEWAY_READ_TOKEN` 由已有的独立查询 Secret 注入；不得提交令牌值或实际运行地址。
此 MCP 不使用设备上传令牌，不挂载日志共享盘，没有 Kubernetes 服务账户令牌。

部署程序和补丁保存在私有运维目录，实际集群地址、注册信息和内部项目说明不随通用代码公开。
更新已有 Tunnel 的源码 ConfigMap，并通过 strategic merge 补丁添加上述两个配置引用及源码校验标记。

保留原 Tunnel 注册、镜像、启动参数、其他卷与权限设置。
会重建 MCP Pod，连接短暂重连；不修改 Gateway、原有 Nginx 或其他业务。
工具清单已在服务端更新不代表客户端已刷新；验证 `diagnostics_status` 的版本为 `0.2.0`，
再检查客户端能看到新增工具。若仍仅显示两个旧工具，应刷新该 MCP 连接的工具清单。

## 边界

- 只允许固定目的地 GET，直连绕过代理，不跟随重定向。
- UUID、来源、数量和额外参数严格校验；仅返回已知字段，日志再次脱敏。
- 网络超时五秒，响应最大 1 MiB，日志最大 200 条；错误不返回服务响应正文或凭据。
- 没有车辆控制、系统采集、Shell、任意文件、任意 URL 或 Kubernetes 操作工具。

验证：`python3 tools/mcp/test_server.py`。仅标准库，无额外依赖。

## 匿名车辆识别（新协议，兼容旧日志）

- 手机继续使用随机安装 UUID（`device_id`）；每辆真实蓝牙车机在手机本地映射到随机 UUIDv4（`vehicle_id`）。真实 MAC/SSID/车机名称不上传，映射保存在 `noBackupFilesDir`，卸载后重新生成。
- 只有 RFCOMM 实际连接成功，才激活匿名车辆；未连接或无法确认身份的日志保持**未关联车辆**。日志入队时就固定车辆 ID，车 A 的积压日志不会被误计入车 B。
- 旧版 App 的无 `vehicle_id` 上报和旧归档记录照常支持；Gateway 对新日志增加可选的 `vehicle_id`（严格校验 UUIDv4），不改变原按安装 ID 的物理归档路径或七天保留期。
- `list_vehicles(device_id)` 仅返回最近七天该手机日志中出现的匿名车辆 ID 和最后日志时间；`get_device_logs(device_id, source, vehicle_id?)` 可以筛选该车辆，省略 `vehicle_id` 则查询手机所有车辆的日志。
- 系统日志绑定的是用户**确认上传时的当前车辆会话**，不证明系统崩溃的根因来自该车辆；连接结束或无法确认时则不附车辆 ID。
- 按顺序部署新版 Gateway → MCP → Android App。先验证旧版无车辆 ID 仍可上报，再验证两辆模拟车的独立过滤和重启后的日志保留。不要将公开 Kubernetes 占位模板直接部署到生产环境。
