# 伪DC调光 · FakeDC

> 让 OLED 面板在低亮度下**不再靠降低背光压暗**，而是把背光钉在一个下限上、用像素层面补偿出更低的亮度。
> 目标只有一个：**低亮度不闪（消 PWM），画面亮度仍然跟你的亮度条走。**

Android / LSPosed 模块 ｜ 作用域 `system` ｜ libxposed API 102 ｜ GPL-3.0

> English: An LSPosed module that clamps the display backlight to a lower bound and compensates the
> perceived brightness with a display color-matrix gain, so the panel never runs at a low duty cycle.
> Same perceived brightness, no low-brightness PWM.

## 下载

APK 在 **[Releases](https://github.com/NoctSis/FakeDC/releases)** 里（仓库只放源码，构建产物不入库）。

- `FakeDC-1.0.3.apk` — 正式签名，覆盖安装即可升级
- 需要 **LSPosed**（libxposed API 102），作用域 `system`
- 装完必须在 LSPosed 里启用模块 → **重启**，否则模块不会注入 `system_server`

---

## 它解决什么问题

OLED 在低亮度靠 **PWM 调光**：不是把电流调小，而是让像素高频开关、用占空比表示亮度。
占空比越低，闪烁越深，长时间看容易眼酸、头痛。

伪 DC 调光的思路是反过来的：

- **不让面板降得太低** —— 背光栈在一个下限上，面板始终工作在高占空比区间；
- **用画面把多出来的亮度压回去** —— 写显示色彩矩阵，把每个像素乘小一点。

所以观感亮度和原来一样，变的是**面板的工作点**。

## 工作原理（三层，很薄）

```
亮度条 → 系统算出目标亮度 t
   │
   ├─① BacklightAdapter.setBacklight(FFFFZ)V   ← 出口钩子
   │     t >= minB → 完全不碰（最高亮度 / HBM 照常，模块不挡路）
   │     t <  minB → args[0](sdrBrightness) 与 args[2](面板背光) 双钉到 minB
   │
   ├─② DisplayDimModifier.shouldApply → 恒 false
   │     掐断系统 DIM：否则 policy=DIM 时亮度会被压成与输入无关的常量（"亮度锁死"）
   │
   └─③ 独立线程：gain = (t / minB) ^ (1/2.2) → 写 DisplayTransformManager 4242 槽位
         1/2.2 是 sRGB gamma 反解。用线性比会在低端掉得太快（看起来"突然全黑"）
         观感亮度 = minB × gain^2.2 = t   ← 数学上精确相等
```

三条纪律：

- **热路径零 IO**：`intercept()` 只读内存、算数、改参数；配置读取与日志上报都在别的线程。
- **配置改动不需要重启**（模块侧只在收到变更信号时重读，并有 30s 兜底）。
- **换 dex 必须重启**（LSPosed 在开机时加载模块）。

## 配置（只有三项）

| 键（`CfgProvider` 列） | 含义 | 默认 | 范围 |
|---|---|---|---|
| `enable` | 总开关 | **1（开）** | 0 / 1 |
| `min_bright` | DC 区间下限，**单位是亮度滑块的百分比** | **0.20** | 0.002 ~ 1.0 |
| `gamma_comp` | 画面补偿（标定旋钮） | **1.00** | 0.5 ~ 1.5 |

界面上每项都是「滑块 + 数值输入框」，改完立即生效（配置走 `ContentProvider`，不重启）。

### 单位怎么换算（容易搞错的一处）

```
滑块 20%  →  min_bright = 0.20  →  ×BRIGHT_FULL(0.5) = 0.10（framework 亮度）→ 系统映射表 → HAL → 面板
└─ 你填的 ─┘  └── 配置里存的 ──┘  └──── 模块算的 ────┘  └──────── 系统负责，模块不管 ────────┘
```

- `min_bright` **不是** framework 亮度值，也不是 HAL 值（HAL 是 8191 还是 16383 都不影响）；
- `BRIGHT_FULL` 是「亮度滑块满刻度对应多少 framework 亮度」，**本机 = 0.5**（`hbmMax=0.499938`、`factory_max_brightness=8191 ÷ max_brightness=16383`、实测三条一致）。

### 换机型：只需标定一个常数

1. 系统亮度拉到最大；
2. `dumpsys display | grep mBrightnessState` —— 那个亮度值就是满刻度；
3. 填进 `Cfg.BRIGHT_FULL`。

错的话症状很明显：设 60%、面板实际钉在 30% 或 120%，一眼能看出来。

## 怎么用

1. 装 APK → 在 **LSPosed** 里启用模块（作用域 `system`）→ **重启手机**；
2. 打开 FakeDC，「启用」保持开；
3. 把「DC 区间下限」**从低往高拖**，拿另一台手机的**摄像头**对着屏幕看（人眼看不准，摄像头能直接看到条纹）：
   - 还在闪 → 继续加；
   - 不闪了 → 往回退一点，找到**刚好不闪**的那一档，停住；
4. 觉得画面发白/发亮，把「画面补偿」往 `0.90`、`0.88` 调（偏暗就往 1.05 调）。

**为什么是"刚好不闪"而不是越高越好**：钉得越高，压暗倍数越大，可用灰阶越少——
`t=3%` 时，下限 10% 还剩 148 级灰阶，下限 60% 只剩 65 级，条带和暗部会明显变差。
这是**用平滑度换不闪**的取舍，最优点就是 DC 区间的下沿。

## 构建

依赖：

- JDK 21
- Android SDK：`platforms/android-37`、`build-tools/34.0.0`、`cmdline-tools/latest/lib/r8.jar`

```bash
cd fakedc-api102
bash build.sh 277 1.0.3        # 用法: build.sh <versionCode> [versionName]
# 产物: ../bata/apk/FakeDC-1.0.3.apk   （暂存区）

bash release.sh 1.0.3           # 确认是正式版后，提升到 ../release/（仓库根目录）
bash release.sh 1.0.3 --build   # 也可以一步：重新构建 + 提升
```

产物目录可用 `OUT_DIR=... bash build.sh ...` 覆盖（默认 `../bata/apk/`）。

管线：`javac → d8 → aapt2 compile/link → zip 追加 dex 与 META-INF/xposed/* → zipalign → apksigner`。

签名：默认用仓库内密钥以保持升级一致；别人的机器上请用自己的：

```bash
FDKS=/absolute/path/my.jks FDKSPASS=你的密码 bash build.sh 277 1.0.3
```

交付前自检（两个独立判官，共 57 + 15 项）：

```bash
bash verify.sh 277        # 注入前提 / 配置通道 / 逻辑正确性 / 热路径纪律 / UI / 交付卫生
bash crosscheck.sh 277    # dex 二进制通道 / 打包字节 / 公式双实现对拍 / 仓库可追溯性
```

## 目录

```
fakedc-api102/
  app/src/main/java/com/lumen/fakedc/
    FakeDCModule.java      模块主体：三个钩子 + 矩阵线程 + 配置读取
    Cfg.java               配置/状态的唯一契约（key、默认值、单位说明）
    CfgProvider.java       只读 ContentProvider（UI 写私有 SP，模块 query）
    SettingsActivity.java  极简界面（滑块 + 数值输入 + 那行运行日志）
    Theme.java             配色
    BuildInfo.java         编译期注入的版本身份（由 build.sh 生成）
  app/src/main/res/        图标与字符串
  app/src/main/resources/META-INF/xposed/   module.prop / scope.list / java_init.list
  tools/make_icon.py       图标生成（纯几何 + 渐变，程序化产出，无第三方素材）
  tools/check_icon.py      图标自检，26 项（尺寸/安全区/不透明率/渐变两端/中心像素）
  build.sh                 构建（javac → d8 → aapt2 → zip → zipalign → apksigner）
  release.sh               把暂存区的包提升为正式版（复制到仓库根目录 release/）
  verify.sh                交付前自检，57 项
  crosscheck.sh            第二判官，独立复算，15 项
README.md  CREDITS.md  LICENSE
```

> 开发过程中还有两份本地内容**未随仓库发布**：旧实现 `fakedc-legacy/`（经典 Xposed API，
> 仅作版本回溯）与开发笔记 `REA-DC-知识库/`（真机实测、踩坑与决策记录）。

## 深入原理

想读代码、想自己改、或者想在自己机型上复现，看 **[原理与实现详解](docs/PRINCIPLE.md)**：

- 为什么低亮度必然闪（PWM vs DC 的物理）
- 三层结构各自解决什么，少一层会坏成什么样
- 代码逐段：两个钩子为什么这么写、公式 `gain=(t/minB)^(1/2.2)` 怎么来的
- 配置为什么走 `SharedPreferences + ContentProvider`（另外两条路都试过、都失败）
- 三个"亮度"的单位换算，以及换机型只改哪一个常数
- 那行日志 20 个字段怎么读（每个字段对应什么故障）

## 已知限制（不是 bug）

| 现象 | 原因 |
|---|---|
| 亮度拉到最低 + 下限设很高 → **画面可能全黑** | 8bit 量化极限：要压 20 倍时，内容低于 22/255 直接归零 |
| 暗部有条带、深色糊成一片 | DC 是恒定电流，没有 PWM 那种时域抖动来抹平量化台阶。面板特性，与 PWM 是同一枚硬币的两面 |
| 画面偏白 / 偏亮 | 面板在高背光下的 EOTF 漂移 + gamma 2.2 近似，用「画面补偿」现场标定 |
| 本机没有硬件 DC | 已探明 `dc_mode`/`dc_backlight` 内核节点存在但 LCM 未实现（无 DCS 下发），伪 DC 只能减弱、不能真正消除 PWM |

## 实测环境

Redmi K60 Ultra（`corot`，MTK）/ Android 13 / MIUI / LSPosed（libxposed API 102）/ KernelSU。
**其它机型未验证**——机制是通用的，但 `BRIGHT_FULL` 与"DC 下沿"必须按上面的方法自己标定。

## 引用与致谢

思路来源、依赖许可证状态、以及"为什么可以这样引用"，见 **[CREDITS.md](CREDITS.md)**。
简要版：

| 项目 | 性质 | 许可证 |
|---|---|---|
| [ztc1997/FakeDCBacklight](https://github.com/ztc1997/FakeDCBacklight) | 设计参照（钉背光 + 压暗的思路、HAL 最低亮度前提）。**未复制代码** | 无 LICENSE 文件（仓库已归档） |
| [libxposed/api](https://github.com/libxposed/api) `102.0.0` | 编译期 API 依赖 | Apache-2.0 |
| [LSPosed](https://github.com/LSPosed/LSPosed) | 运行框架 | GPL-3.0 |

## 许可证

[GPL-3.0](LICENSE)。改动可以，分发也可以，但**改完必须同样开源**——
这个模块会改写系统的显示行为，出了问题得有人能看见改了什么。
（换许可证只需版权人一句话。）

## 免责声明

本模块通过 hook `system_server` 修改显示相关行为。**风险自负**：

- 可能导致亮度异常、耗电增加、烧屏风险（长时间高背光）；
- 不同机型/ROM 行为差异很大，未验证机型请先小范围试；
- 作者不对任何设备损坏、数据丢失或保修失效负责。
