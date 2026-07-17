# 漫读 MangaReader

Android 漫画阅读器，基于 Kotlin + Jetpack Compose + Material3。支持 PDF、ZIP/CBZ、RAR/CBR、MOBI、EPUB、AZW3 等漫画格式。

## 功能特性

- **多格式支持**：PDF、ZIP/CBZ、RAR/CBR、MOBI、EPUB、AZW3
- **原生渲染引擎**：C++ 实现的 PDFium、stb_image 高性能图片解码
- **智能目录扫描**：自动识别漫画文件，按文件夹整理书架
- **封面提取**：自动从漫画文件中提取封面缩略图
- **阅读模式**：左右翻页、上下滚动（Webtoon 模式）、自动滚动
- **主题系统**：Catppuccin、Nord、Tachiyomi、Yotsuba 等 13 种配色方案
- **阅读设置**：亮度、背景色、页面缩放、双页模式
- **搜索历史**：本地漫画搜索与历史记录

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material3
- **数据库**：Room
- **图片加载**：Coil
- **原生层**：C++ (CMake) + JNI
- **压缩处理**：Apache Commons Compress + JunRAR + libarchive
- **构建**：Gradle Kotlin DSL + KSP

## 编译

### 环境要求
- Android Studio Hedgehog 或更高版本
- JDK 17
- Android SDK 36
- NDK 29.0.14206865

### 编译步骤

```bash
# 克隆仓库
git clone https://github.com/IMJXXL/manga-reader.git
cd manga-reader

# Debug 版本
./gradlew assembleDebug

# Release 版本（需要配置签名）
./gradlew assembleRelease
```

### Release 签名

通过环境变量配置签名：

```bash
export KEYSTORE_PATH=/path/to/your.jks
export STORE_PASSWORD=your_password
export KEY_ALIAS=your_alias
export KEY_PASSWORD=your_key_password
```

或在 `local.properties` 中配置。

## 项目结构

```
app/src/main/
├── java/com/mangareader/
│   ├── App.kt                 # Application
│   ├── MainActivity.kt        # 主入口
│   ├── Scanner.kt             # 文件扫描器
│   ├── data/                  # 数据层（Room、Config）
│   ├── native/                # 原生渲染（JNI）
│   ├── ui/                    # Compose UI
│   ├── viewer/                # 漫画查看器核心
│   └── zip/                   # ZIP/EPUB 解析
├── cpp/                       # C++ 原生代码
│   ├── pdfium_wrapper.cpp     # PDF 渲染
│   ├── native_reader.cpp      # 图片解码
│   └── libmobi/               # MOBI 格式支持
├── jniLibs/                   # 预编译 .so 库
└── res/                       # 资源文件
```

## 支持格式

| 格式 | 说明 |
|------|------|
| PDF | 通过 PDFium 原生渲染 |
| ZIP/CBZ | 直接读取压缩包内图片 |
| RAR/CBR | 通过 JunRAR 解压 |
| MOBI | 通过 libmobi C 库解析 |
| EPUB | 自定义解析器提取图片 |
| AZW3 | 识别 KF8 格式，转为 MOBI 处理 |

## 许可证

MIT License

## 致谢

- [Tachiyomi](https://github.com/tachiyomiorg/tachiyomi) - 漫画阅读器的灵感来源，SubsamplingScaleImageView 组件
- [PDFium](https://pdfium.googlesource.com/pdfium/) - PDF 渲染引擎
- [stb_image](https://github.com/nothings/stb) - 图片解码
- [JunRAR](https://github.com/junrar/junrar) - RAR/CBR 解压
- [libarchive](https://github.com/libarchive/libarchive) - 压缩包处理
- [libmobi](https://github.com/nicklockwood/libmobi) - MOBI/AZW3 格式解析
- [Coil](https://coil-kt.github.io/coil/) - 图片加载
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) - ZIP/7Z 处理
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - UI 框架
- [Room](https://developer.android.com/training/data-storage/room) - 本地数据库
