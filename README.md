# SPW ReplayGain Calculator

一个 [Salt Player 创意工坊（SPW Workshop）](https://saltplayer.com/workshop/getting-started) 插件。

使用系统 **FFmpeg** 测量歌曲的 EBU R128 集成响度（LUFS）与真峰，计算 **ReplayGain 2.0** 增益并直接写入音频文件元数据；使用 **SQLite** 记录已索引歌曲，避免重复计算。参考响度可在插件配置界面自定义，默认 **-18 LUFS**。

- 插件 ID：`com.alierkitten.spw.replaygain`
- 主类：`com.alierkitten.spw.replaygain.MainPlugin`
- 构建产物：`build/libs/plugin-com.alierkitten.spw.replaygain-1.0.0.spmod`

## 功能特性

- **ReplayGain 2.0 测量**：调用 FFmpeg `ebur128` 滤镜，解析集成响度（`I`）与真峰（`Peak`）。
- **写回文件元数据**：
  - MP3：ID3v2 `TXXX:REPLAYGAIN_*` 帧
  - FLAC / OGG：Vorbis Comment
  - 写入字段：`REPLAYGAIN_TRACK_GAIN`、`REPLAYGAIN_TRACK_PEAK`、`REPLAYGAIN_ALBUM_GAIN`、`REPLAYGAIN_REFERENCE_LOUDNESS`
- **自动索引**：插件启用（`start()`）时对全库尚未索引的歌曲自动补扫。
- **手动索引**：配置页按钮「索引全部歌曲」「索引当前播放歌曲」。
- **SQLite 持久化**：记录每首已索引歌曲的 ID、路径、专辑、响度、增益、峰值与参考响度。
- **可配置参考响度**：默认 `-18.0 LUFS`，文本输入，范围 `-30.0 ~ 0.0`，超过 1 位的小数在保存时自动舍弃。
- **可手动指定 FFmpeg 路径**：无需把 FFmpeg 加入 `PATH`，在设置中填写即生效（无需重启插件）。
- **容错降级**：未安装 FFmpeg 或未授予曲库读取权限时给出提示并跳过，不影响播放器运行。

## 环境要求

1. **Salt Player**（支持 Workshop / Mod 的版本）。
2. **JDK 25**：工程通过 `jvmToolchain(25)` 编译。
3. **FFmpeg**：必须安装并满足以下任一条件：
   - 在插件设置中填写「FFmpeg 路径」（可填可执行文件或所在目录的绝对路径）；
   - 设置环境变量 `FFMPEG_PATH` 指向 FFmpeg 可执行文件；
   - `ffmpeg` 在系统 `PATH` 中。
4. 首次构建需要联网（从 JitPack 拉取 SPW Workshop API 与 Gradle 插件）。

## 构建

Windows：

```powershell
.\gradlew.bat plugin
```

Linux / macOS：

```bash
./gradlew plugin
```

构建成功后会生成：

```text
build/libs/plugin-com.alierkitten.spw.replaygain-1.0.0.spmod
```

将 `.spmod` 文件导入 Salt Player，启用插件并授予所需权限即可。

## 配置项

配置界面由 `src/main/resources/preference_config.json` 定义，用户配置保存在插件数据目录：

```text
workshop/data/com.alierkitten.spw.replaygain/config.json
```

| 配置项 | 键 | 类型 | 默认值  | 说明 |
| --- | --- | --- |---------| --- |
| 参考响度 (LUFS) | `replaygain.referenceLoudness` | edittext | `-18.0` | 目标响度，范围 `-30.0` ~ `0.0`，保留 1 位小数 |
| FFmpeg 路径 | `replaygain.ffmpegPath` | edittext | 空      | 可执行文件或所在目录路径；留空则回退到 `FFMPEG_PATH` / `PATH` |
| 启动时自动补扫未索引歌曲 | `replaygain.autoIndexOnStart` | switch | `false` | 插件启用后自动处理未索引曲目 |
| 将结果写入文件标签 | `replaygain.writeTags` | switch | `true`  | 关闭后仅测量并记录到数据库 |

增益计算公式：

```text
REPLAYGAIN_TRACK_GAIN = 参考响度 - 实测集成响度
```

## 工作原理

```text
start()
  ├─ 读取配置（参考响度 / 自动补扫 / 写标签）
  ├─ 探测 FFmpeg
  ├─ 初始化 SQLite（replaygain.db）
  └─ 若开启自动补扫 → indexAll()

indexAll() / indexCurrent()
  ├─ WorkshopApi.library.getAllTracks()  (需要 LIBRARY_READ)
  ├─ 过滤已索引歌曲（SQLite）
  ├─ ReplayGainEngine.measure() → FFmpeg ebur128
  ├─ TagWriter.write() → 写 REPLAYGAIN_* 标签
  ├─ IndexStore.markIndexed()
  └─ finalizeAlbums() → 按专辑估算并回写 REPLAYGAIN_ALBUM_GAIN
```

说明：

- 当前 SPW API 的 `LIBRARY_WRITE` 尚无写入入口，因此标签通过 **JAudiotagger 直接写入音频文件**。
- 曲库路径来自 `MediaItem.path`（需要 `LIBRARY_READ`）。
- 测量在单线程后台执行器中进行，避免阻塞宿主 UI；同一时间只允许一个索引任务。
- 专辑增益按专辑内已索引曲目的集成响度做能量平均后估算（近似值）。

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
    │   ├── util/FfmpegChecker.kt    # FFmpeg 探测
    │   ├── replaygain/
    │   │   ├── ReplayGainEngine.kt  # FFmpeg ebur128 测量与增益计算
    │   │   ├── ReplayGainResult.kt  # 测量结果
    │   │   └── TagWriter.kt         # MP3/FLAC/OGG 标签写入
    │   └── index/
    │       ├── IndexStore.kt        # SQLite 持久化
    │       └── IndexService.kt      # 索引流水线与专辑增益回写
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
    album_gain REAL,
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

## 已知限制

- SPW API 目前没有媒体库导入/扫描扩展点，因此「导入文件时自动索引」以 **启动时自动补扫 + 手动触发** 实现。新导入的歌曲会在下次启用插件或手动点击按钮时被处理。
- 必须安装 FFmpeg；未安装时无法测量响度。
- 支持 **MP3 / FLAC / OGG**；暂不支持 M4A / MP4。
- 专辑增益为近似值（基于已索引曲目的能量平均）。
- ID3v2.2 标签会自动提升为 ID3v2.4 后再写入。

## 依赖

| 依赖 | 用途 |
| --- | --- |
| `com.github.Moriafly:spw-workshop-api:0.1.0-dev22` | SPW Workshop API（宿主提供，`compileOnly`） |
| `net.jthink:jaudiotagger:3.0.1` | 音频标签读写 |
| `org.xerial:sqlite-jdbc:3.46.1.3` | SQLite 持久化 |

`jaudiotagger` 与 `sqlite-jdbc` 会随 `.spmod` 一起打包到 `lib/` 目录。
