# HTTP 共享 · HttpShare

在局域网内通过浏览器访问手机存储的 LSPosed 模块应用。支持用 root 共享 `/data`、`/system` 等系统目录，可设置访问密码并启用 HTTPS 加密传输。

## 功能

- **目录共享**：可添加多个共享目录，每个目录可单独开启 ROOT。ROOT 目录通过 `su` 读写，可访问 `/data`、`/system`、`/vendor` 等；普通目录需要「所有文件访问」权限，授权入口在首页（有 root 时会自动授权）。
- **网页端**：电脑、手机浏览器都能使用，界面随屏幕宽度调整，深色模式跟随系统。支持排序、筛选、断点续传/拖动进度（HTTP Range）、在线预览图片和音视频。写权限分两项，在设置里分别开关：「上传 / 新建文件夹」和「改名 / 删除（含覆盖同名文件）」，两项都关时只读。
- **加密**
  - 访问密码：只保存加盐 SHA-256 哈希。网页会话使用 HttpOnly + SameSite=Strict Cookie；同一 IP 连续输错 3 次封锁 2 小时；封锁只保存在内存里，重启服务即全部解除。curl/wget 可以用 HTTP Basic，例如 `curl -u x:密码 URL`。
  - HTTPS：首次使用时生成 RSA-2048 自签名证书（有效期 10 年），可在应用内查看 SHA-256 指纹，用来核对浏览器显示的证书。
  - 其他防护：写操作要求请求带自定义请求头（防 CSRF）；下载的文件以 `CSP: sandbox` 返回，防止共享目录里的 HTML/SVG 劫持会话；路径拒绝 `..`，普通共享不能通过符号链接越出共享目录。
- **网络方式**：在首页启动按钮上方切换「仅局域网 / 自动 / CF 隧道」，服务运行中切换会自动重启服务。
  - 自动：网卡上有公网 IPv4 且与出口 IP 一致时直连，否则自动开 Cloudflare 临时隧道（`xxx.trycloudflare.com`，每次启动变化）。
  - CF 隧道：不检测公网 IP，总是走 Cloudflare 隧道。
  - 隧道无需公网 IP，蜂窝流量下可用；外网访问强制要求先设置访问密码，登录失败锁定按真实客户端 IP（`CF-Connecting-IP`）计。
  - 实现：内置 Termux 构建的 Android 版 cloudflared（arm64，`lib/arm64-v8a/libcloudflared.so`）。Go 程序在 Android 上无法使用系统 DNS、蜂窝下直连会报 network unreachable，所以 DNS（系统 DNS 失败时回落阿里/腾讯 DoH）、申请隧道、到 Cloudflare 边缘的 TCP 都由 App 的 Java 层完成，cloudflared 只连本地中继。
  - 已知：若手机装了 box_for_root/mihomo 等透明代理且 Cloudflare 域名被规则拦截，隧道会失败，需把本应用加入代理绕过名单。
- **同步查阅 `/showme`**：在 App「浏览」页打开某个共享目录后，网页打开 `地址/showme`（需登录）会实时显示同一目录，只读，可预览、下载。离开浏览页后网页显示等待状态。采用长轮询，Cloudflare 隧道下同样可用（实测该隧道会缓冲 SSE 流，所以没用 SSE）。可在设置中关闭。
- **路径选择器**：添加共享时点「浏览…」逐级选择目录；开启 ROOT 时通过 su 列目录，可进入 `/data` 等系统目录。
- **日志**：内存中保留最近 3000 行。
- **桌面图标**：默认显示，可在设置里手动隐藏（不再自动隐藏）。从 0.2 升级时会恢复一次被自动隐藏的图标。
- **关于**：设置页底部的链接跳转到本项目 GitHub。

## 使用

1. 安装 APK，在 LSPosed 中启用模块。作用域勾选推荐项：「系统框架」和本应用。
2. 重启手机（「系统框架」作用域需要重启）。
3. 打开应用，添加共享目录，按需在设置中配置访问密码、HTTPS 和两项写权限，在首页选择网络方式后点「启动」。
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

## 注意

- 开启 ROOT 且允许改名/删除时，网页端可以删除系统文件，请务必设置访问密码，并只在可信网络中使用。
- 服务运行期间会持有 WakeLock 和 WifiLock，保证息屏后仍可访问，因此会增加耗电；不用时请停止服务。
