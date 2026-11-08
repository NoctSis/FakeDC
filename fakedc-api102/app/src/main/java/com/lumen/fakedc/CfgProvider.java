package com.lumen.fakedc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * v266: **配置通道 = 自建 ContentProvider**（只读，给模块用）。
 *
 * ===== 为什么放弃 Remote Preferences =====
 *
 * 2026-11-01 真机取证：`/data/misc/apexdata/<uuid>/prefs/` 下**从来没有**
 * `com.lumen.fakedc` 目录 —— 说明 UI 通过 XposedService 拿到的
 * RemotePreferences **一次都没落盘**，模块读到的全是 Settings 兜底镜像，
 * 而那个镜像是模块自己写的。闭环成了自说自话：
 *
 *     UI 想写 →（没写进去）→ remote 空 → 模块读 Settings
 *              → 模块把 Settings 镜像成"生效值" → UI 显示这个镜像
 *
 * 用户拖滑块的每一次调整，从来没到过模块。
 *
 * ===== 现在的结构 =====
 *
 *   UI 写：getSharedPreferences(PREF, MODE_PRIVATE)   ← app 自己的私有目录，
 *          100% 可写，不需要任何权限
 *   模块读：content://com.lumen.fakedc.cfg/cfg       ← system_server 有权限
 *          查询任意 provider（v221 实测可行）
 *
 * 为什么不用 Settings.System：Android 13+ 起 app 写自定义 key 直接抛
 * IllegalArgumentException（用户截图里的「写入失败」），这条路是死的。
 */
public class CfgProvider extends ContentProvider {

    /** 与模块侧硬编码的 authority 必须一字不差（模块不引用本类，避免跨进程加载） */
    public static final String AUTHORITY = "com.lumen.fakedc.cfg";

    /** 建议共享的 SharedPreferences 文件名（UI 与 Provider 共用） */
    public static final String PREF = "fakedc_cfg";

    /** 两个配置项，顺序固定 —— 模块按列索引读 */
    public static final String[] COLS = {"enable", "min_bright", "gamma_comp"};

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/cfg");
    }

    /**
     * v269: **发一次"配置变了"的信号**（用户 2026-11-02 提出的方案）。
     *
     * 用户原话：「读取到的值后续就不需要继续读取了，等待别人唤醒，然后再正常读取，
     * 看看用户是否需要调整数值」。这正是 Android 的 ContentObserver 机制 ——
     * 不是 app 保持连接发心跳（那反而要把 app 保活），而是**改完发一条消息就完事**，
     * app 立刻可以被系统回收。
     *
     * 模块侧注册 observer 后就零轮询；收到这条信号才重读一次。
     * 没有它，模块只能每秒盲读一次 —— 那是跨进程 binder，而且**会把已被回收的
     * app 进程重新拉起来**（等于模块在替 app 保活，正好是用户想避免的）。
     */
    public static void notifyChanged(android.content.Context ctx) {
        try {
            ctx.getContentResolver().notifyChange(uri(), null);
        } catch (Throwable ignored) {
            // 信号发不出去顶多退化成慢速兜底轮询，不影响配置本身
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri u, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(COLS);
        try {
            SharedPreferences p = getContext()
                    .getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE);
            c.addRow(new Object[]{
                    p.getInt(Cfg.ENABLE, Cfg.ENABLE_DEFAULT),
                    p.getFloat(Cfg.MIN_BRIGHT, Cfg.MIN_BRIGHT_DEFAULT),
                    p.getFloat(Cfg.GAMMA_COMP, Cfg.GAMMA_COMP_DEFAULT)
            });
        } catch (Throwable t) {
            // 读不到也要返回一行默认值 —— 否则模块会一直用兜底默认，看不出问题
            c.addRow(new Object[]{1, Cfg.MIN_BRIGHT_DEFAULT, Cfg.GAMMA_COMP_DEFAULT});
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/fakedc-cfg";
    }

    // 模块只读；UI 直接写自己的 SharedPreferences，不走 insert
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
