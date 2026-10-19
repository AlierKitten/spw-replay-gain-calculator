# SPW ReplayGain Calculator

一个 [Salt Player 创意工坊（SPW Workshop）](https://saltplayer.com/workshop/getting-started) 插件，用于为 Salt Player 的「**音量平衡**」功能准备数据。

Salt Player 的「音量平衡」会读取音频文件中的 ReplayGain 标签来统一不同歌曲的播放音量，但**不会自动计算**这些标签。本插件调用系统 **FFmpeg** 测量歌曲响度（EBU R128），计算 **ReplayGain 2.0** 增益并写入文件标签，让「音量平衡」能够正常工作。

- 插件 ID：`com.alierkitten.spw.replaygain`
- 当前版本：`1.0.5`
- 构建产物：`build/libs/plugin-com.alierkitten.spw.replaygain-1.0.5.spmod`

## 与「音量平衡」的关系

1. 本插件把 `REPLAYGAIN_TRACK_GAIN`、`REPLAYGAIN_TRACK_PEAK` 等标签写入 MP3 / FLAC / OGG 文件。
2. 在 Salt Player 中开启「音量平衡」后，播放器会读取这些标签，自动拉平各歌曲的音量差异。
3. 插件使用 **SQLite** 记录已处理歌曲，避免重复计算。

参考响度可在插件配置中自定义，默认 **-18 LUFS**。

## 自动触发宿主曲库刷新（新）

> 实验性功能，依赖反射调用 Salt Player 内部入口；见下方「[实现原理](#实现原理)」。

每次 ReplayGain 测量实际写入了文件后，会尝试让 Salt Player **立刻重新读取**刚刚写入的标签——这样不需要用户在 Salt Player 里再点一次「刷新音乐库」，写入的增益数据即可在「音量平衡」中生效。流程：

```
测量 + 写标签 + 写数据库（原有逻辑）
        ↓
触发宿主曲库刷新（反射调用）
        ↓
成功 → toast「已请求宿主刷新曲库（ReplayGain 即将生效）」
失败 → toast「未能触发宿主曲库刷新，请手动刷新音乐库」+ 一条日志
```

涉及到的入口：

- **自动**：启用插件时若「启动时自动补扫未索引歌曲」开启，并且本次测量实际写入了至少一首，则在测量完成后自动触发。
- **手动**：
  - 「索引全部歌曲」「重新全量索引」「索引当前播放歌曲」在每次完成测量写入后都会触发。
  - 配置页「索引操作 → 触发宿主曲库刷新」独立按钮：用于一次性让 Salt Player 重新读取历史写入的 ReplayGain 标签，无需重新测量。

### 配置项

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| 测量后触发宿主曲库刷新 | `true` | 实验性：测量并写入标签后是否触发宿主曲库刷新。失败时仅提示一次，不影响插件其他功能；如未来 Salt Player 重命名入口，可关闭此开关恢复旧行为。 |

### 限制（请阅读）

- **本次扫描新入库的歌曲不在当次测量**：单轮流程下，宿主扫描新发现的文件不会在同一次会话里被测量。它们会在下次启用插件时被处理，对应的标签需再下一次扫描才会生效。这是按设计选择的有序约束（先测量后扫描，避免重复扫描），并非缺陷**。**
- **入口可能在宿主升级时失效**：反射调用 `com.xuncorp.voxzen.service.RepoService.INSTANCE.rebuildMusicLibrary()` 是依据当前 Salt Player 版本（1.19.0）通过 `tools/inspect-host.ps1` + `javap -p` 静态定位的，宿主升级后若类名/方法名变更会自动降级为「请手动刷新音乐库」提示。可通过 `tools/inspect-host.ps1` 在新版本宿主下重新定位并更新 `host/HostScanEntry.kt`。
- **不需要新权限**：反射访问插件的类加载器父级即可调用，不需要 `CLASS_TRANSFORM` 等敏感权限。

## 环境要求

- **Salt Player** 1.19.0 或更高版本（与 Workshop API `0.1.0-dev23` 及后续兼容版本对应）。
- **JDK 25**（工程通过 `jvmToolchain(25)` 编译）。
- **FFmpeg**，满足以下任一条件即可：
  - 在插件设置中填写「FFmpeg 路径」；
  - 设置环境变量 `FFMPEG_PATH` 指向可执行文件；
  - `ffmpeg` 已加入系统 `PATH`。

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

- **自动索引**：开启「启动时自动补扫」后，插件启用时会自动处理尚未索引的歌曲，并在写入后尝试触发宿主曲库刷新。
- **手动索引**：配置页提供「索引全部歌曲」「索引当前播放歌曲」等按钮，完成测量后会自动通知 Salt Player 重新读取标签。
- **历史标签一次性生效**：点击「触发宿主曲库刷新」按钮，无需重新测量即可让 Salt Player 重新读取所有已写入的 ReplayGain 标签（可重复点击）。
- 未安装 FFmpeg 或未授予曲库权限时，插件会提示并跳过，不影响播放器运行。

## 实现原理

### Workshop API 版本

本插件基于 Workshop API **`0.1.0-dev23`**（同时升级 gradle 打包插件到同一版本）。`dev22` 与 `dev23` 公开接口无差异（`dev23` 仅同步 Compose Multiplatform 与 salt-ui 依赖、文档统一指向官网），升级为保持与上游一致。

### 反射调用

Salt Player 截至 `1.19.0` 未提供公开的「刷新音乐库」API（上游 [`Tracks`](https://github.com/Moriafly/spw-workshop-api/issues/28) 维护者表示「刷新音乐库和通知可能会给上」）。在与 API 作者沟通后，当前方案：

1. **静态定位入口**：`tools/inspect-host.ps1` 读取 `app/Salt Player for Windows.cfg` 中的 `app.classpath`（实际指向伪装为 `ffmpeg-x64.dll` 的 fat jar），用 `System.IO.Compression.ZipFile` 枚举条目、按应用包名前缀（`com/xuncorp`、`com/moriafly` 等）过滤、关键字（`Library` / `Scan` / `Refresh` / `Index` / `Folder`）筛选，最后调用 `javap -p` 输出方法签名表。
2. **写入候选清单**：当前唯一候选 `com.xuncorp.voxzen.service.RepoService.INSTANCE.rebuildMusicLibrary()`（无参、`void`，非 suspend，宿主在内部以 `launchIn` 协程完成全库重建）。
3. **运行时反射**：`HostLibraryRefresher` 按 `WorkshopApi::class.java.classLoader` → `Thread.currentThread().contextClassLoader` → `ClassLoader.getSystemClassLoader()` 的顺序解析类与方法；在独立 daemon 线程执行 `Method.invoke`，10 秒 `join` 上限不阻塞索引线程；任何异常（`ClassNotFoundException` / `NoSuchMethodException` / `InaccessibleObjectException` / `InvocationTargetException` 等）都被捕获，**绝不冒泡**，仅返回 `RefreshResult.{Triggered,Unavailable,Failed}` 三态。
4. **降级路径**：入口不可用或调用失败时，`IndexService.finishWithHostRefresh` 仅记录一行日志 + 一次 toast 提示，不会让插件启动失败或影响播放。

宿主后续若正式提供 `refreshLibrary()` 等 API，应优先切换到官方实现，并在该处把 `HostScanCandidates.Primary` 设为 `null` 即可整体停用反射分支。

## 配置项

| 配置项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| 参考响度 (LUFS) | 文本 | `-18.0` | 目标响度，范围 `-30.0` ~ `0.0` |
| FFmpeg 路径 | 文本 | 空 | 留空则回退到 `FFMPEG_PATH` / `PATH` |
| 启动时自动补扫 | 开关 | `false` | 启用后自动处理未索引曲目 |
| 将结果写入文件标签 | 开关 | `true` | 关闭后仅测量并记录到数据库 |
| 测量后触发宿主曲库刷新 | 开关 | `true` | 实验性；失败时回退至旧指示 |

增益计算公式：

```text
REPLAYGAIN_TRACK_GAIN = 参考响度 - 实测集成响度
```

## 目录结构

```text
spw-replay-gain-calculator/
├── build.gradle.kts                 # Kotlin/JDK 25、dev23 依赖、spmod 元数据与权限
├── settings.gradle.kts              # JitPack、workshop 插件解析
├── gradle/libs.versions.toml        # 版本目录（含 spw-workshop-api 0.1.0-dev23）
├── gradle/wrapper/                  # Gradle Wrapper
├── gradlew / gradlew.bat
├── tools/
│   ├── inspect-host.ps1             # 宿主入口探测脚本
│   └── gradle-build.ps1             # 本地构建脚本（设置 JAVA_HOME 到 Gradle 工具链）
└── src/main/
    ├── kotlin/com/alierkitten/spw/replaygain/
    │   ├── MainPlugin.kt            # 插件入口、生命周期、按钮回调
    │   ├── config/PluginConfig.kt   # 配置读取与数据目录
    │   ├── util/FfmpegChecker.kt     # FFmpeg 探测
    │   ├── replaygain/
    │   │   ├── ReplayGainEngine.kt  # FFmpeg ebur128 测量与增益计算
    │   │   ├── ReplayGainResult.kt  # 测量结果
    │   │   └── TagWriter.kt         # MP3/FLAC/OGG 标签写入
    │   ├── index/
    │   │   ├── IndexStore.kt        # SQLite 持久化
    │   │   └── IndexService.kt      # 索引流水线（含反射收尾）
    │   └── host/
    │       ├── HostScanEntry.kt     # 宿主入口描述与候选清单
    │       └── HostLibraryRefresher.kt # 反射封装（永不抛异常，三态返回）
    └── resources/
        └── preference_config.json   # 配置界面（含触发开关与按钮）
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
- 必须安装 FFmpeg，否则无法测量响度。
- 新导入的歌曲会在下次启用插件或手动点击按钮时被处理；本次扫描新入库的歌曲按设计需「测量 → 扫描」两轮才能在音量平衡中生效。
- 反射调用的宿主入口会随 Salt Player 升级而失效；失败时插件会明确提示，并自动降级为原有行为。