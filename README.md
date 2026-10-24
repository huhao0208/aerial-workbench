# Aerial Workbench

基于 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) 的独立 fork，保留上游 Git 历史和版权声明。维护者的主题、TCL 原生亮度/低蓝光、SDR 屏保、购物键桥接及 GitHub 更新适配在此维护。

[上游原始说明](README.upstream.md) · [第三方声明](NOTICE.md) · [变更与验证边界](WORKBENCH.md) · [固件在线选配](https://huhao0208.github.io/tcl-firmware-builder/) · [正式 APK 下载与更新](https://github.com/huhao0208/aerial-workbench/releases/latest)

## 安装与更新

正式签名 APK、版本说明、SHA256SUMS 与许可文件统一放在[本仓库 Releases](https://github.com/huhao0208/aerial-workbench/releases)。`standard` 包可作为默认桌面；`guard` 包不声明 HOME，更新时保持原安装类型。已安装版通过桌面“软件更新”下载、校验并安装。

wb.16 起更新地址也指向本仓库。wb.15 及更旧版本从原 APK 发行仓库获取一次 wb.16 迁移更新，升级后自动使用新地址；旧仓库保留兼容入口，不再作为日常发行位置。现有 Root 配置、壁纸、布局和快捷键保留。

## 来源与协议

- 固定基线：Aerial `v1.41` / `db54bede313aa26ccd5779569c51436d9896360f`。`main` 保留 fork 时上游状态，`workbench` 维护本分支；新上游更新经评审后合并。
- Aerial 与本项目新增代码采用 MIT，完整保留原作者 **pengchujin** 的版权及 [LICENSE](LICENSE)。这是独立发行，与上游及电视厂商无隶属/背书关系。
- 字体、图标、城市数据、Java 兼容库和其他依赖分别保留 OFL、CC0、CC BY、Apache、BSD、GPL + Classpath Exception 等协议，见 [NOTICE.md](NOTICE.md) 和 [69 项实际运行时依赖](licenses/runtime-dependencies.json)。
- 每个新 APK 内包含完整 `assets/licenses/THIRD-PARTY-NOTICES.txt`。手机配置服务启动后，在带令牌的配置页地址后追加 `/licenses` 可查看纯文本声明。这个入口沿用配置页的访问令牌校验。
- 本仓库不包含原厂 TCL 固件、Magisk 二进制、签名私钥或个人电视配置。代码 MIT 许可不授予第三方媒体、商标、原厂固件或独立 GPL 组件的额外权利。

## 编译与验证

JDK 17、Android SDK 36 / Build Tools 36.0.0。常规源码构建不需要维护者密钥：

```sh
./gradlew --no-daemon --no-configuration-cache -I tools/license-inputs.gradle -Pworkbench :app:exportLicenseInputs
python3 tools/license-bundle.py --check
./gradlew --no-daemon -Pworkbench -PunsignedRelease :app:testStandardDebugUnitTest :app:assembleStandardRelease :app:assembleGuardRelease :keybridge:assembleRelease
python3 tools/check-apk-notices.py
```

GitHub Actions 执行同样检查，保存三份**未签名候选 APK**。这些文件供源码验证/自有签名使用，不能覆盖维护者已签名的电视版本，也不会自动发布到稳定更新通道。

维护者签名发行继续由私有签名流程完成。自行签名时在本地忽略目录 `local/` 放置自己的 `signing.properties` 和密钥，省略 `-PunsignedRelease`；不要上传这些文件。使用不同证书的 APK 不能作为当前已安装私人桌面的原位升级。

新依赖或版本变化时，重新导出运行时输入并执行 `python3 tools/license-bundle.py`，人工复核许可、来源及新增声明后提交；CI 对依赖或许可证包漂移停止构建。工具只核对已解析元数据和归档声明，不替代每个新增依赖的协议审查。
