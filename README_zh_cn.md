# ZipRepairTool

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/lang-English-blue.svg" alt="English"></a>
  <a href="README_zh_cn.md"><img src="https://img.shields.io/badge/lang-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-orange.svg" alt="简体中文"></a>
</p>

<p align="center">
  <a href="version.json"><img src="https://img.shields.io/badge/version-1.0-blue.svg" alt="版本 1.0"></a>
  <img src="https://img.shields.io/badge/license-Unlicense-lightgrey.svg" alt="许可证：Unlicense">
  <img src="https://img.shields.io/badge/JDK-8+-orange.svg" alt="JDK 8+">
  <img src="https://img.shields.io/badge/build-passing-brightgreen.svg" alt="构建通过">
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20Linux%20%7C%20macOS-informational.svg" alt="跨平台">
  <img src="https://img.shields.io/badge/release-JAR%20%2B%20docs-blueviolet.svg" alt="发布：JAR + 文档">
</p>

<p align="center">
  <img src="https://img.shields.io/github/last-commit/848488489-ff/ZipRepairTool.svg" alt="最近提交">
  <img src="https://img.shields.io/github/languages/top/848488489-ff/ZipRepairTool.svg" alt="主要语言">
  <img src="https://img.shields.io/github/repo-size/848488489-ff/ZipRepairTool.svg" alt="仓库大小">
  <img src="https://img.shields.io/github/release/848488489-ff/ZipRepairTool.svg" alt="最新发布">
  <img src="https://img.shields.io/github/forks/848488489-ff/ZipRepairTool.svg" alt="Forks">
  <img src="https://img.shields.io/github/stars/848488489-ff/ZipRepairTool.svg" alt="Stars">
</p>

ZipRepairTool 是一个 **Java** 命令行与图形界面工具，用于修复在传输过程中
损坏的 ZIP 压缩包。当 ZIP 的元数据变得不一致时——例如传输被中断或损坏——
多数解压软件会拒绝打开它。本工具会重建压缩包，使其能再次打开。

它还提供**完整性检查**功能：检查压缩包、报告哪里不一致，以及修复模式：
从存储的数据重建一个干净的压缩包。

## 为什么压缩包会损坏

ZIP 压缩包保存两份元数据：**中央目录（central directory）** 与**本地文件头
（local file header）**。一次损坏的传输可能让这两份元数据不一致——例如
文件名被改写、大小或 CRC-32 值被修改、真实文件被标记为目录、或
结束记录（EOCD）损坏。严格校验的解压软件——7-Zip、WinRAR、Windows 资源
管理器、Python 的 `zipfile`——会比较这两份数据，发现不一致后拒绝解压，
报"文件名字不一致"或"CRC-32 校验失败"等错误。JVM 的
`java.util.zip.ZipFile` 信任中央目录，所以即使其他工具打不开，它仍能读取
数据。

## 功能

- **检查**：检查压缩包——条目数、本地头与中央目录元数据差异（文件名、
  大小、CRC）、伪目录条目、伪造 EOCD 签名、数据 CRC-32 校验、无法读取的
  条目——给出 完好 / 已损坏 的结论。
- **修复**：重建干净压缩包。用 `ZipFile`（随机读取）读出数据，再通过
  `ZipOutputStream` 重新写出，重新生成完全一致的元数据。输出的压缩包
  任何解压软件都能打开。

## 使用方法

图形界面（标签页：检查 / 修复；把 ZIP 拖进标签页，或点击选择）：

    java -jar ZipRepairTool.jar

命令行：

    java -jar ZipRepairTool.jar -check <输入.zip>
    java -jar ZipRepairTool.jar -repair <输入.zip> <输出.zip>

## 构建

需要 JDK 8 或更高版本。

- Windows：`build.bat`
- Linux / macOS：`./build.sh`

## 许可证

[Unlicense](LICENSE) —— 本软件已贡献至公有领域。
