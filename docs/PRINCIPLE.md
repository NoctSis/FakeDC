# 原理与实现详解

> 这份文档讲三件事：**为什么低亮度会闪**、**我们用什么办法让它不闪**、**代码每一段在干什么**。
> 面向想读代码、想改、或者想在自己的机型上复现的人。

---

## 0. 一页速览

| | |
|---|---|
| 问题 | OLED 低亮度用 PWM 调光，占空比越低闪得越深，长时间看眼睛累 |
| 做法 | 不让背光降下去（钉在一个下限），改用**显示色彩矩阵**把画面压暗，观感亮度不变 |
| 关键 | `gain = (t / minB) ^ (1/2.2)`，指数 `1/2.2` 用来抵消 sRGB gamma |
| 边界 | 只能变暗，不能超过面板峰值；压暗倍数越大，8bit 灰阶越紧张（条带/暗部糊） |
| 挂点 | `LocalDisplayAdapter$BacklightAdapter.setBacklight` / `DisplayDimModifier.shouldApply` / `DisplayTransformManager.setColorMatrix` |

一段话：

> 系统想要亮度 `t`。如果 `t` 不低于下限 `minB`，我们**什么都不做**；如果低于，就把面板背光钉在 `minB`
> （让它工作在高占空比、不闪的区间），再用矩阵把每个像素乘一个小于 1 的系数，
> 把这个"多出来的亮度"压回去。数学上 `minB × gain^2.2 = t`，所以眼睛看到的还是 `t`。

---

## 1. 为什么低亮度必须"闪"

OLED 的亮度只有两条路：

| 方式 | 做法 | 现象 |
|---|---|---|
| **DC 调光** | 恒定电流，调电流大小 | 不闪，但低电流下灰阶控制不准，暗部发灰/有条带 |
| **PWM 调光** | 电流恒定，**快速开关**，用占空比表示亮度 | 低亮度 = 低占空比 = 长时间黑，闪烁加深 |

大部分手机在低亮度用 PWM，因为暗部表现更好。代价就是频闪：1% 亮度时可能只有 1% 的时间是亮的。

伪 DC 的思路**不是**去改面板（多数面板驱动根本没实现 DC 模式），而是：

```
让面板永远不低于某个亮度（= 永远不进入低占空比）
       ↓
多出来的亮度，用"像素变暗"抵消掉
       ↓
像素变暗 = 乘法，不产生闪烁
```

一句话：**把"调背光"换成"调像素"**。

---

## 2. 三层结构

```
        ┌──────────────────────────────────────────────┐
        │ 用户拖动亮度条 → 系统算出目标亮度 t            │
        └───────────────────┬──────────────────────────┘
                            │
        ┌───────────────────▼──────────────────────────┐
        │ ① setBacklight 钩子（显示输出出口）            │
        │    t >= minB ?  → 原样透传，一个参数都不改      │
        │    t <  minB ?  → 把背光"钉"到 minB            │
        └───────────────────┬──────────────────────────┘
                            │
        ┌───────────────────▼──────────────────────────┐
        │ ② shouldApply 钩子（拆掉系统的 DIM 机制）      │
        │    恒返回 false，防止系统把亮度压成常量        │
        └───────────────────┬──────────────────────────┘
                            │
        ┌───────────────────▼──────────────────────────┐
        │ ③ 矩阵线程（每 16ms 一次）                     │
        │    gain = (t / minB) ^ (1/2.2) × 画面补偿      │
        │    写进 DisplayTransformManager 的 4242 槽位   │
        └──────────────────────────────────────────────┘
```

为什么必须三层？少任何一层都会坏：

| 少了谁 | 后果 |
|---|---|
| ① | 背光没钉住，压暗只是把画面变暗，PWM 一点没变（相当于一个调光器） |
| ② | 系统 DIM 把亮度压成常量 → 拖动亮度条没反应（**这就是"亮度被锁死"**） |
| ③ | 背光钉住了但没人补偿 → 画面比你要的亮，且亮度条失效 |

---

## 3. 代码逐段

### 3.1 钩子一：背光出口

