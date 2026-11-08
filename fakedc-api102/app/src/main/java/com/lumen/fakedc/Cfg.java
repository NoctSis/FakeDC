package com.lumen.fakedc;

/**
 * v252: **配置与状态的唯一契约**（从 35 个 key 精简到 7 个）。
 *
 * ===== 为什么推倒重来 =====
 *
 * 用户原话：「前后端基本上都没有一个统一过，到底要哪些不要哪些，
 * 实在不行那就留最简单的，然后直接通过日志来实现查询」。
 *
 * 这是准确的批评。v251 之前这里堆了 35 个 key：
 *   · 4 个 Remote Preferences key（pref_*）
 *   · 4 个 Settings 镜像 key（COM_..._CFG_*）
 *   · 11 个回显 key（ECHO_*）
 *   · 3 个计数 key（STAT_*）
 *   · 4 个 UI 转写 key（UI_HAL / UI_LEVEL / UI_RBC_*）
 *   · 9 个杂项（HOOK_STATUS/SIG/ERROR、MODULE_ALIVE/PROC/LOG、
 *                HOOK_VER/BUILD_DATE、GROUP、FILE_NAME）
 *
 * 结果就是用户说的"没统一"：
 *   · v251 删掉 RBC 后，UI 还在读 UI_RBC_ACT / UI_RBC_LEVEL / ECHO_ACT
 *     —— 这三个值模块**已经永远不写了**，UI 却当成实时状态显示
 *   · 模块在写 ECHO_USER / ECHO_GAIN / ECHO_MPUSH
 *     —— UI **一个都不读**，写了等于扔进垃圾桶
 *   · 配置有两套 key（pref_* 走 Remote Preferences、COM_..._CFG_* 走 Settings），
 *     模块三级通道依次尝试，任何一级读到脏值都会造成"改了没用"
 *
 * ===== 新契约（v262 起：配置 3 个 + 状态 3 个）=====
 *
 * 配置 —— **唯一通道 CfgProvider**（v266 起）：
 *   UI 写进自己 app 的私有 SharedPreferences → 模块通过 ContentProvider.query 读。
 *   为什么不用 RemotePreferences：实测它只在 lspd 的 SQLite 里落盘，
 *   路径假设是错的；而 Android 13+ 又禁止 app 写自定义 Settings key
 *   （抛 IllegalArgumentException）。SharedPreferences + 只读 Provider 是唯一
 *   在普通应用权限内能写、且 system_server 能读的通道。
 *
 *   1. ENABLE        总开关（默认 {@link #ENABLE_DEFAULT}）
 *   2. MIN_BRIGHT    DC 区间下限（默认 {@link #MIN_BRIGHT_DEFAULT}）
 *   3. GAMMA_COMP    画面补偿（默认 {@link #GAMMA_COMP_DEFAULT}）
 *
 * 状态 —— 模块写、UI 读（走 Settings.System，模块单方面写）：
 *   1. LOG           一行诊断日志（**所有**运行状态都在这行里）
 *   2. HOOK_VER      模块实际运行的版本号（分辨"装的新版/跑的旧版"）
 *   3. MODULE_ALIVE  模块是否被注入
 *
 * ===== 为什么诊断只留一行日志 =====
 *
 * 之前 11 个回显 key 的问题不是"信息不够"，而是**没有一个地方能一眼看完**：
 * 排障时要逐个 settings get，还容易漏、容易读到上一次的残留。
 * 现在模块把该说的全拼进 LOG 一行：
 *
 *   v276 ok | en=1 ACTIVE min=20% pin=0.100 comp=0.900 | calls=2463 pins=2117
 *           st=2465 bk=2462 dim=1109 | user=0.025 tgt=0.000 gain=0.106 uAge=192s mtx=3168 | 14:59:41
 *
 * 用户复制这一行就能回答"模块跑没跑、配置读到没有、压暗算到多少"。
 *
 * ⚠️ 这个类**不依赖任何 Xposed API**，UI 进程与 system_server 都能安全加载。
 */
public final class Cfg {

    // ==================== 配置（Remote Preferences，UI 写 → 模块读） ====================

