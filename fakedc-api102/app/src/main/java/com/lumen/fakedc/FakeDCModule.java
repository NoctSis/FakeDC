package com.lumen.fakedc;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * 伪 DC 调光 · 极简版（严格对齐原版 fakedc-upstream/Hook.kt 的全部行为）
 *
 * 设计原则（2026-10-23 事故后重写）：**只做原版做的三件事，一个不多。**
 *  原版 147 行；代码少 = 出事的面小。
 *
 * ===== v254 架构（两个钩子 + 一条矩阵腿）=====
 *
 *  钩子①（DC 本体 · 钳下限）—— 挂在**最下游**
 *    LocalDisplayAdapter$BacklightAdapter.setBacklight **只挂 5 参版 (FFFFZ)V**。
 *    4 参版 (FFFF)V 在本机全 dex **无调用点**（挂了是死代码，故不挂）。
 *    args[2] = 面板背光（两个重载、两条分支都落在 args[2]，已反汇编取证）；
 *    低于 minB 就钉到 minB，真实意图交给矩阵线程。
 *
 *  钩子②（治用户主诉 · 锁屏后亮度锁定）
 *    DisplayDimModifier.shouldApply(DisplayPowerRequest,int) → **恒返回 false**。
 *    policy=DIM 时系统用 max(min(b-0.04, dimConfig), minCfg) 把亮度压成
 *    **恒等于 dimConfig**（本机 8.54597E-4，与输入无关）—— 这就是"锁屏后
 *    亮度锁死在一个固定值"。shouldApply 是它唯一的开关，恒 false = 关掉。
 *    用「替换返回值」而不是改参数：不建状态、不写 Settings、零门控。
 *
 *  状态钩子（**不是**亮度钩子）
 *    requestDisplayStateLocked —— **只**记录 state 并维护 sIdleWhy。
 *    v254 起**不再在这里钳位**（钳位统一交给钩子①，单一职责、挂点最下游）。
 *
 *  3) 独立线程写 DisplayTransformManager 的 4242 槽位
 *     增益 g = (target/minB)^(1/2.2)，完全由模块控制。
 *
 * **RBC（reduce_bright_colors_*）整套已删除** —— 它在 A14+ 上会被系统复位、
 *   level 过高会触发 policy=DIM 把亮度锁死、AOD 期间还会误判。
 *   详见 REA-DC-知识库/06-决策记录.md D-015。
 *
 * 配置通道：**Settings.System 单一通道**（4 个 key，见 Cfg）。
 * 状态通道：**Cfg.LOG 一行日志**（模块每秒写，UI 原样显示）。
 * 不再有 Remote Preferences / 外部文件 / 多级回显 —— 多一条通道就多一处
 * "改了没用"的可能。
 *
 * 热路径纪律（血案教训，2026-10-23）：
 *  intercept() 是显示更新的热路径。**只允许**读内存、算数、改 hook 参数、
 *  调系统 Settings（原版就这么做，且只在低亮度时才写）。
 *  **绝不**做额外 IO / 反射 / 跨进程查询 —— 那会拖死 system_server（触摸失灵）。
 */
public class FakeDCModule extends XposedModule {

    private static final String TAG = "REA-DC";
    /** 本模块包名（配置文件路径用，必须与 AndroidManifest 一致） */
    private static final String PKG = "com.lumen.fakedc";

    /** 上报给 UI 的两个值（沿用原版 key） */
    // 上报给 UI 的 Settings key（v225.4 统一走 Cfg，消除两侧 key 漂移）

    /**
     * 系统 RBC 的两个 @hide key。
     *
     * v234 抽成常量：之前字面量在热路径里出现 6 次，改一处漏一处就会
     * 出现"某条分支清了 level 却没清 activated"这种最难查的状态残留。
     */
    // v251: RBC 两个 @hide key **已彻底删除**。
    // 全套 RBC 机制（activated/level）在 v243~v250 里制造了锁定、DIM 棘轮、
    // AOD 误判、闪烁等一连串问题，而它同时对压暗没有任何帮助。
    // 现在压暗只走 DTM 色彩矩阵 —— 完全由模块控制，系统不会覆盖。

    /** 原版 key 名（**不改**，保证与原版/legacy 配置互通） */
    // 配置 key：统一走 Cfg（v225.4 抽出，消除 UI 字面量与模块常量漂移的风险）
    private static final String K_ENABLE = Cfg.ENABLE;
    private static final String K_MIN_BRIGHT = Cfg.MIN_BRIGHT;   // float 0~1


    /**
     * v225.2: 热路径写入门控（防"每帧 binder"）。
     *
     * 事故教训：热路径里的 Settings 写是 binder 调用，每帧都写没必要且危险。
     * 现在只在值真变时写 —— 稳态下热路径**零 binder**。
     * （原版每帧都写也跑得动，但那是原版；我们出过卡死事故，从严。）
     */
    // v251: RBC 的整套写入门控（sAppliedOff/Level/Act/Min、sLastHal、
    // sLastLevel、sWasEnabled）全部删除 —— 这些状态机只是为了绕开
    // "RBC 写多了会被系统覆盖/打架"而存在的。不写 RBC 就不需要它们。
    /** 面板下限上报缓存（UI 显示用） */
    private static volatile float sLastHal = -1f;
    private static volatile boolean sInstalled = false;
    /** hook 安装失败原因（reportStatus 带进日志行） */
    private static volatile String sHookErr = null;
    /** v251: RBC 残留是否已清理（只在开机后清一次，之后永不触碰） */
    private static volatile boolean sRbcCleared = false;
    // v254: AOD_FLOOR（数值型亮度阈值）**已删除**。
    //
    // 8.54597E-4 既是 DIM 的压制值，也是用户合法把滑条拉到底时的值
    // （dumpsys 03:772/776/780：policy=BRIGHT + user_set + brt=8.54597E-4）。
    // 阈值测的是「结果」不是「状态」，必然误伤 —— 用户拉到底会被判成
    // AOD 而关掉功能。准入判据只保留**一个**：state != ON(2) → 不干预。
    /**
     * v252.4: 用户亮度的**原始值**（0~1，未换算）与**上次变化时刻**。
     *
     * 用途：诊断「亮度被冻住」。如果 uAge 一直很大而 user 又明显偏低，
     * 就说明读到的是残留值（锁屏/AOD 写进去的），不是用户在拖滑块。
     * 这是「固定值锁定」最直接的证据 —— 不用再猜。
     */
    private static volatile float sUserRaw = -1f;
    private static volatile long sUserChangedAt = 0L;
    /**
     * v252.3: 屏幕是否亮着（最近一帧 requestDisplayStateLocked 的 state==ON）。
     *
     * 用户要求（2026-10-31）：「息屏了直接不生效，真正亮了才介入，
     * 先匹配亮度值，然后再锁 DC，避免突然的锁定导致亮度锁死」。
     */
    private static volatile boolean sScreenOn = false;
    /**
     * v252.3: **不生效的原因**（null = 正在生效）。
     *
     * 用户要求"这个区分要做好"。之前只有一堆数值（user/gain/tgt），
     * 出问题时看不出到底是"息屏"还是"总开关关了"。
     * 现在直接给结论：
     *   null        → ACTIVE，正在按规则工作
     *   "disabled"  → 总开关关
     *   "screenoff" → 息屏 / DOZE（含 AOD）
     * v254: 删掉原来的 "aod"（亮度阈值判据）—— 见上面 AOD_FLOOR 的说明。
     */
    private static volatile String sIdleWhy = "boot";
    /**
     * v237: **hook 行为计数**（热路径只做 volatile 自增，零 IO）。
     *
     * v236 教训：HOOK_STATUS=1 只证明"装上了"，回答不了"跑过没有、
     * 钉住过没有"。真机诊断卡在「无回显」时没有任何指标可用。
     * 计数由 fakedc-echo 线程低频刷进 Settings（变化才写）。
     */
    private static volatile long sHookCalls = 0;      // intercept 进入次数（仅 state=ON）
    private static volatile long sHookPins = 0;       // 实际钉住面板次数
    /**
     * v253: **两条 hook 分别计数**（诊断"到底哪条在起作用"的唯一手段）。
     *
     *   st  = requestDisplayStateLocked
     *        v254 起**只记 state**（状态通道，不是亮度通道：实测把参数改成
     *        minB=0.9，brt 一点不变）。钳位已从这里搬走。
     *   bk  = LocalDisplayAdapter$BacklightAdapter.setBacklight
     *        **真正的背光出口**（2026-10-31 反汇编 services.jar 定位）：
     *        setBacklight(sdrBacklight, sdrNits, backlight, nits[, galleryHdrBoost])
     *        两个分支（LogicalLight.setBrightness / SurfaceControl）
     *        用的都是第 3 个参数，且全 dex 只有这一处调背光。
     *        v254：只挂 5 参版（4 参版全 dex 无调用点）。
     *   dim = DisplayDimModifier.shouldApply
     *        v254 新增（钩子②）。每次系统问"要不要压 DIM"都会进这里；
     *        计数在涨说明 policy=DIM 真的发生过 —— 这是"锁屏后亮度锁死"
     *        是否复现的直接指标。
     */
    private static volatile long sCallsState = 0;
    private static volatile long sCallsBk = 0;
    private static volatile long sCallsDim = 0;
    private static volatile float sHookLastRaw = -99f;// 最近一次原始 target

    /**
     * v243: **用户意图亮度**（DPC 单位，0 ～ ~0.5），由 echo 线程每 tick 刷新。
     *
     * 为什么必须单独存一份（2026-10-30 真机实证，锁死亮度的真凶）：
     *   模块原来用 hook 参数 `requestDisplayStateLocked(brightness…)` 当用户意图。
     *   但一旦 RBC 进入 policy=DIM，DPC 下发的 brightness **恒为最小值
     *   8.54597E-4**（与滑块位置无关，实测 level 42→20→10→5→1 全程不变）。
     *   于是 lv = (1 - 8.5E-4/0.1) × maxDim 恒等于 maxDim，
     *   rbcStrength 恒定不变 ⇒ 滑块怎么拖屏幕都不变 ⇒ 用户说的"锁定亮度"。
     *
     *   Settings.System.screen_brightness / Secure.brightness_float 在 DIM 下
     *   **仍然忠实记录滑块位置**，所以它是唯一可用的意图源。
     *
     * 单位换算：本机实测用户设 0.5 → DPC 下发 0.2503963
     * （DPC 的满量程是 hbmMax≈0.499938，即真实最大亮度），
     * 故 USER_TO_DPC ≈ 0.5。用 0.5 近似，误差 0.01%，可忽略。
     */
    private static volatile float sUserBright = -1f;

    /** 用户单位(0..1) → DPC 单位。本机 hbmMax=0.499938，取 0.5。 */
    private static final float USER_TO_DPC = 0.5f;

