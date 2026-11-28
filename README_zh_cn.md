# 我的世界资源包反混淆工具

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/lang-English-blue.svg" alt="English"></a>
  <a href="README_zh_cn.md"><img src="https://img.shields.io/badge/lang-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-orange.svg" alt="简体中文"></a>
</p>

<p align="center">
  <a href="version.json"><img src="https://img.shields.io/badge/version-1.2.1-blue.svg" alt="版本 1.2.1"></a>
  <img src="https://img.shields.io/badge/license-Unlicense-lightgrey.svg" alt="许可证：Unlicense">
  <img src="https://img.shields.io/badge/JDK-8+-orange.svg" alt="JDK 8+">
  <img src="https://img.shields.io/badge/build-passing-brightgreen.svg" alt="构建通过">
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20Linux%20%7C%20macOS-informational.svg" alt="跨平台">
  <img src="https://img.shields.io/badge/release-JAR%20%2B%20docs-blueviolet.svg" alt="发布：JAR + 文档">
</p>

<p align="center">
  <img src="https://img.shields.io/github/last-commit/848488489-ff/mcpack-deobfuscator.svg" alt="最近提交">
  <img src="https://img.shields.io/github/languages/top/848488489-ff/mcpack-deobfuscator.svg" alt="主要语言">
  <img src="https://img.shields.io/github/repo-size/848488489-ff/mcpack-deobfuscator.svg" alt="仓库大小">
  <img src="https://img.shields.io/github/release/848488489-ff/mcpack-deobfuscator.svg" alt="最新发布">
  <img src="https://img.shields.io/github/forks/848488489-ff/mcpack-deobfuscator.svg" alt="Forks">
  <img src="https://img.shields.io/github/stars/848488489-ff/mcpack-deobfuscator.svg" alt="Stars">
</p>

我的世界资源包反混淆工具（mcpack-deobfuscator）是一个 **Java** 命令行与
图形界面工具，用于**反混淆**被 **PackSquash** 等工具混淆或保护的
**Minecraft 资源包（mcpack）**。这类资源包无法被严格校验的解压软件
（7-Zip、WinRAR、Windows 资源管理器、Python 的 `zipfile`）打开，即使游戏
本身能正常读取。本工具能检测混淆、重建干净压缩包、并还原被混淆的文件
内容，让提取出的文件真正能打开。

它还提供完整性**检查**功能，以及可调参数的**混淆**模式（反混淆的逆操作），
用于主动制造这类资源包。

## 为什么这类资源包打不开

被保护/混淆的资源包其实并没有真正加密，而是被"定向破坏"：**中央目录
（central directory）** 与 **本地文件头（local file header）** 的元数据互相
矛盾。常见的破坏方式包括：

- 本地文件头里的文件名被改写（转成大写，甚至替换成别的名字），与中央目录
  不一致。
- 压缩 / 解压大小、CRC-32 校验值被修改。
- 真实文件被标记为目录（伪目录条目）。
- 在文件偏移 0 处伪造 EOCD 结束签名。

严格校验的解压软件 —— 7-Zip、WinRAR、Windows 资源管理器、Python 的
`zipfile` —— 会比较两份元数据，发现不一致后拒绝解压（报"文件名字不一致"、
"CRC-32 校验失败"等）。而 JVM 的 `java.util.zip.ZipFile` 不做这种一致性
校验：它通过中央目录定位条目，直接读取原始字节，所以资源包在 Minecraft
里依然能正常工作。

某些混淆器还会进一步破坏**文件内容本身**，让严格校验的解压软件在解压出
数据后仍然报错：

- **PNG 混淆**：把一个随机的密钥异或进每个数据块的 CRC-32，以及 IDAT 的
  zlib 流末尾的 Adler-32。数据块本身未变，因此无需知道密钥即可还原：
  解压 IDAT 流、重算 Adler-32、重算每个数据块的 CRC-32。
- **Ogg 混淆**：首页流的序列号被改写（+1）、每个 granule 位置被映射为
  `i64::MIN + granule`、Vorbis 识别头的码率字段被强制置 -1。反混淆会还原
  序列号、反映射 granule，并重算 Ogg 页的 CRC-32。
- **JSON 压缩**：空白被删除。反混淆会重新格式化（Prettify）文件，使其可读、
  可编辑。

## 功能

- **检查**：全面检测——条目数、本地头与中央目录元数据差异（文件名、大小、
  CRC）、伪目录条目、伪造 EOCD 签名、数据 CRC-32 校验、无法读取的条目——
  并给出 完好 / 疑似混淆保护 / 已损坏 的结论。它还会扫描资源包内的
  PNG / Ogg / JSON 文件，检测 PackSquash 内容混淆特征，并给出专门的
  **PACKSQUASH** 结论：PNG 数据块 CRC-32 损坏、Ogg 头部序列号被改写 /
  granule 被映射 / 码率被置 -1，以及混淆特有的 CRC 指纹（本地头与中央目录
  的 CRC 字段互相一致，但都与真实数据不符——因为同一个密钥被异或进了两个
  字段）。