    /**
     * v256: **配置通道改回 Remote Preferences**（libxposed 官方通道）。
     *
     * 为什么必须改（2026-11-01 真机截图实证）：
     *   v252 把通道定成 Settings.System，注释里写"UI 写、模块读"——
     *   但 **app 侧根本写不进去**。Android 13+ 起普通应用写自定义 key
     *   直接抛 IllegalArgumentException，用户每拖一次滑块就弹
     *   「写入失败: IllegalArgumentException」，改配置这条链路是断的。
     *   模块能写（system_server 权限），但那是模块写状态，不是用户改配置。
     *
     * 现在：
     *   UI 侧  XposedServiceHelper → service.getRemotePreferences(GROUP)  **可写**
     *   模块侧  XposedInterface.getRemotePreferences(GROUP)               只读
     *
     * 状态（LOG / HOOK_VER / MODULE_ALIVE）仍然走 Settings.System ——
     * 那是"模块单方面写、UI 单方面读"，不涉及 app 写入，没有问题。
     */
    public static final String GROUP = "fakedc_cfg";

    /** 总开关。int 0/1 */
    public static final String ENABLE = "COM_LUMEN_FAKEDC_CFG_ENABLE";

    /**
     * v276: {@link #ENABLE} 的**唯一默认值**。
     *
     * 之前四处各写一个裸字面量 `getInt(Cfg.ENABLE, 1)`。虽然当时都是 1、没出过事，
     * 但 2026-11-02 那次「界面显示 2%、模块跑 10%」就是「同一语义多处各写一份
     * 默认值」造成的 —— 默认值也是配置的一部分，必须单点定义。
     */
    public static final int ENABLE_DEFAULT = 1;

    /**
     * DC 区间下限。**float 0~1，就是用户在 UI 上看到的那个百分比**。
     *
     * 语义（用户 2026-11-02 定）：「不应该是我设置定哪里，你就定在哪个位置吗」。
     * 填 0.60 = 面板背光永远不低于**亮度滑块 60% 那个位置**。
     * 低于它的亮度全部由矩阵压暗模拟；高于它的照常透传，最高亮度不受影响。
     *
     * ⚠️ 它**不是** framework 亮度值。framework 侧要乘 {@link #BRIGHT_FULL}。
     * 这样存值 == UI 显示值 == 日志显示值，三者永远不会各说各话。
     */
    public static final String MIN_BRIGHT = "COM_LUMEN_FAKEDC_CFG_MIN_BRIGHT";

    /**
     * v270: {@link #MIN_BRIGHT} 的**唯一默认值**，三处共用。
     *
     * 真机实证（2026-11-02 13:58）：键没写过时，UI 用 0.02、Provider 用 0.1、
     * 模块兜底用 0.1 —— 界面显示「2%」而模块跑 10%，又是一次「显示值≠生效值」。
     * 默认值必须只有一处定义，否则一定各说各话。
     *
     * 0.20 = 面板钉在**亮度滑块 20% 的位置**（= framework 亮度 0.10）。
     * 这是用户 2026-11-08 实测的「刚好不闪」起点，作为出厂值。
     * 但它**不是每台机器的通用值**：正确值是本机 DC 区间的下沿 ——
     * 低了会闪，高了白吃灰阶（条带更重、暗部更糊），只能逐台用摄像头找。
     */
    public static final float MIN_BRIGHT_DEFAULT = 0.20f;

    /**
     * v271: 画面补偿，float 0.5~1.5，默认 1.0。**直接乘到矩阵增益上**。
     *
     * 用户 2026-11-08 反馈：「软件似乎导致有些偏白了」。
     * 矩阵本身是纯标量 g,g,g、无偏移（不会抬黑），所以"偏白"来自
     * 面板在高背光下的 EOTF 漂移 + gamma 2.2 近似 —— 都是**面板特性**，
     * 没法用公式算准，只能现场标定。
     *
     * 往 <1 调画面变暗（治偏白/偏亮），往 >1 调变亮。
     */
    public static final String GAMMA_COMP = "COM_LUMEN_FAKEDC_CFG_GAMMA_COMP";

    public static final float GAMMA_COMP_DEFAULT = 1.0f;

