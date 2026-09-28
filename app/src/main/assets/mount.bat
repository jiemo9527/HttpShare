@echo off
chcp 65001 >nul
setlocal EnableExtensions DisableDelayedExpansion
rem HttpShare - Windows WebDAV 一键挂载（由手机上的 HTTP 共享按访问地址生成）
set "DAV_URL={URL}"
set "NEED_BASIC={NEED_BASIC}"
set "UNC={UNC}"
set "SYS=%SystemRoot%\System32"
set "KEY=HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters"
if /i "%~1"=="fix" goto fix
title HttpShare WebDAV 挂载
echo.
echo  HttpShare - 把手机挂载为网络盘
echo  地址：%DAV_URL%
echo.
"%SYS%\sc.exe" query WebClient >nul 2>&1
if errorlevel 1 goto noclient
call :check
if "%NEED_FIX%"=="0" goto map
echo  需要调整 Windows 的 WebDAV 设置（允许 HTTP 登录、单文件上限改为 4 GB），将弹出一次管理员权限确认……
set "SELF=%~f0"
"%SYS%\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -Command "try { Start-Process -FilePath $env:SELF -ArgumentList 'fix' -Verb RunAs -Wait -WindowStyle Hidden } catch { exit 1 }"
call :check
if "%BASIC_OK%"=="0" goto basicfail
if "%NEED_FIX%"=="1" echo  提示：设置未全部生效，大于 50 MB 的文件可能无法通过资源管理器传输。
:map
rem WebClient 为手动启动，映射前确保已运行（普通用户可触发启动）
"%SYS%\sc.exe" start WebClient >nul 2>&1
rem 已映射过同一地址：直接打开，不重复占用盘符
set "OLD="
for /f "tokens=1-3" %%a in ('%SYS%\net.exe use 2^>nul ^| %SYS%\findstr.exe /i /l /c:"%UNC%"') do call :old %%a %%b %%c
if defined OLD (
  echo  已挂载为 %OLD% 盘，正在打开……
  start "" "%SystemRoot%\explorer.exe" %OLD%\
  echo.
  pause
  exit /b 0
)
set "DRV="
for %%d in (Z Y X W V U T S R Q P O N M L K J I H) do if not defined DRV call :free %%d
if not defined DRV goto nodrive
echo.
echo  即将映射为 %DRV%: 盘。请输入 App 设置里的访问密码（输入时不显示），按回车确认。
echo  注意：连续输错 3 次会被封锁 2 小时。
echo.
"%SYS%\net.exe" use %DRV%: "%DAV_URL%" * /user:HttpShare /persistent:{PERSIST}
if errorlevel 1 goto mapfail
echo.
echo  已挂载为 %DRV%: 盘，正在打开……
start "" "%SystemRoot%\explorer.exe" %DRV%:\
{NOTE}echo  断开：在“此电脑”里右键该盘符选择“断开连接”。
echo.
pause
exit /b 0

:old
if defined OLD exit /b 0
for %%x in (%*) do if "%%~dx"=="%%x" if exist %%x\ set "OLD=%%x"
exit /b 0

:free
if exist %1:\ exit /b 0
"%SYS%\net.exe" use %1: >nul 2>&1
if not errorlevel 1 exit /b 0
set "DRV=%1"
exit /b 0

:check
set "NEED_FIX=0"
set "BASIC_OK=1"
set "BAL="
set "FSL="
rem SystemRoot 与注册表路径都不含空格，这里不能加引号（引号内 ^ 不再转义）
for /f "tokens=3" %%a in ('%SYS%\reg.exe query %KEY% /v BasicAuthLevel 2^>nul ^| %SYS%\findstr.exe /c:REG_DWORD') do set "BAL=%%a"
for /f "tokens=3" %%a in ('%SYS%\reg.exe query %KEY% /v FileSizeLimitInBytes 2^>nul ^| %SYS%\findstr.exe /c:REG_DWORD') do set "FSL=%%a"
if "%NEED_BASIC%"=="1" if /i not "%BAL%"=="0x2" set "NEED_FIX=1"
if "%NEED_BASIC%"=="1" if /i not "%BAL%"=="0x2" set "BASIC_OK=0"
if /i not "%FSL%"=="0xffffffff" set "NEED_FIX=1"
exit /b 0

:fix
"%SYS%\reg.exe" add "%KEY%" /v FileSizeLimitInBytes /t REG_DWORD /d 4294967295 /f >nul
if "%NEED_BASIC%"=="1" "%SYS%\reg.exe" add "%KEY%" /v BasicAuthLevel /t REG_DWORD /d 2 /f >nul
"%SYS%\net.exe" stop WebClient >nul 2>&1
"%SYS%\net.exe" start WebClient >nul 2>&1
exit /b 0

:noclient
echo  本机没有 WebClient（WebDAV 客户端）服务。Windows 服务器版需在“服务器管理器”中添加“WebDAV 重定向程序”功能，或改用 RaiDrive 等工具。
pause
exit /b 1

:basicfail
echo  未能修改设置（可能在管理员确认时点了“否”）。局域网 HTTP 地址必须允许 HTTP 登录才能挂载，请重新运行本脚本并点“是”。
pause
exit /b 1

:nodrive
echo  没有空闲的盘符（H 到 Z 都已占用）。
pause
exit /b 1

:mapfail
echo.
echo  挂载失败。常见原因：密码错误；手机上的服务已停止或地址已变化（外网隧道地址每次启动都会变）；在 App 设置中关闭了 WebDAV。
pause
exit /b 1
