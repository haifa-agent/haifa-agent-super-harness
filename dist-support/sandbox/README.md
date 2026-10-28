# Super Harness Linux Docker AIO 沙箱发行与配置

本目录包含 Super Harness 在 Linux 生产环境下使用 Docker AIO 沙箱的镜像锁定与策略元数据。

## 1. 镜像锁定清单 (`image-lock.json`)

发布包通过 `sandbox/image-lock.json` 固化经过完整回归与验证的官方沙箱镜像身份：

* **镜像全名**：`enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox:1.11.0@sha256:6328d7fd2f0ff0b4c147c3d05b3df1ce331f4a482eb6e550ecd64ed1fcf906e7`
* **目标平台**：`linux/amd64`
* **策略规范**：`docker-aio/v2`
* **生命周期模式**：`warm`（DeerFlow 热容器常驻会话模式，会话级复用同一活体容器，消除多轮冷启动）
* **空闲回收**：默认 10 分钟（`600000ms`）无交互由守护协程自动安全回收
* **安全隔离**：
  - 非特权模式、`no-new-privileges`，严格内存 (1GB)、CPU (2.0)、PID (256) 资源配额；
  - 容器网桥启用 `--internal` 与 `enable_icc=false`，严禁容器直连外网或容器间横向穿透；
  - 宿主与容器工作区安全双向挂载到 `/mnt/user-data/workspace`。

## 2. 诊断预检 (`doctor`)

在目标 Linux 服务器上，执行安装前可运行只读诊断检查 Docker 守护进程与沙箱环境：

```bash
# 人工可读报告
python3 scripts/install-service.py doctor

# 结构化 JSON 输出
python3 scripts/install-service.py doctor --json
```

预检项目包含：
1. **系统与内核**：Linux 操作系统与 CPU 架构；
2. **Java 运行环境**：Java 21+ 可用性；
3. **Node.js 运行环境**：若启用前端，检测 Node.js 22+；
4. **端口占用**：Gateway 与 Frontend 端口 loopback 监听与可用性；
5. **Docker CLI**：`docker` 命令存在性；
6. **Docker 守护进程**：Docker socket 通信与用户访问权限；
7. **本地镜像身份**：检查本地是否已存在锁定镜像且 SHA-256 Digest 严格一致；
8. **网桥隔离能力**：检查 Docker bridge 内部网络创建与隔离支持。

## 3. 镜像准备 (`prepare`)

### 联网环境（在线拉取并校验）
```bash
python3 scripts/install-service.py prepare
```
脚本自动拉取锁定镜像，通过 `docker inspect` 核对镜像 Digest 与架构，校验通过后标记就绪。

### 离线环境（从镜像归档载入）
若发布包为离线包（携带 `sandbox/images/` 归档）或用户手动提供了镜像归档文件：
```bash
python3 scripts/install-service.py prepare --image-tar /path/to/all-in-one-sandbox-1.11.0.tar
```
脚本将依次校验归档文件哈希、执行 `docker load` 导入镜像，并严格校验镜像身份与架构，不匹配时 fail-closed。

## 4. 启用容器沙箱服务

在安装时增加 `--execution-provider docker-aio` 参数：

```bash
sudo python3 scripts/install-service.py install --execution-provider docker-aio
```

该命令将在 `config/gateway.env` 中生成容器沙箱环境参数并启动服务：
```ini
HAIFA_SUPER_HARNESS_EXECUTION_PROVIDER=docker-aio
HAIFA_SUPER_HARNESS_DOCKER_IMAGE=enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox:1.11.0@sha256:6328d7fd2f0ff0b4c147c3d05b3df1ce331f4a482eb6e550ecd64ed1fcf906e7
HAIFA_SUPER_HARNESS_DOCKER_SOCKET=/var/run/docker.sock
HAIFA_SUPER_HARNESS_DOCKER_LIFECYCLE=warm
HAIFA_SUPER_HARNESS_DOCKER_IDLE_TIMEOUT_MS=600000
```