    /**
     * 亮度滑块满刻度对应的 framework 亮度值。**本机 = 0.5**。
     *
     * 取证（三条互相独立，2026-11-02）：
     *   1. `dumpsys display`：`hbmMax=0.499938`，且 `brt=0.49993792(100.0%)`
     *      —— 框架自己就把 0.5 显示成 100%。
     *   2. sysfs：`factory_max_brightness=8191`、`max_brightness=16383` → 0.5。
     *   3. 实测：`screen_brightness=60/255` → tgt=0.116 ≈ 0.5 × (60/255)。
     *
     * 也就是说 framework 亮度 0~0.5 才是用户滑块的正常行程，0.5~1.0 是 HBM。
     * 换设备时改这一个常量即可（不是让用户去填 HAL 1312/8191）。
     */
    public static final float BRIGHT_FULL = 0.5f;

    // v265: MAX_DIM（压暗强度）**已删除**。
    // 补偿量由 t/minB 唯一决定，不再需要用户调一个"压多少"的旋钮。
    // 那个参数默认 0，等于"设了 DC 区间下限、功能却默认不工作"——
    // 用户 2026-11-01 直接指出来：「这不是 bug 吗」。

    // v262: DISABLE_OFF（息屏不干预）**已删除**。
    // 它从来没被任何代码读过（cDisableOff 只赋值不使用），而且语义与
    // idleReason() 里的 `state != ON → 不干预` 完全重叠 —— 灭屏本来就不干预。
    // 用户原话：「息屏不干预直接删了吧，你不是说代码里面已经有了吗」。
    // 留着一个永不生效的开关，只会让人以为"调了没反应"。

    // ==================== 状态（模块写 → UI 读） ====================

    /**
     * 一行诊断日志。**所有**运行状态都写在这里，不再散落成十几个 key。
     *
     * 格式（固定字段顺序，便于人眼和脚本都能读）：
     *   v{ver} {ok|FAIL} | en={0|1} {ACTIVE|IDLE(原因)} min={x}% pin={f} comp={f} |
     *   calls={n} pins={n} st={n} bk={n} dim={n} |
     *   user={f} tgt={f} gain={f} uAge={n}s mtx={n} | {事件} | {HH:mm:ss}
     *
     * 各字段含义：
     *   en          模块读到的总开关
     *   ACTIVE/IDLE 模块此刻是否在干活（IDLE 会带原因：disabled / off / doze…）
     *   min         模块读到的下限百分比（用户尺子，不是 framework 值）
     *   pin         最近一次**实际钉进框架**的亮度（framework 值，0~0.5）
     *   comp        当前画面补偿
     *   calls       hook 被调用次数（证明真的在被调用）
     *   pins        背光被钳住的次数（证明低亮度分支真的走过）
     *   st/bk/dim   三个钩子各自的调用次数（requestDisplayState / setBacklight / shouldApply）
     *   user        用户意图亮度（跟随亮度条的通道）
     *   tgt         最近一次拿到的目标亮度（0~1，DPC 单位）
     *   gain        矩阵当前增益（1.0 = 不压暗，越小越暗）
     *   uAge        用户意图亮度有多久没更新（秒）—— 判断有没有读错字段
     *   mtx         矩阵写入次数（证明跟随线程真的在写，不是只算不写）
     */
    public static final String LOG = "COM_LUMEN_FAKEDC_LOG";

    /**
     * 模块实际运行的版本号（编译期由 BuildInfo.HOOK_VER 注入）。
     *
     * 存在的唯一理由：换 dex 必须重启才生效。没有这个值，UI 只能显示
     * "已装的版本"，而 dex 里跑的可能是旧的 —— 用户看到新版号却是旧行为，
     * 只会得出"改了没用"。
     *
     *   无此值        → 模块从未被注入
     *   值 != 已装版本 → dex 是旧的，重启后生效
     *   值 == 已装版本 → 真正跑的是当前版本
     */
    public static final String HOOK_VER = "COM_LUMEN_FAKEDC_HOOK_VER";

    /** 模块是否被注入（1 = onModuleLoaded 跑过了）。区分"没加载"和"加载了但 hook 失败" */
    public static final String MODULE_ALIVE = "COM_LUMEN_FAKEDC_MODULE_ALIVE";

    private Cfg() {}
}