    // ===== v248: 矩阵压暗（跟随式）=====
    //
    // 与系统 RBC 完全独立：用 DisplayTransformManager 的 4242 槽位写标量矩阵。
    // 这样不依赖 activated（本 ROM 上它会被系统复位成 0），也不会触发 DIM。
    private static final int MY_MATRIX_LEVEL = 4242;
    /** sRGB 传输函数指数：屏幕亮度在感知上接近 gamma 2.2 */
    private static final float GAMMA_EXP = 2.2f;
    /** DTM 实例（构造函数捕获；可能为 null —— 那就只能靠钳位） */
    private static volatile Object sDtm = null;

    /** 上次写进去的增益（-1 = 还没写过） */
    private static volatile float sLastGain = -1f;

    /**
     * v257: **缓存的 setColorMatrix 反射句柄**。
     *
     * 旧实现每写一次矩阵就 `getDeclaredMethods()` 全类遍历一遍再比对签名，
     * 单次开销比真正写矩阵还大 —— 这也是"跟随线程不敢跑快"的隐性原因之一。
     * 解析一次缓存起来，后面直接 invoke。
     */
    private static volatile java.lang.reflect.Method sSetColorMatrix = null;
    /** 上次写入时间（ms），用于 80ms 心跳补写 */
    private static volatile long sLastGainMs = 0L;
    /** 当前是否处于"我们改了矩阵"的状态（用于退出时复位） */
    private static volatile boolean sMatrixActive = false;
    /** 诊断：矩阵写入次数 / 当前增益 / 当前目标 */
    private static volatile long sMatrixPushes = 0;
    /**
     * v248: 用户意图亮度（DPC 单位），由热路径每帧写入，矩阵线程读取。
     *
     * 热路径只做一次 float 赋值（零开销、零 binder），真正算增益/写矩阵
     * 都在 60ms 周期的线程里 —— 这样既跟得上滑块，又不会打断合成。
     */
    private static volatile float sTargetBright = -1f;
    // 已上报值（变化才写，避免 echo 线程每秒 3 次 binder）
    private static volatile long sRepCalls = -1;
    private static volatile long sRepPins = -1;
    private static volatile float sRepRaw = -99f;
    /** v248: 矩阵增益 / 写入次数的上报缓存 */
    private static volatile float sRepGain = -99f;
    private static volatile long sRepMPush = -1;

    /**
     * v252: **配置快照**（热路径只读这里，保证零 IO）。
     *
     * 唯一来源：Settings.System 的 4 个 key（Cfg.ENABLE / MIN_BRIGHT /
     * MAX_DIM），由 echo 线程每秒刷新。
     *
     * 为什么热路径不能直接读：Settings 读虽然同进程，但仍要走
     * ContentResolver 的锁。拖滑块时 hook 每帧都进，这里做 IO 会
     * 打断显示更新（v221 卡死事故的形态）。所以只读 volatile 快照。
     */
    private static volatile boolean sCfgReady = false;
    private static volatile boolean cEnable = true;
    private static volatile float cMinBright = 1.0f;
    /** 配置实际来源（诊断展示：remote / settings / file / default） */
    private static volatile String cSource = "none";
    /** 上次快照的原始串（变了才重新解析，避免每轮都做字符串比较开销） */
    private static volatile String cRawKey = "";

    /**
     * v269: 配置"脏"标记 —— UI 发了变更信号才重读，不再每秒盲读。
     * 由 ContentObserver 置位，echo 线程消费。初始 true 保证开机读一次。
     */
    private static volatile boolean sCfgDirty = true;
    private static volatile boolean sCfgObserverDone = false;

    /** v271: 画面补偿（乘到增益上），治用户反馈的「偏白」 */
    private static volatile float cGammaComp = 1.0f;
    /** v271: 最近一次实际钉住的目标值（诊断用，回答"面板到底钉在哪"） */
    private static volatile float sLastPin = -1f;

    /**
     * v234: **上报暂存区**。
     *
     * 事故原因（用户截图里「hook 状态: 模块未运行」的直接来源）：
     *   install() 在 onSystemServerStarting 阶段跑，那时
     *   ActivityThread.getSystemContext() 还返回 null（本文件
     *   onModuleLoaded 的注释里就写过这个坑）。旧的 reportHook/reportLog
     *   在 ctx == null 时直接 return —— **状态被静默丢弃**。
     *   于是 hook 明明装上了，UI 却一直显示「模块未运行」，
     *   用户据此以为模块没注入，实际是被上报通道吞了。
     *
     * 现在：先写内存（永不丢），再由 fakedc-alive 线程在 Context 可用后刷出。
     */
    private static volatile int sPendStatus = -1;      // -1 无 / 0 失败 / 1 成功
    private static volatile String sPendSig;
    private static volatile String sPendErr;
    private static final java.util.ArrayList<String> sPendLog = new java.util.ArrayList<String>();
    /** 有新的待刷内容。用脏标记而不是"刷一次就锁死"，因为 install() 会分多次上报 */
    private static volatile boolean sDirty = false;

    /** v234: 系统 Context 缓存 —— system_server 里是单例，首次解析后直接复用 */
    private static volatile Context sSysCtx;
    /** 解析失败后的冷却截止时间（避免热路径兜底调用每帧反射） */
    private static volatile long sCtxRetryAfter = 0L;

