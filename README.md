# HTTP 共享 · HttpShare

通过浏览器或网络盘（WebDAV）访问手机存储的 Android 应用（同时是 LSPosed 模块）。支持局域网和外网（Cloudflare 隧道，无需公网 IP），可用 root 共享 `/data`、`/system` 等系统目录，支持访问密码和 HTTPS。

下载：[Releases](https://github.com/jiemo9527/HttpShare/releases)（仅 arm64-v8a，Android 10+）

## 功能

- **共享目录（只有一个）**：首页二选一：「内部存储」（只能选 `/sdcard` 下的目录，需要「所有文件访问」权限，有 root 时自动授权）或「系统位置（ROOT）」（通过 `su` 读写，可选 `/data`、`/system` 等任意目录）。两种类型各自记住上次选的路径，切换即时生效。从旧版本升级时取原来第一个共享目录。
- **网页端**：电脑、手机浏览器都能使用，界面随屏幕宽度调整，深色模式跟随系统。支持排序、筛选、断点续传/拖动进度（HTTP Range）、在线预览图片和音视频。写权限分两项，在设置里分别开关：「上传 / 新建文件夹」和「改名 / 删除（含覆盖同名文件）」，两项都关时只读。
- **加密**
  - 访问密码：可一键随机生成 9–12 位（大小写字母 + 数字，去掉 0/O、1/l/I 等易混字符），保存后自动复制，可在设置里查看/复制。校验用加盐 SHA-256 哈希；明文用 Android Keystore（AES-GCM，密钥不可导出）加密保存，只能在本机 App 内解密。网页会话使用 HttpOnly + SameSite=Strict Cookie；同一 IP 连续输错 3 次封锁 2 小时（同一个错误密码被客户端自动重试只算一次）；封锁只保存在内存里，重启服务即全部解除。curl/wget 可以用 HTTP Basic，例如 `curl -u x:密码 URL`。
  - HTTPS（默认开启）：首次使用时在手机上生成「本地根证书」（每台手机不同，RSA-2048，10 年），再用它按当前局域网 IP 签发服务器证书（IP 变化后重启服务自动重签）。根证书带关键的名称约束，只能为内网 IP（10/8、172.16/12、192.168/16、100.64/10、127/8、169.254/16、fc00::/7、fe80::/10）和 localhost 签发——电脑信任它之后，即使私钥泄露也无法冒充任何公网网站（已实测 Windows 拒绝其签发的公网证书：`HasNotPermittedNameConstraint`）。浏览器首次会提示不受信任：核对 App 里显示的指纹，或下载 `地址/ca.cer` 装到“受信任的根证书颁发机构”后浏览器与 Windows 网络盘都不再报警。
  - 其他防护：写操作要求请求带自定义请求头（防 CSRF）；下载的文件以 `CSP: sandbox` 返回，防止共享目录里的 HTML/SVG 劫持会话；路径拒绝 `..`，普通共享不能通过符号链接越出共享目录。
- **网络方式**：在首页启动按钮上方切换「仅局域网 / 自动 / CF 隧道」，服务运行中切换会自动重启服务。
  - 自动：网卡上有公网 IPv4 且与出口 IP 一致时直连，否则自动开 Cloudflare 临时隧道（`xxx.trycloudflare.com`，每次启动变化）。
  - CF 隧道：不检测公网 IP，总是走 Cloudflare 隧道。
  - 隧道无需公网 IP，蜂窝流量下可用；外网访问强制要求先设置访问密码，登录失败锁定按真实客户端 IP（`CF-Connecting-IP`）计。
  - 实现：内置 Termux 构建的 Android 版 cloudflared（arm64，`lib/arm64-v8a/libcloudflared.so`）。Go 程序在 Android 上无法使用系统 DNS、蜂窝下直连会报 network unreachable，所以 DNS（系统 DNS 失败时回落阿里/腾讯 DoH）、申请隧道、到 Cloudflare 边缘的 TCP 都由 App 的 Java 层完成，cloudflared 只连本地中继。
  - 已知：若手机装了 box_for_root/mihomo 等透明代理且 Cloudflare 域名被规则拦截，隧道会失败，需把本应用加入代理绕过名单。
- **文件夹打包下载**：网页上文件夹行的「打包下载」或工具栏「打包下载此目录」。服务端边读边压缩（流式 ZIP，不生成临时文件，下载立即开始，走隧道也可以）；图片/视频/压缩包等只存储不压缩，其余快速压缩；超过 4 GB 或 65535 个文件自动使用 ZIP64。普通共享不跟随符号链接，ROOT 共享不进入链接目录（防循环），跳过设备/管道文件。大小事先未知，浏览器不显示总进度。
- **WebDAV（`/dav/`）**：把手机挂载为电脑/手机的网络盘，直接用本地软件打开、编辑、保存，改动实时写回手机。用户名任意、密码为访问密码；读写权限与网页端相同（上传/新建需「上传」，删除/移动/覆盖需「改名/删除」）。只认 HTTP Basic，不认网页 Cookie，所以其他网站无法借用登录态。已测试：Windows 资源管理器（走隧道 HTTPS 直接映射）、rclone、curl；支持 chunked 上传、`Expect: 100-continue`、LOCK/PROPPATCH（Windows 需要）。
  - **Windows 一键挂载**：网页上点「挂载为网络盘」→「下载 Windows 一键挂载脚本」，双击运行、输入访问密码（明文输入，可直接粘贴），自动映射为空闲盘符（Z 往前找）并打开。脚本按当前访问地址生成（局域网 HTTP / 隧道都可以），不包含密码；挂载前先验证连接和密码，密码错误、被封锁等都会给出明确提示。局域网 HTTPS 地址：脚本内置根证书指纹，首次运行下载 `/ca.cer` 核对指纹一致后装入“本地计算机\受信任的根证书颁发机构”（与修改注册表共用管理员确认），之后局域网 HTTPS 可直接挂载。首次运行如需修改系统设置会弹一次管理员确认，然后重启 WebClient 服务。已映射过同一地址时直接打开，不重复占用盘符。局域网地址设为开机自动重连；隧道地址每次启动都变，不做持久映射。
  - 脚本修改的两项注册表设置（`HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters`）：`BasicAuthLevel=2` —— Windows 默认只在 HTTPS 下发送用户名密码（值 1），改为 2 才允许在局域网 HTTP 上登录（密码以 Base64 明文传输，同一 Wi-Fi 下可被抓包，只在可信网络使用）；`FileSizeLimitInBytes=4294967295` —— 单文件上限从默认 50 MB 改为 4 GB。
  - 已知限制：手机上修改文件后，Windows 映射盘里约 60 秒后才读到新内容（实测：局域网 / 隧道都一样，目录列表和文件大小会立即更新）。这是 Windows WebDAV 客户端（MRxDAV）按时间缓存文件内容，期间根本不向服务器请求；服务器已返回 `ETag` + `Cache-Control: no-cache` 也无效，把 `FileInformationCacheLifeTimeInSec` 设为 0 反而会让内容一直不刷新，所以不做修改。需要立刻看到手机上的改动时，用网页端或 RaiDrive 等第三方客户端。反方向（电脑上修改保存）会立即写回手机。
  - 其他系统手动添加地址 `http(s)://地址/dav/`，用户名任意，密码为访问密码。
- **同步查阅 `/showme`**：在 App「浏览」页打开某个共享目录后，网页打开 `地址/showme`（需登录）会实时显示同一目录，只读，可预览、下载。离开浏览页后网页显示等待状态。采用长轮询，Cloudflare 隧道下同样可用（实测该隧道会缓冲 SSE 流，所以没用 SSE）。可在设置中关闭。
- **路径选择器**：首页「选择目录…」逐级选择；内部存储模式不能退到 `/sdcard` 之上，系统位置模式通过 su 列目录。
- **日志**：保存在本机（`files/log.txt`），App 被杀、服务或手机重启后都保留；最多保留最新 3000 行，超出只丢最早的，除非手动点「清空」不会清除。
- **桌面图标**：默认显示，可在设置里手动隐藏（不再自动隐藏）。从 0.2 升级时会恢复一次被自动隐藏的图标。
- **关于**：设置页底部的链接跳转到本项目 GitHub。

## 使用

1. 安装 APK，在 LSPosed 中启用模块。作用域勾选推荐项：「系统框架」和本应用。
2. 重启手机（「系统框架」作用域需要重启）。
3. 打开应用，在首页选择共享目录（内部存储 / 系统位置）和网络方式，按需在设置中配置访问密码、HTTPS、两项写权限和 WebDAV，然后点「启动」。
4. 在同一局域网的设备上打开首页显示的地址。

作用域说明：勾选本应用，界面才能显示模块是否生效。勾选系统框架后，手动隐藏图标时会拦截 Android 10+ 生成的「应用详情」替身图标。不启用模块时，共享功能照常可用。

## 本地构建

APK 仅含 arm64-v8a 的 cloudflared（约 29 MB，压缩后 APK 约 11 MB）；其他架构外网只能用公网 IPv4 直连。

需要 JDK 17、Android SDK（platform 35、build-tools 35.0.0）和 Gradle 8.7。在工程根目录的 `local.properties` 中填写 `sdk.dir`，然后运行：

```
gradle assembleDebug
python -m unittest discover -s tests
```

生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

## 许可证

MIT，见 [LICENSE](LICENSE)。内置的 cloudflared 为 Apache-2.0，见 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)。

## 注意

- 开启 ROOT 且允许改名/删除时，网页端可以删除系统文件，请务必设置访问密码，并只在可信网络中使用。
- 服务运行期间会持有 WakeLock 和 WifiLock，保证息屏后仍可访问，因此会增加耗电；不用时请停止服务。