```java
private static final class BacklightAdapterHooker implements Hooker {
    /** 0=sdrBacklight 1=sdrNits 2=backlight 3=nits */
    private static final int IDX_BACKLIGHT = 2;

    @Override public Object intercept(Chain chain) throws Throwable {
        List<Object> cargs = chain.getArgs();
        if (cargs == null || cargs.size() <= IDX_BACKLIGHT) return chain.proceed();
        float b = ((Number) cargs.get(IDX_BACKLIGHT)).floatValue();

        if (b <= 0f) return chain.proceed();          // 0/-1 = 灭屏，绝不钉亮
        ensureCfg();
        if (!cEnable) return chain.proceed();         // 总开关关着
        if (sIdleWhy != null) return chain.proceed(); // 息屏/AOD → 不干预

        float minB = minBNorm();
        if (b >= minB) return chain.proceed();        // 不低于下限 → 完全透传

        Object[] arr = cargs.toArray();
        arr[0]             = Float.valueOf(minB);     // sdrBrightness
        arr[IDX_BACKLIGHT] = Float.valueOf(minB);     // 面板背光
        sLastPin = minB;
        return chain.proceedWith(chain.getThisObject(), arr);
    }
}
```

四个细节，每个都是踩出来的：

1. **挂 5 参版**：本机实际被调用的是
   `setBacklight(float sdrBacklight, float sdrNits, float backlight, float nits, boolean galleryHdrBoost)`，
   4 参那个重载在整个 dex 里没有调用点（死代码）。
2. **钉两个参数**：只钉 `args[2]` 时，用摄像头仍能看到频闪——因为 HDR / 混合路径下
   `sdrBrightness`（`args[0]`）才是真正下发的那一个。两个一起钉才彻底。
3. **`b <= 0` 直接放行**：灭屏时系统传 0，把 0 钉成 `minB` 会让屏幕"熄不掉"。
4. **`b >= minB` 就完全不动**：所以最高亮度、HBM 阳光屏、HDR 峰值全都不受影响。
   模块只在低端干活，这是它能和系统共存的根本原因。

### 3.2 钩子二：拆掉 DIM

```java
private static final class DimModifierHooker implements Hooker {
    @Override public Object intercept(Chain chain) {
        sCallsDim++;
        return Boolean.FALSE;    // 不调 proceed() → 原方法不执行
    }
}
```

`DisplayDimModifier.shouldApply` 是系统"超时自动变暗"的开关。它一旦为真，亮度会被算成

```
max(min(b - 0.04, dimConfig), minConfig)     // 与输入几乎无关的常量
```

于是拖动亮度条完全没反应——**这就是本项目最初的主诉"锁屏后亮度锁死"**。
钉成 `false` 就等于把这套机制摘掉。

> 代价：系统原生的"超时渐暗"效果也没了。我们选择保留"能调亮度"，放弃"自动渐暗"。

### 3.3 增益公式：为什么是 `1/2.2`

```java
static float gainFor(float t, float minB) {
    if (minB <= 0f || t < 0f || t >= minB) return 1f;
    float r = t / minB;
    if (r <= 0f) return 0f;
    return (float) Math.pow(r, 1.0 / GAMMA_EXP);   // GAMMA_EXP = 2.2
}
```

推导只有两行：

- 面板亮度 ≈ `背光 × 像素值的线性光`，而像素值是 **sRGB 编码**过的，`线性光 = 编码值^2.2`；
- 我们要 `minB × (g^2.2) = t`，解出 `g = (t / minB)^(1/2.2)`。

**如果偷懒用线性比 `g = t/minB`**：`t/minB = 0.1` 时线性给 0.1，看起来几乎全黑；
正确值应该是 `0.1^(1/2.2) = 0.35`。低端会明显"掉得太快"，这也是历史上反复出现"过暗"的原因之一。

### 3.4 矩阵线程：写在哪、怎么防抖

```java
float t    = (sTargetBright > 0f) ? sTargetBright : sUserBright;  // 用户意图亮度
float minB = minBNorm();                                          // 下限（framework 单位）

String why = idleReason(sScreenOn);
if (why != null) { sIdleWhy = why; resetMatrix(); continue; }      // 息屏 → 复位矩阵

float g = gainFor(t, minB) * cGammaComp;                          // 画面补偿直接乘
if (g > 1f) g = 1f;
pushMatrix(g);
```

`pushMatrix` 里两个关键点：

