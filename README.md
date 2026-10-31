# SPW ReplayGain Calculator

一个 [Salt Player 创意工坊（SPW Workshop）](https://saltplayer.com/workshop/getting-started) 插件，用于为 Salt Player 的「**音量平衡**」功能准备数据。

Salt Player 的「音量平衡」会读取音频文件中的 ReplayGain 标签来统一不同歌曲的播放音量，但**不会自动计算**这些标签。本插件使用内置的纯 Java 解码器与 **EBU R128**（ITU-R BS.1770-4）分析测量歌曲响度，计算 **ReplayGain 2.0** 增益并写入文件标签，让「音量平衡」能够正常工作。**无需安装 FFmpeg 或任何外部程序。**

- 插件 ID：`com.alierkitten.spw.replaygain`
- 构建产物：`build/libs/plugin-com.alierkitten.spw.replaygain-1.0.0.spmod`

## 与「音量平衡」的关系

1. 本插件把 `REPLAYGAIN_TRACK_GAIN`、`REPLAYGAIN_TRACK_PEAK` 等标签写入 MP3 / FLAC / OGG 文件。
2. 在 Salt Player 中开启「音量平衡」后，播放器会读取这些标签，自动拉平各歌曲的音量差异。
3. 插件使用 **SQLite** 记录已处理歌曲，避免重复计算。

参考响度可在插件配置中自定义，默认 **-18 LUFS**。

## 环境要求

- **Salt Player**（支持 Workshop / Mod 的版本）。
- **JDK 25**（工程通过 `jvmToolchain(25)` 编译）。
- 无需安装 FFmpeg 或其它外部程序；响度测量所需的音频解码与 EBU R128 分析均内置在插件内（依赖纯 Java 的 `mp3spi` / `vorbisspi` / `tritonus-all` 解码器，通过 `javax.sound.sampled` 提供服务）。

## 构建

```powershell
# Windows
.\gradlew.bat plugin
```

```bash
# Linux / macOS
./gradlew plugin
```

构建成功后将生成的 `.spmod` 文件导入 Salt Player，启用插件并授予曲库读取权限即可。

## 使用

- **自动索引**：开启「启动时自动补扫」后，插件启用时会自动处理尚未索引的歌曲。
- **手动索引**：配置页提供「索引全部歌曲」「索引当前播放歌曲」按钮。
- 未授予曲库权限、或遇到无法解码的音频文件时，插件会提示并跳过，不影响播放器运行。

## 配置项

| 配置项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| 参考响度 (LUFS) | 文本 | `-18.0` | 目标响度，范围 `-30.0` ~ `0.0` |
| 启动时自动补扫 | 开关 | `false` | 启用后自动处理未索引曲目 |
| 将结果写入文件标签 | 开关 | `true` | 关闭后仅测量并记录到数据库 |

增益计算公式：

```text
REPLAYGAIN_TRACK_GAIN = 参考响度 - 实测集成响度
```

## 目录结构

```text
spw-replay-gain-calculator/
├── build.gradle.kts                 # Kotlin/JDK 25、依赖、spmod 元数据与权限
├── settings.gradle.kts              # JitPack、workshop 插件解析
├── gradle/libs.versions.toml        # 版本目录
├── gradle/wrapper/                  # Gradle Wrapper
├── gradlew / gradlew.bat
└── src/main/
    ├── kotlin/com/alierkitten/spw/replaygain/
    │   ├── MainPlugin.kt            # 插件入口、生命周期、自动补扫
    │   ├── config/PluginConfig.kt   # 配置读取与数据目录
    │   ├── replaygain/
    │   │   ├── ReplayGainEngine.kt  # 内置 EBU R128 测量与增益计算
    │   │   ├── analyzer/
    │   │   │   ├── AudioDecoder.kt  # 纯 Java 解码 MP3/FLAC/OGG 为 PCM
    │   │   │   ├── Resampler.kt     # 窗函数 sinc 重采样
    │   │   │   └── LoudnessAnalyzer.kt # K 加权、门控与真峰
    │   │   ├── ReplayGainResult.kt  # 测量结果
    │   │   └── TagWriter.kt         # MP3/FLAC/OGG 标签写入
    │   └── index/
    │       ├── IndexStore.kt        # SQLite 持久化
    │       └── IndexService.kt      # 索引流水线
    └── resources/
        └── preference_config.json   # 配置界面
```

## 数据库

SQLite 文件位置：

```text
workshop/data/com.alierkitten.spw.replaygain/replaygain.db
```

表结构：

```sql
CREATE TABLE indexed_tracks (
    track_key TEXT PRIMARY KEY,   -- 歌曲 ID，缺失时使用文件路径
    track_id TEXT,
    path TEXT NOT NULL,
    album TEXT,
    integrated_loudness REAL,
    true_peak_db REAL,
    track_gain REAL,
    track_peak REAL,
    reference REAL,
    status TEXT,
    indexed_at INTEGER
);
```

## 权限

| 权限 | 用途 |
| --- | --- |
| `library-read` | 读取曲库歌曲元数据与文件路径 |

未授予 `library-read` 时，索引任务会提示并跳过。

## 限制

- 支持 **MP3 / FLAC / OGG**，暂不支持 M4A / MP4。
- 无需安装 FFmpeg；解码与分析均在插件内完成（纯 Java，无外部二进制）。
- 新导入的歌曲会在下次启用插件或手动点击按钮时被处理。