- **反混淆**：重建干净压缩包。用 `ZipFile`（随机读取）读出数据，
  再通过 `ZipOutputStream` 重新写出，让它重新生成一套完全一致的元数据。
  输出的压缩包任何解压软件都能打开。它还会对包内的 `.png`、`.ogg`/`.oga`、
  `.json`/`.mcmeta`/`.jsonc` 与 `.nbt` 条目按上述方式还原 / 校验，使提取
  出的文件能正常打开。
- **混淆**：反混淆的逆操作。可调参数：改名方式（none / upper / digit /
  random）、影响条目比例、伪装目录、白名单（不触碰的条目）、伪 EOCD 前缀、
  中央目录大小扰动、CRC-32 扰动、随机种子，以及**内容混淆**——对包内的
  PNG / Ogg / JSON 文件内容施加混淆（PNG 块 CRC-32 与 IDAT Adler-32 异或
  一个密钥、Ogg 序列号 / granule / 码率字段被改写、JSON 被压缩成单行）。
  内容混淆可**按类型单独开启**：PNG / Ogg / JSON 分别选择，例如只混淆 Ogg
  （`--content-ogg`），让图片仍正常而声音损坏。会去除 data descriptor，
  让本地头携带真实大小——这正是让严格解压软件拒绝、而 JVM（信任中央
  目录）仍能读取的关键。

## 使用方法

图形界面（三个标签页：检查 / 反混淆 / 混淆；把 ZIP 拖进标签页，或点击
选择）：

    java -jar mcpack-deobfuscator.jar

命令行：

    java -jar mcpack-deobfuscator.jar -check <输入.zip>
    java -jar mcpack-deobfuscator.jar -deobfuscate <输入.zip> <输出.zip>
    java -jar mcpack-deobfuscator.jar -obfuscate <输入.zip> <输出.zip>
        [--name <none|upper|digit|random>] [--percent <0-100>]
        [--fake-dir <0-100>] [--eocd] [--crc] [--no-sizes]
        [--content|--content-png|--content-ogg|--content-json]
        [--seed <n>] [--whitelist <a,b,c>]

`--seed` 接受十进制或 `0x` 前缀十六进制，覆盖完整的 64 位无符号范围
（0 .. 18446744073709551615），以对应位模式存储。相同种子与相同参数
的混淆运行会得到相同的破坏方案。

### 白名单（不触碰的条目）

白名单里列出的条目在混淆时**完全跳过**：不改名、不扰动大小、不伪装目录、
不混淆内容。

- **格式**：每个条目是压缩包内的**完整条目名**或**路径前缀**。GUI 用
  列表（输入框 + 添加/删除按钮）逐条加入；命令行用逗号分隔，
  `--whitelist <a,b,c>`。
- **匹配规则**：精确匹配条目名，或作为路径前缀匹配其下所有条目。
  例如 `assets/minecraft/textures/item/diamond_sword.png` 保护这一个文件
  （连同它的 `.mcmeta`），`assets/minecraft/textures` 保护整个目录。
- **缺失校验**：白名单条目如果在压缩包中**不存在**（既无精确匹配也无
  前缀匹配），混淆会**报错并中止**，列出缺失条目——避免你以为某文件已
  被保护、实际却因拼写错误而没保护。
- **示例**（保护一张图片，其余全部混淆 PNG）：

    java -jar mcpack-deobfuscator.jar -obfuscate pack.zip out.zip \
        --content-png --whitelist assets/minecraft/textures/item/diamond_sword.png

界面语言跟随系统（中文 / 英文），可在窗口中切换；命令行输出为英文。

### 检测与反混淆混淆的包

被 PackSquash 混淆的资源包会被自动识别，并给出专门的 `check.packsquash`
结论以及分项统计：

    $ java -jar mcpack-deobfuscator.jar -check "pack.zip"
    verdict: check.packsquash
    entries=909 nameMismatch=908 sizeMismatch=908 crcFieldMismatch=0 fakeDirs=2 forgedEocd=true badCrc=906 unreadable=0 jvmReadable=true
    obfuscated=true png=675 ogg=6 json=124 crcFingerprint=906
      675 个 PNG 的块 CRC-32 损坏（如 assets/minecraft/textures/block/stone.png）
      6 个 OGG 头部被混淆
      906 个条目符合 PackSquash CRC 指纹（本地头与中央目录 CRC 一致但与数据不符）
      124 个 JSON 疑似被压缩（minify）

反混淆时既重建 ZIP 容器，也还原被混淆的文件内容，使提取出的文件真正可用：

    $ java -jar mcpack-deobfuscator.jar -deobfuscate "pack.zip" out.zip
    ok=909 skipped=0 fakeDirs=2 bytes=6272438
    content: pngFixed=676 oggFixed=6 jsonPrettified=125 nbtChecked=0 nbtBad=0

反混淆后再次检查会得到 `check.ok`，所有混淆计数归零。

## 构建

需要 JDK 8 或更高版本。

- Windows：`build.bat`
- Linux / macOS：`./build.sh`

## 许可证

[Unlicense](LICENSE) —— 本软件已贡献至公有领域。