```java
float tol = Math.max(1.5E-4f, g * 0.0025f);   // 吞掉的变化量
boolean changed   = Math.abs(g - sLastGain) >= tol;
boolean heartbeat = (now - sLastGainMs) >= 80L;
if (!changed && !heartbeat) return;           // 既没变、又不是心跳 → 跳过

float[] m = new float[16];
m[0] = m[5] = m[10] = g;  m[15] = 1.0f;       // 纯标量矩阵：R/G/B 同倍
dtmSetColorMatrix.invoke(dtm, m, 4242);        // 4242 = 我们独占的槽位
```

- **阈值不能太大**：早期用 `g*1.2%` 吞变化，拖动亮度条会"一顿一顿"，因为小步全被丢掉、攒够才跳一下。
- **80ms 心跳必须有**：显示管线在某些场景会重置矩阵，心跳负责把它补回来。
- **4242 槽位**：`DisplayTransformManager` 里给自定义用途留的固定 ID，用它不会和系统的色彩管理打架
  （也就不会影响截图亮度、色温等）。

### 3.5 为什么热路径一行 IO 都不能有

`intercept()` 跑在 `system_server` 的显示更新路径上，**每帧都会被调用**。
早期版本在这里查配置、拼字符串、写 Settings，结果把 `system_server` 拖死、整机触摸失灵。

现在的纪律（verify.sh 的 D1~D5 专门检查）：

| 允许 | 禁止 |
|---|---|
| 读 `volatile` 字段 | `ContentResolver.query()` |
| 浮点运算、比较 | 文件读写 |
| 改 hook 参数 | `Settings.put*` |
| 纯计数自增 | 字符串格式化、创建线程 |

配置读取、日志上报全在独立线程（1 秒一次），热路径只看内存里的快照。

---

## 4. 配置怎么从 App 走到 `system_server`

```
App（普通应用）                          system_server（模块）
  │                                          │
  ├─ 写自己的 SharedPreferences               │
  │    getSharedPreferences("fakedc_cfg")    │
  │         ↓                                │
  │   notifyChange(uri)   ← 一条变更信号       │
  │                                          │
  └─ CfgProvider（只读 ContentProvider）  ←───┘ 每秒 / 收到信号时 query
       authority: com.lumen.fakedc.cfg
```

**为什么不用别的通道**（都试过）：

| 通道 | 为什么不work |
|---|---|
| `Settings.System` 自定义 key | Android 13+ 普通应用写入直接抛 `IllegalArgumentException` |
| `RemotePreferences`（libxposed 提供） | 实测只在 LSPosed 自己的 SQLite 里落盘，模块侧读到的是另一套值 |
| 文件 | 需要额外权限，且跨用户/跨应用目录不可靠 |

`SharedPreferences`（应用私有，一定能写）+ 只读 `ContentProvider`（`system_server` 一定能读）
是唯一在普通权限内跑通的组合。

---

## 5. 单位换算（最容易搞错的一处）

有三个不同的"亮度"：

```
滑块 20%  ──×0.5──▶  framework 0.10  ──系统映射表──▶  HAL 1638  ──▶  面板
   用户尺子          Cfg.MIN_BRIGHT × BRIGHT_FULL      系统负责
```

| 量 | 范围 | 谁产生 |
|---|---|---|
| 滑块百分比 | 0 ~ 100% | 用户 |
| `Cfg.MIN_BRIGHT` | 0 ~ 1（**就是那个百分比**） | App 写入 |
| framework 亮度 | 0 ~ `BRIGHT_FULL`(本机 0.5) | 模块换算后拿去比对/钉住 |
| HAL 值 | 0 ~ 16383（本机） | 系统的 `DisplayDeviceConfig` 映射表 |

- 存的值 = 界面显示的值 = 日志里的 `min=`，三者永远一致；
- **换机型只改 `BRIGHT_FULL`**（滑块满刻度对应多少 framework 亮度），HAL 是几千还是几万都无所谓——
  因为我们和系统用的是**同一张映射表**，比例关系恒成立。

标定方法：系统亮度拉满 → `dumpsys display | grep mBrightnessState` → 那个数就是满刻度。

---

## 6. 代价与边界（不是 bug）