    /**
     * v234: getSurroundingThis 的反射结果缓存。
     *
     * 热路径每帧都会调 getCtx()，而旧实现每帧做 1 次 getDeclaredField +
     * 1 次 getMethod + 1 次 invoke。原版也这么干（XposedHelpers 内部有
     * 自己的缓存），但我们的 D 类纪律要求"热路径无反射"—— 静态检查脚本
     * 之前只扫 intercept 函数体，正好看不见这个跨函数的反射，属于
     * 「检查假通过」。现在按 Class 缓存 Field/Method，稳态零反射。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Object[]> sCtxCache =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Object[]>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {

        // v232: **模块活着标记** —— 一进入模块代码就写，早于 install()。
        //
        // 目的：区分「模块没被注入」和「注入了但 hook 失败」。
        // 旧版只有 HOOK_STATUS（install 里写），两种情况都显示"模块未运行"，
        // 用户不知道该去 LSPosed 里启用还是等修 ROM 兼容。
        //
        // onModuleLoaded 时 systemContext() 可能还不可用（实测 v220 踩过），
        // 所以起短线程重试，最多 10 秒。这是启动期一次性动作，非热路径。
        //
        // v234: 这个线程现在兼职两件事 —— 写 ALIVE 标记 + 把 install() 期间
        // **暂存在内存里**的上报刷进 Settings。旧实现在 ctx==null 时直接丢弃，
        // 导致 hook 装上了 UI 仍显示「模块未运行」（用户截图里的那条）。
        final String proc = safeProcName(param);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                boolean alive = false;
                for (int i = 0; i < 40; i++) {   // 20s 上限，install 通常 1s 内完成
                    try { Thread.sleep(500L); } catch (InterruptedException e) { return; }
                    Context c = systemContext();
                    if (c == null) continue;
                    try {
                        if (!alive) {
                            Settings.System.putInt(c.getContentResolver(), Cfg.MODULE_ALIVE, 1);
                            // v244: 版本身份（用户要求：能分辨"装的"和"跑的"）
                            // 读回校验 —— 写入被拒时 UI 会看到 null，而不是
                            // 看到一个"看起来对"的假值。
                            try {
                                Settings.System.putInt(c.getContentResolver(),
                                        Cfg.HOOK_VER, BuildInfo.HOOK_VER);
                            } catch (Throwable ignored) {
                            }
                            alive = true;
                            log(3, TAG, "MODULE_ALIVE written, proc=" + proc
                                    + " hookVer=" + BuildInfo.HOOK_VER);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "fakedc-alive");
        t.setDaemon(true);
        t.start();

        // v234: **配置回显线程**（1s 周期，常驻 daemon）。
        //
        // 为什么从热路径搬出来：旧实现把 echoConfig 放在 intercept 里，
        // 用户拖"面板下限"滑块时 min_bright 连续变化，每帧都要写 5 次
        // Settings（5 次 binder）—— 和 v221 卡死是同一类风险，只是没到
        // 那个量级。而回显纯属诊断用途，1s 粒度完全够用。
        // 读取的是框架维护的内存 prefs 对象，1s 一次开销可忽略。
        //
        // v237: **观测点** —— v236 真机上「无回显」却查不出原因，
        // 因为 echo 链路里所有异常都被静默吞掉（违反我们自己"失败可见"
        // 的纪律）。现在：
        //   ① 线程第一次真正跑通 → 写一条日志（UI 没看到它 = 线程没跑）
        //   ② 任何异常 → 原文写进模块日志（变化才写，不刷屏）
        //   ③ 顺带把 hook 行为计数刷出去（变化才写）
        Thread e = new Thread(new Runnable() {
            @Override public void run() {
                boolean firstOk = false;
                long lastReload = 0L;
                while (true) {
                    try { Thread.sleep(1000L); } catch (InterruptedException ie) { return; }
                    Context c = systemContext();
                    if (c == null) continue;
                    try {
                        // ===== v269: 配置改成「被唤醒才读」（用户 2026-11-02 提出）=====
                        //
                        // 用户原话：「读取到的值后续就不需要继续读取了，等待别人唤醒，
                        // 然后再正常读取，看看用户是否需要调整数值」。
                        //
                        // 之前每秒无条件 reloadCfg() = 每秒一次**跨进程 binder** 进到
                        // app 里；更糟的是 app 若已被系统回收，这次 query 会把它
                        // **重新拉起来** —— 模块等于在替 app 保活，正好是用户要避免的。
                        //
                        // 现在：UI 改完配置调 CfgProvider.notifyChanged() 发一条信号，
                        // 本线程注册的 ContentObserver 收到就置 sCfgDirty，这里只在
                        // 脏了才真正 query。30s 兜底一次，防信号丢失导致"改了没用"。
                        ensureCfgObserver(c);
                        long now = SystemClock.uptimeMillis();
                        boolean due = sCfgDirty || (now - lastReload > 30_000L);
                        if (due) {
                            sCfgDirty = false;
                            lastReload = now;
                            reloadCfg(c);
                        }

                        // ===== v251: RBC 残留**一次性**清理 =====
                        //
                        // 本版起完全不碰 RBC，但设备上可能还有旧版留下的
                        // activated=1 / level=37（实测 2026-10-31 19:44 就是
                        // act=1 level=37）。不清掉的话，系统会一直按 DIM 走，
                        // 表现为"明明删了 RBC 亮度还是被接管"。
                        //
                        // 只在开机后执行一次，之后**永不触碰** —— 这是整个模块
                        // 唯一一次写 reduce_bright_colors_*，且写入值恒为 0。
                        if (!sRbcCleared) {
                            try {
                                android.content.ContentResolver cr = c.getContentResolver();
                                Settings.Secure.putInt(cr, "reduce_bright_colors_level", 0);
                                Settings.Secure.putInt(cr, "reduce_bright_colors_activated", 0);
                                sRbcCleared = true;
                                reportLog("v251: RBC 残留已清零（一次性，之后不再触碰）");
                                sDirty = true;
                            } catch (Throwable ignored) {
                            }
                        }

                        // v243: 用户意图亮度（DPC 参数在部分状态下不可信，靠这条接力）
                        refreshUserBright(c);
                        // v252: 所有状态压成一行日志（唯一的诊断输出）
                        reportStatus(c);
                    } catch (Throwable t) {
                        String m = "echo tick 失败: " + t.getClass().getSimpleName()
                                + ": " + t.getMessage();
                        if (!m.equals(sLastTickErr)) {
                            sLastTickErr = m;
                            reportLog(m);
                            sDirty = true;
                        }
                    }
                }
            }
        }, "fakedc-echo");
        e.setDaemon(true);
        e.start();

        // ===== v248: 矩阵跟随线程（60ms 周期）=====
        //
        // 为什么不放在 echo 线程（1s）：压暗要跟着滑块走，1s 一拍会明显
        // "阶梯感"。但也不能放热路径 —— requestDisplayStateLocked 每帧
        // 都会进，在里面做反射 + 写矩阵会打断 SurfaceFlinger 合成（整机闪）。
        //
        // 折中：热路径只把目标值存进 volatile（零开销），本线程按 60ms
        // 取出来算增益并写矩阵。60ms ≈ 16fps，肉眼已足够连续；
        // 再叠加 pushMatrix 内部的 tol + 80ms 心跳，稳态几乎不写。
        Thread mtx = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    // v257: 60ms(16fps) → 16ms(约 60fps)。
                    //
                    // 用户实测反馈"亮度调整一顿一顿、没有原生的线性"——
                    // 16fps 的采样率肉眼就能看出台阶。反射句柄已缓存，
                    // 单拍开销降到一次 invoke，才敢跑到 60fps。
                    try { Thread.sleep(16L); } catch (InterruptedException ie) { return; }
                    try {
                        if (sDtm == null) continue;
                        // ===== v252.1: 增益的输入必须是**用户意图**，不能是 hook 参数 =====
                        //
                        // 真机实证（2026-10-31 20:13，v252 装机后）：
                        //
                        //   SET=0.50 → tgt=0.239 gain=1.000   正常
                        //   SET=0.25 → tgt=0.004 gain=0.290   ← target 突降！
                        //   SET=0.15 → tgt=0.000 gain=0.114   ← 增益 0.11 = 几乎全黑
                        //
                        // `sTargetBright` 记的是 hook 收到的 brightness，而它会
                        // 被系统改写（屏幕超时进 DIM 时降到 8.5E-4≈0，矩阵自己
                        // 压暗后系统也可能再调整）—— 于是形成正反馈：
                        //   压暗 → 屏幕变暗 → target 变小 → 增益更小 → 更暗 → …
                        // 用户观感就是"反复滑会越来越黑，最后跟熄屏一样"。
                        //
                        // 修法：优先用 sUserBright（滑块位置换算来的，系统改不了它），
                        // 只有拿不到时（开机头 1s，值 -1）才退回 hook 参数。
                        // v256: **改回用 sTargetBright**（hook 收到的实时亮度）。
                        //
                        // v252.1 曾改用 sUserBright（读 brightness_float），理由是
                        // 「target 会被 DIM 改写 → 压暗→target 更小→更暗」的正反馈。
                        // 但那个正反馈的**根因是 DIM 压制**，而 v254 已经把
                        // DisplayDimModifier.shouldApply 钉成 false —— 源头没了。
                        //
                        // 反过来 brightness_float 在本机**不跟随实时拖动**：
                        //   2026-11-01 实测 SET 0.80/0.30/0.06 →
                        //   tgt = 0.400 / 0.150 / 0.030（完美跟随）
                        //   而 float 恒为 0.05、uAge 从 52s 一路涨到 346s（纹丝不动）
                        // 后果：gain 恒 0.532 —— 高亮度被压暗 47%（用户："最高不够亮"），
                        // 低区不再变化（用户："10 以下滑不动"）。两个症状同一个根。
                        float t = (sTargetBright > 0f) ? sTargetBright : sUserBright;
                        float minB = minBNorm();   // DC 区间下限（framework 单位）
                        // 息屏 / 总开关关 → 不压暗，并复位矩阵。
                        // v254: 与热路径**同一套**准入判断（只剩 state != ON 一条）
                        String why2 = idleReason(sScreenOn);
                        if (why2 != null) {
                            sIdleWhy = why2;
                            resetMatrix();
                            continue;
                        }
                        float g = gainFor(t, minB);
                        // v271: 画面补偿（治「偏白」）。直接乘，>1 变亮 / <1 变暗。
                        g *= cGammaComp;
                        if (g > 1f) g = 1f;
                        pushMatrix(g);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "fakedc-matrix");
        mtx.setDaemon(true);
        mtx.start();
    }

    /** v232: 取进程名（写诊断用，取不到不影响主流程） */
    private static String safeProcName(ModuleLoadedParam p) {
        try {
            return String.valueOf(p.getProcessName());
        } catch (Throwable t) {
            return "?";
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            install(param.getClassLoader());
        } catch (Throwable t) {
            log(6, TAG, "install(sss) failed: " + t);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        try {
            if ("android".equals(param.getPackageName())) {
                install(param.getClassLoader());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        try {
            if ("android".equals(param.getPackageName())) {
                install(param.getDefaultClassLoader());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * v226: hook 安装 —— **多签名兼容 + 失败可见**。
     *
     * 为什么改（2026-10-25 静态验证发现）：
     *   本地 miui-services.jar（10-17 提取）里三个 hook 目标**全部不存在**，
     *   而 AOSP services.jar 里 requestDisplayStateLocked 有 14 次。
     *   无法确定用户当前 ROM 的框架里方法叫什么/什么签名 ——
     *   **写死签名 = ROM 一改就静默失效**（旧版就是这样，失败只打
     *   LSPosed 日志，用户根本看不到）。
     *
     * 现在：
     *   1. **挂全部同名重载**（对齐原版 XposedBridge.hookAllMethods）
     *   2. 结果与失败原因写入 Settings.System（UI 直接读，失败一眼可见）
     *
     * v234 修正（旧实现的致命选法）：旧版"4参优先 → 3参 → 任意，只 hook 一个"。
     * 原版是 hookAllMethods，3 参、4 参（Android 14 起带 DisplayOffloadSession）
     * 全挂。如果 ROM 上两个重载都存在而实际调用的是我们**没挑中**的那个，
     * 现象就是「UI 显示已装上 ✓、但完全没效果」—— 这种失败最难查，
     * 因为它的一切外部指标都是"正常"。
     */
    private void install(ClassLoader cl) {
        if (sInstalled) return;

        boolean ok = false;
        StringBuilder err = new StringBuilder();

        // ---- 0) requestDisplayStateLocked（**主 hook**，v236 定级）----
        //
        // 依据（2026-10-30 从用户提供的真机 services.jar 反汇编取证，字节码级）：
        //   · 签名 (I,F,F,DisplayOffloadSessionImpl)->Runnable，被
        //     DisplayManagerService.updateDisplayStateLocked / requestDisplayPower
        //     调用 —— 每次亮度变化必经，参数 arr[1] 就是亮度 0~1。
        //   · 正常亮度路径：requestDisplayStateLocked → $1.setDisplayBrightness
        //     → brightnessToBacklight(本 ROM 已反汇编证实是**恒等函数**，纯算术
        //     无 invoke) → BacklightAdapter.setBacklight。
        //     所以「亮度值 0~1 = 背光值」，把 arr[1] 钉成 minBright 就是直接
        //     钉住面板背光下限，无中间曲线。
        //   · RBC 压暗的写入（SEC_LEVEL/ACT）也在这里做 —— 系统的
        //     handleRbcChanged（已证实存在于本 ROM）会读到并自己重算 nits。
        try {
            Class<?> cls = Class.forName(
                    "com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice", false, cl);
            int nHooked = 0;
            StringBuilder sigAll = new StringBuilder();
            for (Method m : cls.getDeclaredMethods()) {
                if (!"requestDisplayStateLocked".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 2 || pt[0] != int.class || pt[1] != float.class) continue;
                m.setAccessible(true);
                Executable e = (Executable) (Object) m;
                try { deoptimize(e); } catch (Throwable ignored) {}
                hook(e).intercept(new DisplayStateHooker());
                nHooked++;
                if (sigAll.length() > 0) sigAll.append(" + ");
                sigAll.append(pt.length).append("参");
                log(3, TAG, "hooked requestDisplayStateLocked [" + pt.length + " args]");
            }
            if (nHooked == 0) throw new NoSuchMethodException(
                    "类存在但没有 requestDisplayStateLocked(int, float…) 方法（MIUI 可能改名/移除）");
            String sig = sigAll.toString();
            reportHook(true, sig, null);
            reportLog("hook OK: requestDisplayStateLocked ×" + nHooked + " [" + sig + "]");
            ok = true;
        } catch (Throwable t) {
            String why = t.getClass().getSimpleName() + ": " + t.getMessage();
            err.append("requestDisplayStateLocked -> ").append(why);
            log(6, TAG, "hook requestDisplayStateLocked failed: " + why);
            reportHook(false, null, why);
            reportLog("hook FAIL: requestDisplayStateLocked -> " + why);
        }

        // ---- 1) getBacklightFromBrightness（**辅助**，只盖 HDR/图库路径）----
        //
        // v235 曾把它当主 hook，依据是「所有亮度下发的唯一必经之路」——
        // v236 反汇编证伪：正常路径根本不调它。全 dex invoke 扫描只找到 3 个
        // 调用者，全在 DisplayDeviceConfig 自己的 HDR 系方法里：
        //   getCineLookBrightness / getGalleryHdrBrightness / getHdrBrightnessFromSdr
        //
        // 仍保留：HDR 亮度也走这里换算成背光，挂上它可让 HDR 低亮度同样
        // 不低于面板下限，与主 hook 行为一致。它是纯函数（F->F），钉参数无副作用。
        try {
            Class<?> ddc = Class.forName(
                    "com.android.server.display.DisplayDeviceConfig", false, cl);
            Method chosen = null;
            for (Method m : ddc.getDeclaredMethods()) {
                if (!"getBacklightFromBrightness".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 1 && pt[0] == float.class
                        && m.getReturnType() == float.class) {
                    chosen = m;
                    break;
                }
            }
            if (chosen == null) throw new NoSuchMethodException(
                    "DisplayDeviceConfig 里没有 getBacklightFromBrightness(float)float");
            chosen.setAccessible(true);
            Executable e = (Executable) (Object) chosen;
            try { deoptimize(e); } catch (Throwable ignored) {}
            hook(e).intercept(new BacklightHooker());
            reportLog("hook OK: DisplayDeviceConfig.getBacklightFromBrightness(F)F (HDR辅助)");
            ok = true;
        } catch (Throwable t) {
            String why = t.getClass().getSimpleName() + ": " + t.getMessage();
            err.append("getBacklightFromBrightness -> ").append(why);
            log(6, TAG, "hook getBacklightFromBrightness failed: " + why);
            reportLog("hook FAIL: getBacklightFromBrightness -> " + why);
        }

        // ---- 1d) v254 钩子①**真正的背光出口**：BacklightAdapter.setBacklight ----
        //
        // 反汇编结果（2026-10-31，services.jar classes2.dex）：
        //   setBacklight(F sdrBacklight, F sdrNits, F backlight, F nits)        4 参 668322
        //   setBacklight(F sdrBacklight, F sdrNits, F backlight, F nits,
        //                Z galleryHdrBoost)                                     5 参 668395
        // 两个分支（LogicalLight.setBrightness / SurfaceControl）用的都是
        // 第 3 个 float = 面板背光。
        //
        // **只挂 5 参版**：4 参版 (FFFF)V 在本机**全 dex 无调用点**（死代码），
        // 挂它既无效又多一个热路径分支 —— 违背「最简单」。唯一 invoke
        // 是 670094/2ac110，走的就是 5 参版。
        try {
            Class<?> ba = Class.forName(
                    "com.android.server.display.LocalDisplayAdapter$BacklightAdapter", false, cl);
            int n = 0;
            for (Method m : ba.getDeclaredMethods()) {
                if (!"setBacklight".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                // 只认 5 参 (F,F,F,F,Z) —— 4 参版是死代码，不挂
                if (pt.length != 5) continue;
                if (pt[0] != float.class || pt[1] != float.class
                        || pt[2] != float.class || pt[3] != float.class
                        || pt[4] != boolean.class) continue;
                m.setAccessible(true);
                Executable eb = (Executable) (Object) m;
                try { deoptimize(eb); } catch (Throwable ignored) {}
                hook(eb).intercept(new BacklightAdapterHooker());
                n++;
            }
            if (n == 0) throw new NoSuchMethodException(
                    "BacklightAdapter 里没有 setBacklight(F,F,F,F,Z)");
            reportLog("hook OK: BacklightAdapter.setBacklight(FFFFZ)V ×" + n + "（背光出口·只挂5参）");
        } catch (Throwable t) {
            String why = t.getClass().getSimpleName() + ": " + t.getMessage();
            log(6, TAG, "hook BacklightAdapter failed: " + why);
            reportLog("hook FAIL: BacklightAdapter.setBacklight -> " + why);
        }

        // ---- 1e) v254 钩子②**锁屏后亮度锁定**：DisplayDimModifier.shouldApply ----
        //
        // 用户主诉：「锁屏直接解锁后，亮度被锁在一个差不多固定的值」。
        // 根因（2026-11-01 反汇编，work/分析/锁屏DIM机制-R2.md）：
        //   DisplayDimModifier.getBrightnessAdjusted = max(min(b - 0.04, dimConfig), minCfg)
        //   本机 dimConfig = 8.54597E-4（= 亮度下限）⇒ min() 对任意输入恒等于它
        //   ⇒ 输出与输入无关，恒 8.54597E-4（dumpsys 796/800 两行实证）
        //   shouldApply 判据 = request.policy == 2（C2:741744），是唯一开关。
        //
        // 做法：**恒返回 false**（替换返回值，不改参数）。
        // 不写 Settings、不建状态、不加门控 —— 返回 false 后亮度保持
        // updatePowerStateInternal 的原值，dwell/ramp/落 HAL 全走系统原路径。
        //
        // 副作用：同时取消「超时前屏幕逐渐变暗」的视觉效果（超时后直接灭屏）。
        // 这是已知取舍；若将来要保留渐暗，就在本钩子里加一个判断，不要动别处。
        //
        // 不写死签名：按「名 + 返回 boolean + 2 参」遍历（ROM 换了请求类
        // 的写法也能命中）。
        try {
            Class<?> ddm = Class.forName(
                    "com.android.server.display.brightness.clamper.DisplayDimModifier",
                    false, cl);
            int nDim = 0;
            for (Method m : ddm.getDeclaredMethods()) {
                if (!"shouldApply".equals(m.getName())) continue;
                if (m.getReturnType() != boolean.class) continue;
                if (m.getParameterTypes().length != 2) continue;
                m.setAccessible(true);
                Executable ed = (Executable) (Object) m;
                try { deoptimize(ed); } catch (Throwable ignored) {}
                hook(ed).intercept(new DimModifierHooker());
                nDim++;
            }
            if (nDim == 0) throw new NoSuchMethodException(
                    "DisplayDimModifier 里没有 shouldApply(?,2参)Z");
            reportLog("hook OK: DisplayDimModifier.shouldApply ×" + nDim + "（恒 false，取消锁屏 DIM）");
        } catch (Throwable t) {
            String why = t.getClass().getSimpleName() + ": " + t.getMessage();
            log(6, TAG, "hook DisplayDimModifier failed: " + why);
            reportLog("hook FAIL: DisplayDimModifier.shouldApply -> " + why);
        }

        // ---- 2) RBC 相关方法：v235 起**不再 noop** ----
        //
        // 原版（Android 12~13）把 applyReduceBrightColorsSplineAdjustment /
        // handleRbcChanged 挂成返回 null，目的是「别让系统覆盖我写进去的
        // reduce_bright_colors_level」。
        //
        // 但 AOSP 从 Android 14 起把 RBC 改成 **nits 体系**：
        //   handleRbcChanged → mCdsi.getReduceBrightColorsAdjustedBrightnessNits()
        //                    → recalculateSplines(...)
        // 也就是说「系统应用 RBC」正是压暗生效的唯一通道。把它 noop 掉，
        // 等于我们写了 level 却亲手拦住了唯一会读它的地方 —— 屏幕当然不暗。
        //
        // 现在的做法：完全交给系统。我们只负责
        //   ① 把面板钉在下限（backlight hook）
        //   ② 往 Secure 里写 activated + level（系统 ColorDisplayService
        //      监听到就自己重算 spline）
        // 不再拦任何系统方法，符合「按系统来」这一条。
        // （若将来确认需要抑制，再加回来时必须先真机验证，不能凭原版经验。）

        // ---- 3) v248: DisplayTransformManager 矩阵压暗（跟随式） ----
        //
        // 【为什么必须加这一层 —— 2026-10-31 真机实证】
        //
        // 本 ROM 上 RBC 走不通：
        //   · activated 写进去立刻被系统复位成 0（pkg:android 改回）
        //   · dumpsys mIsRbcActive=false ⇒ rbcStrength 只是预估值，不生效
        //   · 而 activated=1 又会触发 policy=DIM 单向棘轮（v243 实证）
        // 结论：**不能靠系统 RBC 做压暗**。
        //
        // 用户要的语义（原话）：
        //   "背光停在 DC 区间下限，我要更低就继续压暗，他要降低你跟随不就行了"
        //
        // 也就是 legacy 系一直在用的方案（03-代码实现.md「核心算法：矩阵压暗」）：
        //   t >= minB：面板 = t，增益 1.0，完全原生
        //   t <  minB：面板钉在 minB（不进 PWM），增益 g=(t/minB)^(1/2.2)
        //              t 越小 g 越小 ⇒ 跟随用户意图连续变暗
        //
        // 用 sRGB 反解（1/2.2）而不是线性比例，是因为屏幕亮度在感知上
        // 接近 gamma 2.2：线性压会让低端掉得太快，gamma 反解才是"看起来
        // 和原生调光一样自然"。
        //
        // 写入用 **4242 这个独立 level**，与系统自己的 RBC 矩阵并存不冲突。
        // 防闪靠两条（legacy v210/v211 的血泪）：
        //   · 变化够大才写（tol = max(8e-4, g*0.012)）
        //   · 80ms 心跳补写（系统切页面/重配显示时会清掉我们的矩阵）
        // 两条是「或」不是「且」—— 写成「且」会出现"既不写也永远不补"，
        // 系统清掉后再也补不回来，表现为白闪。
        try {
            Class<?> dtmCls = Class.forName(
                    "com.android.server.display.color.DisplayTransformManager", false, cl);
            // 3a: 从构造函数捕获实例（最可靠，legacy v109 验证过）
            for (java.lang.reflect.Constructor<?> ctor : dtmCls.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    hook(ctor).intercept(new DtmCtorHooker());
                } catch (Throwable ignored) {
                }
            }
            // 3b: 挂 setColorMatrix 做节流（防止系统高频重配打断合成 → 闪）
            int nCm = 0;
            for (Method m : dtmCls.getDeclaredMethods()) {
                if (!"setColorMatrix".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2 || pt[0] != int.class || pt[1] != float[].class) continue;
                m.setAccessible(true);
                Executable e = (Executable) (Object) m;
                try { deoptimize(e); } catch (Throwable ignored) {}
                hook(e).intercept(new ColorMatrixHooker());
                nCm++;
            }
            if (nCm == 0) throw new NoSuchMethodException("没有 setColorMatrix(int, float[])");
            reportLog("hook OK: DisplayTransformManager.setColorMatrix ×" + nCm
                    + "（v248 矩阵压暗：面板钉下限 + 增益跟随）");
            ok = true;
        } catch (Throwable t) {
            String why = t.getClass().getSimpleName() + ": " + t.getMessage();
            err.append("DisplayTransformManager -> ").append(why);
            log(6, TAG, "hook DisplayTransformManager failed: " + why);
            reportLog("hook FAIL: DisplayTransformManager -> " + why
                    + "（矩阵压暗不可用，只能钳位）");
        }

        if (ok) sInstalled = true;
        // 把完整失败原因也写进去（UI 显示用）
        if (err.length() > 0) sHookErr = err.toString();
    }

    /**
     * v226: 把 hook 结果写入 Settings.System —— **让失败对用户可见**。
     *
     * 旧版只在 logcat/LSPosed 日志里报 hook 失败，用户没电脑根本看不到，
     * 表现为「模块装了但完全没效果，且不知道为什么」。
     * 现在 UI 直接读这两个 key 显示。
     *
     * 注意：install() 不是热路径（只跑一次），这里做 IO 安全。
     */
    /**
     * v252: **一行状态日志** —— 模块唯一的诊断输出。
     *
     * 替代了 v230~v251 的整套机制：11 个回显 key + 3 个计数 key +
     * 暂存队列 + 合并日志 + 只保留最近 6 条。那套东西的问题是
     * **没有任何一个地方能一眼看完**：排障要逐个 settings get，
     * 还容易漏、容易读到上一次的残留。
     *
     * 现在每 1 秒写一行（1 次写，同进程内存操作，成本可忽略）：
     *
     *   v254 ok ACTIVE min=10% max=50 | calls=1234 pins=567 st=88 bk=90 dim=3 |
     *   user=0.150 tgt=0.150 gain=0.870 uAge=2s mtx=890 | 20:15:33
     *
     * 每个字段都能回答一个具体问题：
     *   ok/FAIL  模块装上了没有
     *   en/min/max  模块**实际读到的**配置（而不是 UI 以为写进去的）
     *   calls/pins  hook 真被调用了吗、钳位分支真走过吗
     *   st/bk/dim   三条钩子各自的调用次数（bk 涨 = 背光出口在跑；
     *               dim 涨 = 系统真的问过"要不要压 DIM"，即锁屏 DIM 复现过）
     *   tgt/gain    当前目标亮度与矩阵增益（gain<1 = 正在压暗）
     *   mtx         矩阵真被写了吗（不是只算不写）
     *   时间戳      模块还活着吗（不动就是停了）
     *
     * ⚠️ **观测字段的读法（2026-11-01 修正）**
     *   dumpsys 里的 `brt=` 与本行的 `tgt=` 都是**上游记录**：
     *   `brt=` 来自 BrightnessEvent.toString()（写在 requestDisplayStateLocked
     *   上游，C2:723204），`tgt=` 来自 requestDisplayStateLocked 的入参。
     *   **两者都不能用来判断下游是否生效** —— 下游改的是 setBacklight 的
     *   args[2]，上游那两个数不会因此变化。要看下游生效没有，看 `bk` 与
     *   `pins` 这两个计数，以及 dumpsys 的 `mBrightnessState` / `mState`。
     */
    private static volatile String sLastStatus = "";

    private static void reportStatus(Context c) {
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            String ts = new java.text.SimpleDateFormat("HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date());
            StringBuilder sb = new StringBuilder(160);
            sb.append('v').append(BuildInfo.HOOK_VER);
            sb.append(sInstalled ? " ok" : " FAIL");
            if (!sInstalled && sHookErr != null) sb.append("(").append(sHookErr).append(')');
            sb.append(" | en=").append(cEnable ? 1 : 0);
            sb.append(sIdleWhy == null ? " ACTIVE" : " IDLE(" + sIdleWhy + ")");
            sb.append(" min=").append(Math.round(cMinBright * 100)).append('%');
            sb.append(" pin=").append(sLastPin < 0f ? "-" : fmt3(sLastPin));
            sb.append(" comp=").append(fmt3(cGammaComp));
            sb.append(" | calls=").append(sHookCalls);
            sb.append(" pins=").append(sHookPins);
            sb.append(" st=").append(sCallsState);
            sb.append(" bk=").append(sCallsBk);
            sb.append(" dim=").append(sCallsDim);
            sb.append(" | user=").append(fmt3(sUserBright));
            sb.append(" tgt=").append(fmt3(sHookLastRaw));
            sb.append(" gain=").append(fmt3(sLastGain));
            long age = sUserChangedAt == 0L ? -1L
                    : (android.os.SystemClock.elapsedRealtime() - sUserChangedAt) / 1000L;
            sb.append(" uAge=").append(age < 0 ? "?" : age + "s");
            sb.append(" mtx=").append(sMatrixPushes);
            if (sLastEvent != null) sb.append(" | ").append(sLastEvent);
            sb.append(" | ").append(ts);
            String line = sb.toString();
            if (line.equals(sLastStatus)) return;
            Settings.System.putString(cr, Cfg.LOG, line);
            sLastStatus = line;          // 写成功才记，失败下一秒重试
        } catch (Throwable ignored) {
        }
    }

    /** 三位小数格式化（热路径不调用，只在 echo 线程里用） */
    private static String fmt3(float v) {
        if (v != v) return "?";
        int i = (int) (v * 1000f);
        return (i / 1000) + "." + String.format(java.util.Locale.US, "%03d",
                Math.abs(i % 1000));
    }

    /** v230: 回显用的上次值（只在变化时写） */
    private static volatile float sEchoMin = -1f;
    private static volatile int sEchoMax = -1;
    private static volatile int sEchoEn = -1;
    /** v235: 上次回显的配置来源（变了也要重写，否则 UI 看不到通道切换） */
    private static volatile String sEchoSrc = "";
    private static volatile int sEchoOff = -1;
    /**
     * v245: ECHO_USER / ECHO_TS 的独立缓存。
     *
     * 这两项必须和上面那组「配置量」分开：v243 把它们和配置写在同一个
     * early-return 之后，导致配置一稳定就再也不更新 —— 实测 ECHO_TS 停住、
     * 而 STAT_CALLS 还在涨，看起来像"线程死了"，其实是回显被 return 挡了。
     */
    private static volatile float sEchoUser = -99f;
    private static volatile long sEchoTs = 0L;
    /** v247: 上次回显的 RBC activated 实际读回值（变化才写） */
    private static volatile int sEchoAct = -2;
    /** v237: 上一条已上报的 echo 错误（变化才写，防每秒刷屏） */
    private static volatile String sLastTickErr = "";
    /** v237: 上一条已上报的配置读取错误（同上） */
    private static volatile String sLastCfgErr = "";

    /**
     * v237: **失败可见**。v236 的「无回显」之所以查不动，是因为
     * echo 链路上每一层 catch 都是 `catch (Throwable ignored) {}`
     * —— 异常被吞了，UI 上只留下一个笼统的"无回显"。
     * 现在所有诊断链路上的 catch 都过这里：原文进模块日志，
     * 内容变化才写（防每秒刷屏）。
     */
    private static void noteErr(String where, Throwable t) {
        String m = where + ": " + t.getClass().getSimpleName()
                + ": " + t.getMessage();
        if (m.equals(sLastCfgErr)) return;
        sLastCfgErr = m;
        reportLog("配置读取失败(" + m + ")");
        sDirty = true;
    }

    /** v237: 把 hook 行为计数刷进 Settings（变化才写，echo 线程专用）。
     *  v238: 同 echoConfig —— 先写成功、后提交缓存（v237 的先改后写
     *  顺序 bug 会让首次失败后永远不再重试）。 */
    /**
     * v252: **只记最后一条事件**，不写 Settings。
     *
     * 旧版把每条日志都写进 Settings 并保留最近 6 条 —— 那是"日志系统"，
     * 但排障真正需要的只是"现在什么状态 + 最近出过什么事"。
     * 事件直接挂在 reportStatus 那一行的尾部，看一行就够。
     */
    private static volatile String sLastEvent = null;

    private static void reportLog(String msg) {
        sLastEvent = msg;
        android.util.Log.i(TAG, msg);
    }

    /** hook 安装结果（写进 sInstalled / sHookErr，由 reportStatus 带出） */
    private static void reportHook(boolean ok, String sig, String why) {
        sInstalled = ok;
        sHookErr = ok ? null : (why == null ? "?" : why);
    }


    // ================= v235: 配置快照（三级通道） =================

    private static final java.util.concurrent.atomic.AtomicBoolean sCfgLoading =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 首次使用时加载一次配置；之后由 fakedc-cfg 线程周期性刷新。
     *
     * 热路径调到这里时最多发生**一次** binder（sCfgReady 之后立刻短路）。
     * v221 那次卡死是「每帧 query」，与"整个进程生命周期一次"完全不是一回事。
     */
    private static void ensureCfg() {
        if (sCfgReady) return;
        if (!sCfgLoading.compareAndSet(false, true)) return;
        try {
            Context c = systemContext();
            if (c != null) loadCfg(c);
        } catch (Throwable ignored) {
        } finally {
            sCfgLoading.set(false);
        }
    }

    /** 后台线程调用：强制重新读一次（配置可能被 UI 改了） */
    /**
     * v269: 注册配置变更观察者（只注册一次）。
     *
     * 收到 UI 的 notifyChanged() 就把 sCfgDirty 置位，echo 线程下一次醒来
     * 才真正 query。这样"配置读取"从**每秒一次跨进程调用**变成**只在你调了
     * 之后读一次**，app 进程也不再被模块每秒拉活。
     *
     * 兜底：即使信号丢了，echo 线程仍有 30s 一次的重读，不会出现"改了没用"。
     */
    private static void ensureCfgObserver(Context ctx) {
        if (sCfgObserverDone) return;
        try {
            android.net.Uri u = android.net.Uri.parse(
                    "content://com.lumen.fakedc.cfg/cfg");
            ctx.getContentResolver().registerContentObserver(u, true,
                    new ContentObserver(null) {
                        @Override public void onChange(boolean selfChange) {
                            sCfgDirty = true;
                        }
                    });
            sCfgObserverDone = true;
            reportLog("v269: 配置观察者已注册（事件驱动读取，不再每秒轮询）");
        } catch (Throwable t) {
            // 注册失败就退回慢速兜底（30s 一次），功能不受影响
            noteErr("cfg-observer", t);
        }
    }

    private static void reloadCfg(Context ctx) {
        try {
            loadCfg(ctx);
        } catch (Throwable ignored) {
        }
    }

    /**
     * v243: 刷新"用户意图亮度"（DPC 单位）。
     *
     * 只在 echo 线程调用（1s 一次），热路径零 binder。
     * v254：**唯一来源** = Secure.brightness_float（Android 13+ 有小数精度）。
     * System.screen_brightness（0..255 老接口）在本机是垃圾值通道，已彻底不再读。
     *
     * 注意：**不要**用 `cmd display get-brightness` 那条路——它反映的是
     * 实际背光（含 DIM 压制后的值），DIM 下恒为最小值，正是我们要绕开的坑。
     */
    /**
     * v254: **统一的准入判断** —— 钩子①与矩阵线程用同一套规则，且**只有一个判据**。
     *
     * 用户要求（2026-10-31）：
     *   「息屏了直接不生效，真正亮了才介入。先匹配亮度值，然后再锁 DC，
     *     避免突然的锁定导致亮度锁死」
     *
     * 判据：`state != ON(2) → 不干预`，其余情况一律干预。
     *
     * ===== 为什么只剩这一条（v254 删掉 AOD_FLOOR 数值阈值）=====
     * 8.54597E-4 既是 DIM 的压制值，也是用户合法把滑条拉到底时的值
     * （dumpsys 03:772/776/780：policy=BRIGHT + user_set + brt=8.54597E-4）。
     * 阈值测的是「结果」不是「状态」，必然误伤；而且换一个 dimConfig 的 ROM
     * 就完全挡不住。数值只能当**观测量**（日志里的 user/tgt），不能当准入。
     *
     * 保留 state 判据的理由（work/分析/背光出口-R2.md §4 结论 2）：
     * 到 setBacklight 共 4 条路径，A/C/D 三条显式要求 state==2，但路径 B
     * 与 `isAodNormalbrightness()/isFullAodState()` 为假这两处例外下，
     * 非 ON 状态仍可能落到 setBacklight —— 所以这道门不能省。
     *
     * @return null = 可以生效；否则返回不生效原因（日志直接显示这个词）
     */
    private static String idleReason(boolean screenOn) {
        if (!cEnable) return "disabled";
        if (!screenOn) return "screenoff";
        return null;
    }

    private static void refreshUserBright(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            float fromFloat = -1f;
            try {
                fromFloat = Settings.Secure.getFloat(cr, "brightness_float", -1f);
            } catch (Throwable ignored) {
            }

            // ===== v252.2: 取两者**较大**值（v245 的 min 是错的，它会锁死亮度）=====
            //
            // 用户实证（2026-10-31 20:22）：
            //   「锁定是在**锁屏直接解锁系统**的时候引发的，而且是一个
            //     差不多固定的值」
            //
            // 机制：锁屏时系统会把 brightness_float 改成锁屏亮度（很低），
            // 解锁后**两张表恢复不同步** —— 常见是 float 仍停在低值，
            // 而 screen_brightness（滑块直接写的那张）已经回到高值。
            //
            // v245 用 Math.min(float, int)，于是永远取到那个卡住的低值：
            //
            //   float=0.05（锁屏残留）  int=0.78（滑块 200）
            //   → min = 0.05 → user = 0.025 → 增益 0.5 → 屏幕一直暗
            //   → 用户怎么拖都亮不起来 = **锁死在一个固定值**
            //
            // 这正是用户描述的"固定值"。取 max 后：
            //   → user = 0.78 → 增益 1.0 → 立刻回到滑块对应的亮度
            //
            // 为什么 max 更安全：min 的失败模式是"锁死在暗"（用户完全没法用），
            // max 的失败模式只是"少压暗一点"（功能减弱但可用）。
            // 宁可少压暗，也不能锁死。
            // v252.5: **只认 brightness_float 一张表**
            //
            // 实测（2026-10-31 20:41）：
            //     brightness_float  = 0.03   （滑块说的）
            //     screen_brightness = 255    （另一张表，垃圾值）
            // 取 max 得 1.0 → 以为用户要满亮度 → 完全不压暗 → 「到不了更低」
            //
            // 而 screen_brightness 在本机**根本不是亮度通道**（写它不产生
            // BrightnessEvent，模块关掉也一样）。一个死通道却在某些时刻被写成
            // 255，于是取 min 会「锁死在暗」、取 max 会「永远不压暗」——
            // 两种取法都错，错的是「两张表都参与」这件事本身。
            //
            // v254: 因此**只认 brightness_float 一张表**，screen_brightness
            // 的读取与 ContentObserver 一并删除（连读都不读，就不会被它污染）。
            float user = fromFloat;

            if (user >= 0f && user <= 1.001f) {
                if (user > 1f) user = 1f;
                if (Math.abs(user - sUserRaw) > 0.0015f) {
                    sUserRaw = user;
                    sUserChangedAt = android.os.SystemClock.elapsedRealtime();
                }
                sUserBright = user * USER_TO_DPC;
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * v252: **唯一的配置读取入口**，只走 Settings.System 一条通道。
     *
     * ===== 为什么砍掉另外两条 =====
     *
     * 旧版是三级兜底：Settings → Remote Preferences → 文件。
     * 三级兜底听起来稳，实际是"改了没用"的主要来源（2026-10-30 实测）：
     *   · remote：重启后 LSPosed 常常没绑上，里面躺着旧值（强度 0），
     *     反而把后面全屏蔽；
     *   · file  ：文件权限 rw-rw---- u0_a393:ext_data_rw，
     *     system_server 的 canRead() 返回 false —— 根本读不到；
     *   · settings：唯一"app 写、system_server 必读"的通道。
     *
     * 三级兜底还有个更隐蔽的坏处：**出问题时无法确定是哪一级生效的**。
     * 用户看到"界面显示 50、实际跑 0"，而模块认为自己读到了值 ——
     * 因为读的是另一条通道里的旧值。一条通道就没这个问题。
     *
     * 代价：UI 必须拿到 WRITE_SETTINGS。UI 侧已有 canWrite 检查并会引导
     * 用户去"修改系统设置"授权，这比"静默走另一条通道"好得多。
     */
    private static void loadCfg(Context ctx) {
        boolean en = true;
        float mb = Cfg.MIN_BRIGHT_DEFAULT;
        float gc = Cfg.GAMMA_COMP_DEFAULT;
        boolean ok = false;

        // v266: **ContentProvider 优先** —— UI 把配置写进它自己的 SharedPreferences，
        // 由 CfgProvider 只读暴露。system_server 有权限查询任意 provider（v221 验证）。
        //
        // 为什么不用 RemotePreferences：2026-11-01 取证，
        // /data/misc/apexdata/*/prefs/ 下从未出现 com.lumen.fakedc 目录 ——
        // UI 拿到的"可写句柄"一次都没落盘，模块读到的其实是 Settings 兜底镜像，
        // 而镜像又是模块自己写的，等于自己读自己写的值。
        try {
            android.net.Uri uri =
                    android.net.Uri.parse("content://com.lumen.fakedc.cfg/cfg");
            android.database.Cursor c = ctx.getContentResolver()
                    .query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst() && c.getColumnCount() >= 2) {
                        en = c.getInt(0) != 0;
                        mb = c.getFloat(1);
                        if (c.getColumnCount() >= 3) gc = c.getFloat(2);
                        ok = true;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            noteErr("provider", t);
        }

        // 兜底：provider 拿不到（app 被冻结 / 刚开机还在启动）时读 Settings 镜像。
        // 镜像是我v260起自己写的，只能保证"不改坏"，不能当用户意图的唯一来源。
        if (!ok) try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            en = Settings.System.getInt(cr, Cfg.ENABLE, Cfg.ENABLE_DEFAULT) != 0;
            mb = Settings.System.getFloat(cr, Cfg.MIN_BRIGHT, Cfg.MIN_BRIGHT_DEFAULT);
            gc = Settings.System.getFloat(cr, Cfg.GAMMA_COMP, Cfg.GAMMA_COMP_DEFAULT);
            ok = true;
        } catch (Throwable t) {
            noteErr("settings", t);
        }

        // 统一钳位：任何来源的值都过这一关（除零、越界都在此拦住）
        if (mb < 0.002f) mb = 0.002f;
        else if (mb > 1f) mb = 1f;
        if (gc < 0.5f) gc = 0.5f;
        else if (gc > 1.5f) gc = 1.5f;

        // v271: 下限变了 → 记一笔，供诊断对比"面板实际钉在哪"
        if (Math.abs(mb - cMinBright) > 1e-4f) {
            sLastEvent = "minB " + Math.round(cMinBright * 100) + "%->" + Math.round(mb * 100) + "%";
        }
        cEnable = en;
        cMinBright = mb;
        cGammaComp = gc;
        // 只有真读到才置 ready —— 读失败要重试，不能用一个假默认值糊住
        sCfgReady = ok;

        // v260: 把**实际生效的值**镜像回 Settings（变了才写），供 UI 显示。
        //
        // 配置真值在 RemotePreferences（app 唯一可写通道）。Settings 只是镜子：
        // 万一 UI 没绑上服务（清除数据 / 刚重启 / 服务未就绪），它读到的是
        // 模块真正在跑的值，而不是另一套默认值 —— 否则会出现
        // 「界面显示 10%、模块实际跑 11%」，用户永远对不上账（2026-11-01 实测）。
        if (ok && ctx != null) {
            try {
                android.content.ContentResolver cr = ctx.getContentResolver();
                if (Settings.System.getInt(cr, Cfg.ENABLE, -1) != (en ? 1 : 0)) {
                    Settings.System.putInt(cr, Cfg.ENABLE, en ? 1 : 0);
                }
                if (Math.abs(Settings.System.getFloat(cr, Cfg.MIN_BRIGHT, -1f) - mb) > 1.0E-6f) {
                    Settings.System.putFloat(cr, Cfg.MIN_BRIGHT, mb);
                }
                // v273: 第三项也要镜像。否则开机头几十秒（provider 还没就绪、
                // 模块走 Settings 兜底时）GAMMA_COMP 读不到 → 悄悄回落 1.0，
                // 表现为「刚开机画面偏白，过一会儿自己好」。
                if (Math.abs(Settings.System.getFloat(cr, Cfg.GAMMA_COMP, -1f) - gc) > 1.0E-6f) {
                    Settings.System.putFloat(cr, Cfg.GAMMA_COMP, gc);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static float parseFloat(String s, float d) {
        if (s == null) return d;
        try {
            return Float.parseFloat(s.trim());
        } catch (Throwable t) {
            return d;
        }
    }

    // ===== v248: 矩阵压暗实现 =====

    /**
     * 按「用户意图亮度 t」和「DC 区间下限 minB」算出矩阵增益。
     *
     *   t >= minB  → g = 1（完全原生，不动矩阵）
     *   t <  minB  → g = (t / minB) ^ (1/2.2)
     *
     * 为什么是 1/2.2：屏幕亮度在感知上接近 sRGB gamma 2.2。
     * 若用线性比 t/minB，低端会掉得极快（t=0.1minB 时线性给 0.1，
     * 而 gamma 反解给 0.36），看起来像"突然全黑"；反解后才与原生
     * 调光的观感一致。legacy v126 用实测数据验证过：
     *   minB=8%、t=0.0855% → 理论 g=0.12707，实测 0.12704。
     *
     * 纯函数，无副作用，方便单测/仿真对拍。
     */
    /**
     * v265: **删除「压暗强度」参数，补偿量自动算**。
     *
     * 用户 2026-11-01 质疑「这不是 bug 吗，感觉很像」—— 他说得对。
     * 把补偿做成一个独立参数（默认还是 0），等于「设了 DC 区间下限、
     * 功能却默认不工作」，这不是设计而是缺陷。
     *
     * 正确做法：钳位发生时，补偿量由 t/minB **唯一决定** ——
     * 用户把亮度拖到 t，视觉亮度就该是 t，中间差多少由矩阵补多少。
     *
     *   t >= minB  → g = 1（原生，不动矩阵）
     *   t <  minB  → g = (t/minB)^(1/2.2)（完全补偿，视觉亮度 = t）
     *
     * 为什么 1/2.2：屏幕亮度感知接近 sRGB gamma 2.2。线性比在低端掉得
     * 太快（t=0.1minB 时线性给 0.1，gamma 反解给 0.36），看起来像
     * "突然全黑"。legacy v126 实测：minB=8%、t=0.0855% → 理论 0.12707，
     * 实测 0.12704。
     *
     * 纯函数，无副作用，方便单测/仿真对拍。
     */
    static float gainFor(float t, float minB) {
        if (minB <= 0f) return 1f;
        if (t < 0f) return 1f;
        if (t >= minB) return 1f;
        float r = t / minB;
        if (r <= 0f) return 0f;
        if (r >= 1f) return 1f;
        return (float) Math.pow(r, 1.0 / GAMMA_EXP);
    }

    /**
     * v267: 用户百分比 → framework 亮度单位。
     *
     * {@link Cfg#MIN_BRIGHT} 存的是**用户在 UI 上看到的那个百分比**
     * （0.60 = 亮度滑块 60% 那个位置），而 hook 里拿到的 t 是 framework
     * 亮度值（0~{@link Cfg#BRIGHT_FULL} 才是滑块行程）。两者必须换算
     * 才能比大小，否则"设置 60%"会钉到 120% 的位置上去。
     *
     * 换算只此一处，三个使用点（矩阵线程 + 两个背光钩子）都走它。
     */
    private static float minBNorm() {
        return cMinBright * Cfg.BRIGHT_FULL;
    }

    /**
     * 把增益写进 DTM 的 4242 槽位（标量矩阵）。
     *
     * 防闪两条（legacy v210/v211 定案，2026-10-18 血案）：
     *   · 变化够大（|Δg| >= tol）→ 写
     *   · 距上次写超过 80ms（心跳）→ 写
     *   **两条是「或」**。写成「且」会出现"既不写也永远不补"：
     *   系统在切页面/显示重配时会清掉我们的矩阵，之后再也补不回来 → 白闪。
     *
     * 只在热路径外调用（echo 线程），避免在显示更新关键路径上做反射。
     */
    private static void pushMatrix(float g) {
        Object dtm = sDtm;
        if (dtm == null) return;

        if (g > 1f) g = 1f;
        if (g < 0f) g = 0f;

        long now = System.currentTimeMillis();
        if (sLastGain >= 0f) {
            float d = Math.abs(g - sLastGain);
            // v257: 收窄吞变化的阈值。
            //
            // 旧值 max(8e-4, g*0.012) 在 g=0.5 时等于 0.006 —— 意味着 0.6% 以内
            // 的变化全被丢掉，攒够一次才跳一下。拖动亮度条时这就是"一顿一顿"、
            // 没有原生那种连续感。改成 max(1.5e-4, g*0.0025)，约四分之一，
            // 小步也能跟上；80ms 心跳保留，负责补回被系统清掉的矩阵。
            float tol = Math.max(1.5E-4f, g * 0.0025f);
            boolean changed = d >= tol;
            boolean heartbeat = (now - sLastGainMs) >= 80L;
            if (!changed && !heartbeat) return;   // 变化不大 **且** 刚写过 → 跳过
        }

        try {
            float[] m = new float[16];
            m[0] = g; m[5] = g; m[10] = g; m[15] = 1.0f;

            // v257: 反射句柄只解析一次（旧实现每帧都遍历全类方法，比写矩阵还贵）
            java.lang.reflect.Method sm = sSetColorMatrix;
            if (sm == null) {
                for (java.lang.reflect.Method x : dtm.getClass().getDeclaredMethods()) {
                    if (!"setColorMatrix".equals(x.getName())) continue;
                    Class<?>[] pt = x.getParameterTypes();
                    if (pt.length == 2 && pt[0] == int.class && pt[1] == float[].class) {
                        sm = x; break;
                    }
                }
                if (sm == null) return;
                sm.setAccessible(true);
                sSetColorMatrix = sm;
            }
            sm.invoke(dtm, Integer.valueOf(MY_MATRIX_LEVEL), m);
            sLastGain = g;
            sLastGainMs = now;
            sMatrixActive = (g < 0.9999f);
            sMatrixPushes++;
        } catch (Throwable ignored) {
        }
    }

    /** 复位矩阵（增益 1.0 = 完全透传），退出压暗时调用一次 */
    private static void resetMatrix() {
        if (!sMatrixActive && sLastGain < 0f) return;
        Object dtm = sDtm;
        if (dtm != null) {
            try {
                float[] m = new float[16];
                m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f;
                for (java.lang.reflect.Method x : dtm.getClass().getDeclaredMethods()) {
                    if (!"setColorMatrix".equals(x.getName())) continue;
                    Class<?>[] pt = x.getParameterTypes();
                    if (pt.length == 2 && pt[0] == int.class && pt[1] == float[].class) {
                        x.setAccessible(true);
                        x.invoke(dtm, Integer.valueOf(MY_MATRIX_LEVEL), m);
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        sLastGain = 1f;
        sLastGainMs = 0L;
        sMatrixActive = false;
    }

    /** 3a: 从 DTM 构造函数捕获实例（legacy v109 验证过最可靠的路径） */
    private static final class DtmCtorHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            // 先拿实例再 proceed：DTM 的构造体本身很轻（只初始化字段），
            // 在这里读 thisObject 不会读到半初始化状态的风险 —— 我们只
            // 存引用，等到真正调用 setColorMatrix 时才用它。
            //
            // proceed 放在最后 return，保持"异常原样上抛"的语义：
            // 构造失败是系统自身问题，吞掉反而会掩盖故障。
            try {
                Object self = chain.getThisObject();
                if (self != null && sDtm == null) {
                    sDtm = self;
                    reportLog("DTM captured via constructor");
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        }
    }

    /** 3b: setColorMatrix 节流 —— 我们自己写的放行，系统高频重配挡掉一部分 */
    private static final class ColorMatrixHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            try {
                Object self = chain.getThisObject();
                if (self != null && sDtm == null) sDtm = self;
                java.util.List<Object> args = chain.getArgs();
                if (args != null && args.size() >= 1 && args.get(0) instanceof Number) {
                    int lv = ((Number) args.get(0)).intValue();
                    // 我们自己那一路：直接放行（不做任何改写，避免自激）
                    if (lv == MY_MATRIX_LEVEL) return chain.proceed();
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        }
    }

    /**
     * v235: **背光转换 hook** —— 低亮度时把面板钉在下限。
     *
     * 为什么选这个点（而不是 requestDisplayStateLocked）：
     *   · 它是「亮度 → 背光」的唯一翻译函数，AOSP 多年未改签名（F→F）；
     *   · 上层无论走自动亮度、手动滑条、RBC nits、还是 SurfaceControl，
     *     最后都过它，所以不受上层重构影响；
     *   · 入参本身就是 0~1 亮度，直接换成 minBright，语义与原版
     *     「把 brightnessState 钉在 minBright」完全一致，且自动套用
     *     ROM 自己的背光曲线（不会因为曲线非线性而算错）。
     *
     * 热路径纪律：只读 volatile 快照，不写 Settings、不做反射。
     */
    private static final class BacklightHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            try {
                java.util.List<Object> cargs = chain.getArgs();
                if (cargs == null || cargs.isEmpty()) return chain.proceed();
                Object a0 = cargs.get(0);
                if (!(a0 instanceof Number)) return chain.proceed();
                float b = ((Number) a0).floatValue();
                // 负数 = BRIGHTNESS_OFF_FLOAT(-1) 或无效值：灭屏，绝不能钉成下限
                if (b < 0f) return chain.proceed();

                ensureCfg();
                if (!cEnable) return chain.proceed();

                float minB = minBNorm();
                if (b >= minB) return chain.proceed();     // 本来就在下限之上

                Object[] arr = cargs.toArray();
                arr[0] = Float.valueOf(minB);
                sLastPin = minB;
                return chain.proceedWith(chain.getThisObject(), arr);
            } catch (Throwable t) {
                android.util.Log.e(TAG, "backlight intercept error", t);
            }
            return chain.proceed();
        }
    }

    /**
     * v254 钩子②：**取消锁屏 DIM 强制覆盖** —— DisplayDimModifier.shouldApply
     *
     * ===== 为什么挂这里（work/分析/锁屏DIM机制-R2.md）=====
     *
     *     DisplayDimModifier.getBrightnessAdjusted(b) =
     *         max(min(b − dimAmount, dimConfig), minCfg)
     *     本机 dimConfig = 8.54597E-4 = 亮度下限 ⇒ min() 对任意输入恒等于它
     *     ⇒ 输出**与输入无关**，恒 8.54597E-4（dumpsys 796/800 两行实证：
     *       unclamped 0.4772027 与 0.075837344 → brt 同为 8.54597E-4 [ dim ]）
     *
     *     shouldApply 判据 = request.policy == 2（C2:741744），
     *     是 `BrightnessModifier.apply` 里「压不压」的唯一开关 ——
     *     返回 false 后亮度保持系统原算值，dwell/ramp/落 HAL 全走原路径。
     *
     * ===== 实现方式：替换返回值，不是改参数 =====
     *
     * 直接 return Boolean.FALSE 且不调 chain.proceed() —— 原方法根本不执行，
     * 返回值被替换成 false。不建状态、不写 Settings、不加门控。
     *
     * ⚠️ 副作用（已知取舍）：同时取消「超时前屏幕逐渐变暗」的视觉效果，
     * 超时后直接灭屏。若要保留渐暗，单点改造：在本钩子里加一个判断，
     * 不要动其它钩子。
     */
    private static final class DimModifierHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            sCallsDim++;
            // 恒 false：不调 proceed()，原方法不执行，返回值即为 FALSE
            return Boolean.FALSE;
        }
    }

    /**
     * v253: **真正的背光出口** —— LocalDisplayAdapter$BacklightAdapter.setBacklight
     *
     * ===== 怎么找到的（反汇编 services.jar，2026-10-31）=====
     *
     *     setBacklight(float sdrBacklight, float sdrNits,
     *                  float backlight,    float nits)      ← 4 参（本机无调用点）
     *     setBacklight(float sdrBacklight, float sdrNits,
     *                  float backlight,    float nits,
     *                  boolean galleryHdrBoost)             ← 5 参（MIUI，唯一被调）
     *
     * 两条分支用的都是**第 3 个 float（backlight）**：
     *
     *     if (!mUseSurfaceControlBrightness && !mForceSurfaceControl)
     *         mBacklight.setBrightness(backlight);      // LogicalLight → 灯光 HAL
     *     else
     *         mSurfaceControlProxy.setDisplayBrightness(token,
     *                 sdrBacklight, sdrNits, backlight, nits);   // MIUI 版再转
     *                                                            // ...WithDimLayer
     *
     * 全 dex 里 `LogicalLight.setBrightness(F)` 只有 2 个调用点，都在这里。
     *
     * ===== 为什么不用之前那些 hook =====
     *
     *   requestDisplayStateLocked：改参数后 brt 完全不变（minB=90% 实测），
     *       它是状态通道，不是亮度通道 —— v254 起它只记 state，不再钳位。
     *   SurfaceControl.setDisplayBrightness：count=0，本 ROM 走 LogicalLight 分支。
     *
     * 只改一个数：第 3 个 float。nits / galleryHdrBoost 原样传下去。
     *
     * v254: **钳位只在这里做**（单一职责）—— 它是整条链最下游，
     * 上游无论走 4 条路径里的哪一条，最后都汇到这里。
     */
    private static final class BacklightAdapterHooker implements Hooker {
        /** 0=sdrBacklight 1=sdrNits 2=backlight 3=nits */
        private static final int IDX_BACKLIGHT = 2;

        @Override
        public Object intercept(Chain chain) throws Throwable {
            try {
                java.util.List<Object> cargs = chain.getArgs();
                if (cargs == null || cargs.size() <= IDX_BACKLIGHT) return chain.proceed();
                Object a = cargs.get(IDX_BACKLIGHT);
                if (!(a instanceof Number)) return chain.proceed();
                float b = ((Number) a).floatValue();
                sCallsBk++;
                if (b <= 0f) return chain.proceed();      // 0 / -1 = 灭屏，绝不钉亮

                ensureCfg();
                if (!cEnable) return chain.proceed();
                if (sIdleWhy != null) return chain.proceed();   // state != ON → 不干预

                // v264: **钳位与压暗解耦**。
                //
                // v261 曾在这里加过 `if (cMaxDim <= 0) return proceed()`，
                // 理由是"钳位不压暗 = 纯限制，亮度会锁死"。这个判断是错的：
                // 用户指出「压暗是压暗，DC 区间是 DC 区间」——
                //   · DC 区间（本方法）= 背光不低于下限 → 消除低亮 PWM，独立成立
                //   · 压暗强度（矩阵线程）= 低于下限后补多少暗 → 可选增强
                // 把钳位绑在压暗上，导致用户把强度设 0 时连 DC 钳位一起消失，
                // 外置摄像头立刻看到 PWM（2026-11-01 实测）。
                // 现在：只要 enable 且亮度低于下限就钳位，强度只影响矩阵。

                float minB = minBNorm();
                if (b >= minB) return chain.proceed();

                Object[] arr = cargs.toArray();
                arr[IDX_BACKLIGHT] = Float.valueOf(minB);
                sLastPin = minB;

                // v263: **sdrBrightness 一起钉**。
                //
                // 依据：2026-11-01 用户用外置摄像头实拍，钉住 args[2]
                // (displayBacklight) 之后**仍然能看到 PWM 频闪** —— 说明
                // 背光根本没被抬到 minB，改的那个参数没有到达硬件。
                //
                // 本机走 SurfaceControl 分支（mForceSurfaceControl=false），
                // SC 的形参是 (sdrBrightness, sdrNits, displayBacklight,
                // displayNits, galleryHdrBoost)；SurfaceFlinger 侧决定最终
                // 背光的是 sdrBrightness（SDR 内容亮度），displayBacklight
                // 只参与 HDR 混合。所以 args[0] 才是真正起作用的那一个。
                //
                // 两个都钉：SDR 场景等效，HDR 场景也不会被漏掉。
                // args[1] 是 sdrNits（尼特值，不是 0~1 比例），绝不碰。
                Object s0 = arr.length > 0 ? arr[0] : null;
                if (s0 instanceof Number) {
                    float v0 = ((Number) s0).floatValue();
                    if (v0 > 0f) arr[0] = Float.valueOf(minB);
                }

                sHookPins++;
                return chain.proceedWith(chain.getThisObject(), arr);
            } catch (Throwable t) {
                android.util.Log.e(TAG, "backlight adapter intercept error", t);
            }
            return chain.proceed();
        }
    }


    /** 核心 hook：逐行对齐原版 Hook.kt，无任何额外机制 */
    /**
     * v254: **状态钩子** —— 只记 state、维护 sIdleWhy，**不再钳位**。
     *
     * ===== 为什么把钳位搬走 =====
     *
     * 这个点原本是主 hook（v236 定级），但真机实测：**改参数不改背光**
     * —— 把 arr[1] 钉成 minB=90%，dumpsys 的 brt 一点不变。它是状态通道，
     * 不是亮度通道。真正的出口在下游的 `BacklightAdapter.setBacklight`
     * （v253 反汇编定位，v254 收敛为只挂 5 参版）。
     *
     * 所以 v254 起本钩子只剩三个职责，一个不多：
     *   ① 记录 state（sScreenOn）—— 准入判据 state != ON(2) 的唯一来源
     *   ② 维护 sIdleWhy（日志里"为什么不生效"的一句话结论）
     *   ③ 观测计数 sHookCalls / sHookLastRaw（上游观测量，**不是**生效证据）
     *
     * 钳位全部交给钩子①，单一职责、挂在最下游。
     *
     * ===== v251 推倒重写的背景（保留）=====
     *
     * v243~v250 在这一个方法里堆了 317 行：RBC 写入、level 计算、act 状态机、
     * DIM 判断、反馈环修正、量化门控…… 每一层都是为了修上一层的副作用。
     * RBC 那条路上的坑全部踩过：activated 被系统复位 / level>=35 触发
     * policy=DIM / 单向棘轮 / AOD 误判 / 写 Settings 太多与 ramp 打架。
     * 所以 **RBC 整套删掉，一次都不碰**；压暗交给矩阵线程，由模块自己控制。
     */
    private static final class DisplayStateHooker implements Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            try {
                java.util.List<Object> cargs = chain.getArgs();
                if (cargs == null || cargs.size() < 2) return chain.proceed();
                if (!(cargs.get(0) instanceof Number)) return chain.proceed();
                if (!(cargs.get(1) instanceof Number)) return chain.proceed();

                // v252.3: 记录屏幕状态（DOZE(=3)/OFF(=1) 都不算亮屏）
                sCallsState++;
                boolean screenOn = ((Number) cargs.get(0)).intValue() == 2;
                sScreenOn = screenOn;

                float target = ((Number) cargs.get(1)).floatValue();
                if (target < 0f) return chain.proceed();      // 灭屏标记

                // v237: hook 行为计数（volatile 自增，零 IO）。
                // 回答"hook 到底跑过没有" —— 诊断卡在无回显时唯一的指标。
                sHookCalls++;
                sHookLastRaw = target;

                ensureCfg();

                // ===== v254: 统一准入（只剩 state != ON 一条判据）=====
                // 息屏 / 总开关关 —— 任一命中就**完全不管**。
                // 这是"避免突然锁定"的根本：不该介入的时候一个字节都不写，
                // 系统怎么设置亮度都跟我们无关。
                String why = idleReason(screenOn);
                if (why != null) {
                    sIdleWhy = why;
                    sTargetBright = -1f;
                    return chain.proceed();
                }
                sIdleWhy = null;

                // 交给矩阵线程：这里是**未钳位的真实意图**，没有反馈环
                // （反馈环的成因是拿钳位后的值回推，而我们从不用回推值）
                sTargetBright = target;

                // v254: **这里不再钳位**。钳位统一交给钩子①（setBacklight），
                // 它挂在本方法下游、是真正的背光出口 —— 在本方法改参数
                // 实测不生效（minB=90% 时 brt 一点不变）。本钩子只剩
                // 「记 state + 维护 sIdleWhy + 观测计数」三个职责。
                return chain.proceed();
            } catch (Throwable t) {
                android.util.Log.e(TAG, "intercept error", t);
            }
            return chain.proceed();
        }
    }

    // v235: 原 NoopHooker（把 RBC 方法挂成返回 null）已删除。
    // Android 14+ 的 RBC 走 nits spline，handleRbcChanged 正是压暗生效点，
    // 拦住它等于让 level 永远没人读 —— 详见 install() 里的说明。

    // ---- helpers ----

    /**
     * 原版：getSurroundingThis(thisObject) → getOverlayContext()
     *
     * v234: **按 Class 缓存反射结果**。
     *
     * 旧实现每帧都做 1 次 getDeclaredField + 1 次 getMethod + 1 次 invoke。
     * 原版用的是 XposedHelpers（内部自带缓存）所以无感；我们直接手写反射，
     * 就等于每帧重新解析一遍。而 verify.sh 的 D3「热路径无反射」只扫
     * intercept 函数体，反射代码在隔壁函数里 —— **静态检查看不见**，
     * 属于典型的"假通过"。
     *
     * 现在稳态只有 HashMap.get + Field.get + Method.invoke，解析零次。
     * 解析失败也缓存 {null,null}，避免每帧重试。
     */
    private static Context getCtx(Object o) {
        Class<?> k = o.getClass();
        Object[] hit = sCtxCache.get(k);
        if (hit == null) {
            hit = resolveCtxAccessor(k);
            sCtxCache.put(k, hit);
        }
        try {
            java.lang.reflect.Field f = (java.lang.reflect.Field) hit[0];
            Method m = (Method) hit[1];
            if (f != null && m != null) {
                Object outer = f.get(o);
                if (outer != null) {
                    Object r = m.invoke(outer);
                    if (r instanceof Context) return (Context) r;
                }
            }
        } catch (Throwable ignored) {
        }
        return systemContext();
    }

    /** 解析 {Field this$0, Method getOverlayContext}；找不到返回 {null,null} */
    private static Object[] resolveCtxAccessor(Class<?> k) {
        try {
            Class<?> c = k;
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("this$0");
                    f.setAccessible(true);
                    Method m = findDeclaredMethod(f.getType(), "getOverlayContext");
                    if (m != null) {
                        m.setAccessible(true);
                        return new Object[]{f, m};
                    }
                } catch (NoSuchFieldException ignored) {
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return new Object[]{null, null};
    }

    /** 沿类层次找声明方法（getMethod 只找 public，@hide 方法常常不是） */
    private static Method findDeclaredMethod(Class<?> c, String name) {
        while (c != null) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * system_server 的 system Context。
     *
     * v234: 首次成功后**缓存引用**（system_server 里它是单例，永不变化）。
     * 失败则 5 秒内不重试 —— 否则热路径兜底调用会每帧反射 3 次。
     */
    private static Context systemContext() {
        Context c = sSysCtx;
        if (c != null) {
            ensureUserBrightObserver(c);
            return c;
        }
        long now = System.currentTimeMillis();
        if (now < sCtxRetryAfter) return null;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object inst = at.getMethod("currentActivityThread").invoke(null);
            Object r = at.getMethod("getSystemContext").invoke(inst);
            if (r instanceof Context) {
                sSysCtx = (Context) r;
                ensureUserBrightObserver((Context) r);
                return (Context) r;
            }
        } catch (Throwable ignored) {
        }
        sCtxRetryAfter = now + 5000L;
        return null;
    }

    // ===== v250: 用户意图亮度**实时**监听（修「有延迟 / 拉太快没反应 / 抖动」）=====
    //
    // 用户实证（2026-10-31 19:39）：
    //   「有延迟，亮度能直接到最低……拉太快就会出现没反应然后突然高亮……
    //     到不了更低，但是反复滑动其实能更低」
    //
    // 实测事件序列坐实了抖动来源：
    //   19:37:18 brt=100% rbcStr=56
    //   19:37:20 brt= 39% rbcStr=25 → 48   ← 同一亮度下压暗量在跳
    //   19:37:22 brt= 72% rbcStr=25
    //   19:37:24 brt= 60% rbcStr=35
    //   19:37:40 brt=100% rbcStr=25
    //   19:37:59 brt=  7% rbcStr=56        ← 能到 2 nits，但要碰运气
    //
    // 根因：压暗量用 sUserBright 算，而 sUserBright 由后台线程**每秒**才刷一次。
    // 拖滑块时它滞后 1 秒：
    //   · 滞后期间算出"不需要压暗" → 用户感觉"没反应"
    //   · 1 秒后突然更新 → 压暗量突跳 → 用户感觉"突然高亮/闪烁"
    //   · 只有恰好落在 rbcStrength 高的那一帧才够暗 → "反复滑才能更低"
    //
    // 修法：注册 ContentObserver，`brightness_float` / `screen_brightness`
    // 一变就立刻刷 sUserBright（同进程内存读，成本极低）。
    // 后台线程的 1s 轮询保留作为兜底（观察者注册失败时仍能工作）。
    private static volatile boolean sUserObsRegistered = false;

    private static void ensureUserBrightObserver(final Context ctx) {
        if (sUserObsRegistered) return;
        synchronized (FakeDCModule.class) {
            if (sUserObsRegistered) return;
            try {
                android.content.ContentResolver cr = ctx.getContentResolver();
                android.database.ContentObserver obs =
                        new android.database.ContentObserver(
                                new android.os.Handler(android.os.Looper.getMainLooper())) {
                            @Override
                            public void onChange(boolean selfChange) {
                                // 同一线程内只做 2 次内存读，不跨进程、不做 IO
                                refreshUserBright(ctx);
                            }
                        };
                // v254: 只监听 brightness_float —— screen_brightness 在本机是
                // 垃圾值通道（实测写成 255），监听它只会引入错误的意图源。
                cr.registerContentObserver(
                        Settings.Secure.getUriFor("brightness_float"), false, obs);
                sUserObsRegistered = true;
                android.util.Log.i(TAG, "v250 user-bright observer registered (realtime)");
            } catch (Throwable t) {
                // 注册失败不影响主流程：后台线程 1s 轮询仍在兜底
                android.util.Log.w(TAG, "v250 observer register failed: " + t);
            }
        }
    }
}