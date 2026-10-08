# 在 Android 手机上直接编译本项目

不用电脑，用 Android 上的 Linux 容器（proot Ubuntu / Termux + proot-distro 都行）把 APK 编译出来。本项目就是这么开发出来的。

## 为什么需要额外折腾

官方 Android SDK 的 `build-tools` 里 **`aapt2` 只有 x86_64 的 Linux 版本**，而手机是 aarch64。直接跑会报 `cannot execute binary file`。

解决办法：装 `qemu-user-static` 转译，再给 aapt2 提供一个包装脚本。

## 步骤

假设你已经有一个 Ubuntu rootfs（proot/termux 都行，下文用 rootfs 表示内部路径）。

### 1. 基础工具

```bash
apt-get update
apt-get install -y openjdk-21-jdk-headless unzip zip curl qemu-user-static
```

> 如果 apt 报 `Could not resolve 'ports.ubuntu.com'`，是 IPv6 没有路由导致的，加上：
> `echo 'Acquire::ForceIPv4 "true";' > /etc/apt/apt.conf.d/99force-ipv4`

### 2. Android SDK

```bash
cd /opt && curl -LO https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
# 注意：cmdline-tools 23.0 起的新版是个调用 `android` 二进制的壳，缺文件会报 not found，
# 所以这里固定用旧版 13114758（19.0）。
mkdir -p /opt/android-sdk/cmdline-tools
unzip -q commandlinetools-linux-13114758_latest.zip -d /tmp/clt
mv /tmp/clt/cmdline-tools /opt/android-sdk/cmdline-tools/latest

export ANDROID_HOME=/opt/android-sdk
SM=/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager
yes | $SM --sdk_root=$ANDROID_HOME --licenses
$SM --sdk_root=$ANDROID_HOME "platforms;android-37.0" "build-tools;36.1.0"
```

### 3. 给 x86_64 的 aapt2 准备运行库

x86_64 的 ELF 需要 x86_64 的 glibc，rootfs 里没有，手动取（**不要**用 `dpkg --add-architecture amd64`，ports 源不提供 amd64 包）：

```bash
mkdir -p /opt/x86root /workspace/debs && cd /workspace/debs
# 需要 amd64 的索引：加一个指向 archive.ubuntu.com 的源（注意把原有源限制为 arm64）
cat > /etc/apt/sources.list.d/amd64.list <<'EOF'
deb [arch=amd64] http://archive.ubuntu.com/ubuntu noble main universe
deb [arch=amd64] http://archive.ubuntu.com/ubuntu noble-updates main universe
deb [arch=amd64] http://security.ubuntu.com/ubuntu noble-security main universe
EOF
apt-get update
apt-get download libc6:amd64 libstdc++6:amd64 zlib1g:amd64 libgcc-s1:amd64
for d in *.deb; do dpkg-deb -x "$d" /opt/x86root; done

# qemu 会按 ELF 里写死的 /lib64/ld-linux-x86-64.so.2 找 loader，补上链接
cd /opt/x86root
ln -sfn usr/lib lib
mkdir -p lib64 && ln -sf ../usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 lib64/ld-linux-x86-64.so.2

# 测试
/usr/bin/qemu-x86_64-static -L /opt/x86root /opt/android-sdk/build-tools/36.1.0/aapt2 version
# 期望输出：Android Asset Packaging Tool (aapt) 2.20-...
```

### 4. 让 AGP 用这个 aapt2

```bash
mkdir -p /opt/android-sdk/tools
printf '#!/bin/sh\nexec /usr/bin/qemu-x86_64-static -L /opt/x86root /opt/android-sdk/build-tools/36.1.0/aapt2 "$@"\n' \
  > /opt/android-sdk/tools/aapt2
chmod +x /opt/android-sdk/tools/aapt2
```

在 **`$GRADLE_USER_HOME/gradle.properties`**（不是仓库里的那个，避免把设备专用路径提交进仓库）里加：

```properties
org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-arm64
android.aapt2FromMavenOverride=/opt/android-sdk/tools/aapt2
```

### 5. 编译

```bash
cd VoltMeter
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk
export GRADLE_USER_HOME=/path/to/gradle-home      # 建议放在数据分区，别放内存盘
./gradlew :app:assembleDebug
```

内存不足时把 `gradle.properties` 里的 `org.gradle.jvmargs` 调小（手机建议 `-Xmx1536m`）。

## 实测环境

- 一加 13（Android 16 / arm64），proot Ubuntu 24.04
- 首次构建约 4 分钟（含下载依赖），之后增量构建 30 秒左右
- aapt2 走 qemu 转译的开销很小，资源处理是秒级

## 小提示

- 手机编译建议关掉 Gradle daemon 之外的一切：`--no-daemon` 更省内存但更慢
- `aapt2` 走的是 `android.aapt2FromMavenOverride`，这是 AGP 的实验性开关，会有 WARNING，属正常