| 现象 | 原因 |
|---|---|
| 暗部条带、深色糊成一片 | DC 是恒定电流，没有 PWM 那种时域抖动来抹平 8bit 量化台阶 |
| 极低亮度 + 下限很高 → 全黑 | 要压 20 倍时，内容低于 22/255 直接归零 |
| 画面偏白 | 面板在高背光下的 EOTF 漂移 + gamma 2.2 近似，用「画面补偿」现场标定 |
| 耗电/发热略增 | 面板常驻在较高背光 |
| 系统「超时渐暗」失效 | 我们拆掉了 DIM 机制（见 3.2） |

**"刚好不闪"就是最优解**，不是越高越好：

| 下限 | `t=3%` 时的增益 | 可用灰阶 |
|---|---|---|
| 10% | 0.58 | 148 级 |
| 30% | 0.35 | 90 级 |
| 60% | 0.26 | 65 级 |
| 100% | 0.20 | 52 级 |

越往上，防闪更好、条带更重。用摄像头找到"条纹刚好消失"的那一档，就是这台机器的正确值。

---

## 7. 怎么读那行日志

```
v276 ok | en=1 ACTIVE min=12% pin=0.060 comp=0.909 |
calls=195 pins=166 st=203 bk=194 dim=38 |
user=0.025 tgt=0.020 gain=0.561 uAge=93s mtx=669 | 16:06:26
```

| 字段 | 含义 | 怎么用 |
|---|---|---|
| `v276` | **dex 里实际跑的版本** | 与已装版本不一致 → 装的旧包，或没重启 |
| `en` / `ACTIVE` | 总开关 / 是否在干活 | `IDLE(disabled)` = 开关关着；`IDLE(screenoff)` = 息屏 |
| `min` | 读到的下限百分比 | 与界面显示不一致 → 配置没送到 |
| `pin` | **最近一次真正钉进框架的值** | 与 `min×0.5` 不等 → 面板没按预期钉 |
| `comp` | 当前画面补偿 | 与设置不符 → 补偿没生效 |
| `calls` / `pins` | 钩子调用数 / 钳位次数 | `calls` 不涨 = 模块没被调用 |
| `user` / `uAge` | 用户意图亮度 / 多久没变 | `uAge` 一直涨 = **读错了字段**（踩过） |
| `tgt` / `gain` | 目标亮度 / 矩阵增益 | 手算 `(tgt/min×0.5)^(1/2.2)` 应与 `gain` 吻合 |
| `mtx` | 矩阵写入次数 | 不涨 = 矩阵线程没跑 |

---

## 8. 代码地图

| 文件 | 职责 | 依赖 |
|---|---|---|
| `FakeDCModule.java` | 模块主体：三个钩子 + 矩阵线程 + 配置读取 + 一行日志 | libxposed API 102 |
| `Cfg.java` | 配置/状态的**唯一契约**：key、默认值、单位、含义 | 无（纯常量） |
| `CfgProvider.java` | 只读 ContentProvider，把 App 的私有 SharedPreferences 暴露给 `system_server` | Android SDK |
| `SettingsActivity.java` | 极简界面：开关 + 两个滑块 + 数值输入 + 日志 | Android SDK |
| `Theme.java` | 配色 | 无 |
| `build.sh` | `javac → d8 → aapt2 → zip → zipalign → apksigner` | JDK/SDK |
| `verify.sh` | 57 项交付前自检（注入前提 / 配置通道 / 逻辑 / 热路径纪律 / UI / 卫生） | — |
| `crosscheck.sh` | 第二判官，15 项独立复算（dex 通道 / 打包字节 / 公式对拍） | — |

---

## 9. 想改的时候从哪下手

| 想做的事 | 改哪里 |
|---|---|
| 换机型 | `Cfg.BRIGHT_FULL`（唯一需要标定的常数） |
| 调压暗曲线 | `gainFor()`（先确认不是读到错的 `t`） |
| 加配置项 | `Cfg` 加 key + `CfgProvider` 加列 + UI 加控件（三处必须同时改） |
| 改日志字段 | `reportStatus()`（UI 只是原样显示，不会解析） |
| 支持 Android 新版本 | 先 `dumpsys display` / 查 `services.jar`（或 AOSP 源码）确认挂点签名有没有变 |
