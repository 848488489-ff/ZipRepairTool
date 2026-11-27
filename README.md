# ZipRepairTool

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/lang-English-blue.svg" alt="English"></a>
  <a href="README_zh_cn.md"><img src="https://img.shields.io/badge/lang-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-orange.svg" alt="简体中文"></a>
</p>

<p align="center">
  <a href="version.json"><img src="https://img.shields.io/badge/version-1.0-blue.svg" alt="Version 1.0"></a>
  <img src="https://img.shields.io/badge/license-Unlicense-lightgrey.svg" alt="License: Unlicense">
  <img src="https://img.shields.io/badge/JDK-8+-orange.svg" alt="JDK 8+">
  <img src="https://img.shields.io/badge/build-passing-brightgreen.svg" alt="Build passing">
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20Linux%20%7C%20macOS-informational.svg" alt="Cross-platform">
  <img src="https://img.shields.io/badge/release-JAR%20%2B%20docs-blueviolet.svg" alt="Release: JAR + docs">
</p>

<p align="center">
  <img src="https://img.shields.io/github/last-commit/848488489-ff/ZipRepairTool.svg" alt="Last commit">
  <img src="https://img.shields.io/github/languages/top/848488489-ff/ZipRepairTool.svg" alt="Top language">
  <img src="https://img.shields.io/github/repo-size/848488489-ff/ZipRepairTool.svg" alt="Repo size">
  <img src="https://img.shields.io/github/release/848488489-ff/ZipRepairTool.svg" alt="Latest release">
  <img src="https://img.shields.io/github/forks/848488489-ff/ZipRepairTool.svg" alt="Forks">
  <img src="https://img.shields.io/github/stars/848488489-ff/ZipRepairTool.svg" alt="Stars">
</p>

ZipRepairTool is a **Java** command-line and GUI tool that repairs ZIP archives
damaged in transit. When a ZIP's metadata becomes inconsistent — for example
after an interrupted or corrupted transfer — most extractors refuse to open it.
This tool rebuilds the archive so it opens again.

It also offers an integrity **check** that inspects an archive and reports what
is inconsistent, and a repair mode that rebuilds a clean archive from the
stored data.

## Why archives fail

A ZIP archive keeps two copies of its metadata: the **central directory** and
the **local file header**. A damaged transfer can leave these disagreeing —
for example a file name rewritten, sizes or CRC-32 values altered, a real file
flagged as a directory, or a broken end-of-central-directory record. Strict
extractors — 7-Zip, WinRAR, Windows Explorer, Python's `zipfile` — compare the
two and reject the archive with errors such as "file name in directory and
header differ" or "bad CRC-32". The JVM's `java.util.zip.ZipFile` trusts the
central directory, so it can still read the data even when other tools cannot.

## Features

- **Check**: inspect an archive — entry count, local/central metadata
  divergence (names, sizes, CRC), fake directory entries, forged EOCD
  signature, data CRC-32 verification and unreadable-entry detection. Reports
  an OK / DAMAGED verdict.
- **Repair**: rebuild a clean ZIP. Data is read with `ZipFile` (random access)
  and re-emitted through `ZipOutputStream`, which regenerates fully consistent
  metadata. The output opens in any extractor.

## Usage

GUI (three tabs: Check / Repair; drag & drop a ZIP onto a tab, or pick one
with the file chooser):

    java -jar ZipRepairTool.jar

Command line:

    java -jar ZipRepairTool.jar -check <in.zip>
    java -jar ZipRepairTool.jar -repair <in.zip> <out.zip>

## Build

Requires JDK 8 or newer.

- Windows: `build.bat`
- Linux / macOS: `./build.sh`

## License

[Unlicense](LICENSE) — this software is released into the public domain.
