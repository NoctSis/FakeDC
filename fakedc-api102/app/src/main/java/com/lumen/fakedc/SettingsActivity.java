package com.lumen.fakedc;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v252: **极简设置界面**（从 1068 行精简到约 420 行）。
 *
 * ===== 为什么重写 =====
 *
 * 用户原话：「前后端基本上都没有一个统一过，到底要哪些不要哪些，
 * 实在不行那就留最简单的，然后直接通过日志来实现查询」。
 *
 * 旧界面有 6 行「实时诊断」，分别读 HOOK_STATUS / HOOK_SIG / HOOK_ERROR /
 * UI_HAL / UI_LEVEL / UI_RBC_ACT / UI_RBC_LEVEL / ECHO_ENABLE / STAT_CALLS /
 * STAT_PINS / ECHO_MIN / ECHO_MAX / ECHO_OFF / ECHO_SRC / MODULE_LOG /
 * MODULE_ALIVE / MODULE_PROC —— 17 个 key。
 *
 * 其中 UI_RBC_ACT / UI_RBC_LEVEL / ECHO_ACT 自 v251 删掉 RBC 后
 * **永远不会有值**，但界面还在把它们当实时状态显示 —— 用户看到
 * "系统压暗等级 0"，会以为功能坏了，其实那个 key 早就废了。
 *
 * ===== 新界面只有 4 件事 =====
 *
 *   1. 三个开关/滑块：启用、DC 下限、压暗强度（+ 息屏不干预）
 *   2. 一行日志：模块自己把状态拼好写在 Cfg.LOG 里，界面原样显示
 *   3. 复制按钮：把日志复制走
 *   4. 权限引导：没拿到 WRITE_SETTINGS 时明确告诉用户去哪开
 *
 * 配置**只有一个通道**：Settings.System（4 个 key）。
 * 状态**只有一个通道**：Cfg.LOG（1 个字符串）。
 * 前后端不可能再对不上 —— 因为没有第二份数据。
 */
public class SettingsActivity extends Activity {

    private TextView heroStatus;
    private TextView heroSub;
    private TextView logView;
    private TextView permTip;
    private Switch swEnable;
    private SeekBar sbMin;
    private TextView tvComp;
    private SeekBar sbComp;
    private EditText etMin;
    private EditText etComp;
    private TextView tvMin;

    /** 刷新时抑制监听回调，避免"程序设值 → 触发保存"的抖动 */
    private boolean suppress = false;
    private int lastLogHash = 0;

    /**
     * v256: **Remote Preferences —— UI 唯一能写的配置通道**。
     *
     * v252 直接写 Settings.System，在 Android 13+ 对自定义 key 是禁止的，
     * 每次拖动都抛 IllegalArgumentException（用户截图里的「写入失败」）。
     * LSPosed 的 module.apk 通过 XposedService 拿到的是**可写**句柄。
     */
    private android.content.SharedPreferences sp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.setDark(true);
        setContentView(buildUi());
        try {
            if (getActionBar() != null) getActionBar().hide();
        } catch (Throwable ignored) {
        }

        // v266: **配置写自己的 SharedPreferences**（app 私有目录，100% 可写，
        // 不需要任何权限）。模块通过 CfgProvider 以只读方式取。
        //
        // 为什么彻底放弃 XposedService / RemotePreferences（2026-11-01 取证）：
        // /data/misc/apexdata/*/prefs/ 下从来没有 com.lumen.fakedc 目录 ——
        // UI 拿到的可写句柄一次都没真正落盘，模块读到的全是 Settings 兜底镜像，
        // 而那个镜像又是模块自己写的，闭环成了自说自话。
        try {
            sp = getSharedPreferences(CfgProvider.PREF, MODE_PRIVATE);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        logView.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                refreshLog();
                refreshHero();
                logView.postDelayed(this, 1000L);
            }
        }, 1000L);
    }

    // ============================ UI 构建 ============================

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Theme.bg());
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(32));
        sv.addView(root);

        // ---- 标题 ----
        TextView title = new TextView(this);
        title.setText("伪DC调光");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text());
        root.addView(title);

        heroStatus = new TextView(this);
        heroStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        heroStatus.setTextColor(Theme.textDim());
        heroStatus.setPadding(0, dp(6), 0, 0);
        root.addView(heroStatus);

        heroSub = new TextView(this);
        heroSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        heroSub.setTextColor(Theme.textDim());
        heroSub.setPadding(0, dp(2), 0, dp(16));
        root.addView(heroSub);

        // ---- 权限提示（默认隐藏）----
        permTip = new TextView(this);
        permTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        permTip.setTextColor(Theme.warn());
        permTip.setPadding(dp(12), dp(10), dp(12), dp(10));
        permTip.setBackground(round(Theme.accentSoft(), 10));
        permTip.setVisibility(View.GONE);
        permTip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWriteSettings(); }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.bottomMargin = dp(12);
        permTip.setLayoutParams(plp);
        root.addView(permTip);

        // ---- 卡片 1：开关 ----
        LinearLayout card1 = card();
        swEnable = switchRow(card1, "启用", "关闭后完全恢复系统原生亮度",
                new CompoundListener() {
                    @Override public void onChecked(boolean v) {
                        if (!suppress) putInt(Cfg.ENABLE, v ? 1 : 0);
                    }
                });
        root.addView(card1);

        // ---- 卡片 2：参数 ----
        LinearLayout card2 = card();

        tvMin = sectionLabel(card2, "DC 区间下限", "面板背光的下限。拖到 30% 就是面板永远不低于 30%");
        sbMin = seekRow(card2, 99, 0, new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvMin.setText("DC 区间下限    " + (p + 1) + "%");
                setNum(etMin, p + 1);
                if (fromUser && !suppress) {
                    // v267: **直写，不再做任何映射**。
                    //
                    // 用户 2026-11-02 原话：「不应该是我设置定哪里，你就定在哪个位置吗」。
                    // 这正是 Cfg.MIN_BRIGHT 契约的本意（float 0~1 = 面板亮度百分比），
                    // 也是原版 fakedcbacklight 的语义（pref_min_screen_bright 直接是百分比）。
                    //
                    // v261 曾加平方映射 0.5*(p/100)^2，把标签「28%」写成 0.0392 ——
                    // 标签与实际差 7 倍，用户拖到 28% 以为面板在 28%，实际停在 3.9%，
                    // 于是「只有变暗生效、防闪没反应」。映射越花哨越骗人，直写才对得上。
                    int pct = p + 1;
                    putFloat(Cfg.MIN_BRIGHT, pct / 100.0f);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        etMin = numberInput(card2, 0.1f, 100f, new OnNumber() {
            @Override public void onNumber(float v) {
                int pct = Math.round(v);
                if (pct < 1) pct = 1;
                if (pct > 100) pct = 100;
                suppress = true;
                try { sbMin.setProgress(pct - 1); } catch (Throwable ignored) {}
                suppress = false;
                tvMin.setText("DC 区间下限    " + trimNum(v) + "%");
                putFloat(Cfg.MIN_BRIGHT, v / 100.0f);
            }
        });

        // ---- v271: 画面补偿（治「偏白」）----
        tvComp = sectionLabel(card2, "画面补偿", "觉得画面发白/发亮就往低调，偏暗就往高调");
        sbComp = seekRow(card2, 100, 50, new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float v = 0.5f + p / 100.0f;
                tvComp.setText("画面补偿    " + String.format(java.util.Locale.US, "%.2f", v));
                setNum(etComp, v);
                if (fromUser && !suppress) {
                    putFloat(Cfg.GAMMA_COMP, v);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        etComp = numberInput(card2, 0.5f, 1.5f, new OnNumber() {
            @Override public void onNumber(float v) {
                suppress = true;
                try { sbComp.setProgress(Math.round((v - 0.5f) * 100f)); } catch (Throwable ignored) {}
                suppress = false;
                tvComp.setText("画面补偿    " + String.format(java.util.Locale.US, "%.2f", v));
                putFloat(Cfg.GAMMA_COMP, v);
            }
        });

        root.addView(card2);

        // ---- v275: 已知限制（用户要求写进界面，免得当成 bug 反复排查）----
        LinearLayout cardNote = card();
        TextView noteTitle = new TextView(this);
        noteTitle.setText("已知限制（不是 bug）");
        noteTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        noteTitle.setTypeface(Typeface.DEFAULT_BOLD);
        noteTitle.setTextColor(Theme.text());
        noteTitle.setPadding(0, 0, 0, dp(8));
        cardNote.addView(noteTitle);
        cardNote.addView(noteLine(
                "亮度拉到最低 + 下限设很高 → 画面可能全黑。8bit 量化极限，那时本来也看不见。"));
        cardNote.addView(noteLine(
                "暗部可能有条带、深色糊成一片。面板特性，和 PWM 是同一个取舍的两面。"));
        root.addView(cardNote);

        // ---- 卡片 3：日志 ----
        LinearLayout card3 = card();
        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(Theme.text());
        logTitle.setPadding(0, 0, 0, dp(8));
        card3.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTextColor(Theme.textDim());
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setText("（等待模块写入…）");
        logView.setLineSpacing(dp(3), 1f);
        card3.addView(logView);

        Button copy = new Button(this);
        copy.setText("复制日志");
        copy.setAllCaps(false);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        copy.setTextColor(Theme.accent());
        copy.setBackground(round(Theme.accentSoft(), 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        copy.setLayoutParams(clp);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    String t = logView.getText().toString();
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("fakedc", t));
                    Toast.makeText(SettingsActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
        card3.addView(copy);

        root.addView(card3);
        return sv;
    }

    // ============================ 刷新 ============================

    private void refreshAll() {
        ContentResolver cr = getContentResolver();
        android.content.SharedPreferences prefs = sp;
        suppress = true;
        try {
            boolean en;
            float mb;
            if (prefs != null && prefs.contains(Cfg.ENABLE)) {
                en = prefs.getInt(Cfg.ENABLE, Cfg.ENABLE_DEFAULT) != 0;
                mb = prefs.getFloat(Cfg.MIN_BRIGHT, Cfg.MIN_BRIGHT_DEFAULT);
            } else {
                en = Settings.System.getInt(cr, Cfg.ENABLE, Cfg.ENABLE_DEFAULT) != 0;
                mb = Settings.System.getFloat(cr, Cfg.MIN_BRIGHT, Cfg.MIN_BRIGHT_DEFAULT);
            }
            swEnable.setChecked(en);

            // v271: 画面补偿回填（0.5~1.5 → 进度 0~100）
            float gc;
            if (prefs != null && prefs.contains(Cfg.GAMMA_COMP)) {
                gc = prefs.getFloat(Cfg.GAMMA_COMP, Cfg.GAMMA_COMP_DEFAULT);
            } else {
                gc = Settings.System.getFloat(cr, Cfg.GAMMA_COMP, Cfg.GAMMA_COMP_DEFAULT);
            }
            if (gc < 0.5f) gc = 0.5f;
            if (gc > 1.5f) gc = 1.5f;
            int pComp = Math.round((gc - 0.5f) * 100f);
            sbComp.setProgress(pComp);
            tvComp.setText("画面补偿    " + String.format(java.util.Locale.US, "%.2f", gc));
            setNum(etComp, gc);

            // v267: 直写直读。minB 就是面板亮度百分比，反解就是 ×100。
            // （v261 的平方反解 100*sqrt(2*mb) 已随映射一起删掉。）
            int pMin = (int) Math.round(Math.max(0f, mb) * 100f);
            if (pMin < 1) pMin = 1;
            if (pMin > 100) pMin = 100;
            sbMin.setProgress(pMin - 1);
            tvMin.setText("DC 区间下限    " + pMin + "%");
            setNum(etMin, mb * 100f);

        } catch (Throwable t) {
            // v256: 不再静默吞掉 —— 之前 catch 后滑块停在初始值 0，
            // 用户看到"压暗强度 0"，而模块实际读到的是 50（截图实证）。
            Toast.makeText(this, "读取配置失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
        suppress = false;

        refreshPerm();
        refreshHero();
        refreshLog();
    }

    /**
     * v256: 配置能不能写，取决于**有没有连上模块**，不再是 WRITE_SETTINGS。
     *
     * 走 Remote Preferences 之后不需要任何系统权限；写不进去只有一种原因：
     * LSPosed 没启用模块 / 没勾「系统框架」/ 改完没重启 —— 那就一条都写不进去。
     */
    /**
     * v266: 配置通道换成 SharedPreferences 后，写入不再依赖任何权限或服务绑定
     * —— 永远可写，所以这条提示永久隐藏。
     * 模块是否在跑，由顶部状态行（读 HOOK_VER）负责告知，不需要重复提示。
     */
    private void refreshPerm() {
        permTip.setVisibility(View.GONE);
    }

    /** 版本与存活状态 */
    private void refreshHero() {
        ContentResolver cr = getContentResolver();
        int installed = 0;
        try {
            installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable ignored) {
        }
        int running = -1;
        boolean alive = false;
        try {
            running = Settings.System.getInt(cr, Cfg.HOOK_VER, -1);
            alive = Settings.System.getInt(cr, Cfg.MODULE_ALIVE, 0) == 1;
        } catch (Throwable ignored) {
        }

        if (running < 0) {
            heroStatus.setText("模块未运行");
            heroStatus.setTextColor(Theme.bad());
            heroSub.setText("已装 v" + installed + " · 去 LSPosed 启用本模块并勾选「系统」作用域，然后重启");
        } else if (running != installed) {
            heroStatus.setText("已装 v" + installed + " · 运行 v" + running + " → 重启后生效");
            heroStatus.setTextColor(Theme.warn());
            heroSub.setText("dex 还是旧的，重启手机才会加载新版本");
        } else {
            heroStatus.setText("运行中 v" + running);
            heroStatus.setTextColor(Theme.ok());
            heroSub.setText(alive ? "已注入 system_server" : "注入中…");
        }
    }

    /** 日志：模块每秒写一行，这里原样显示（不解析、不加工） */
    private void refreshLog() {
        try {
            String s = Settings.System.getString(getContentResolver(), Cfg.LOG);
            if (s == null || s.isEmpty()) {
                logView.setText("（暂无日志 —— 模块可能没被注入，或刚开机还没写）");
                return;
            }
            int h = s.hashCode();
            if (h != lastLogHash) {
                lastLogHash = h;
                logView.setText(s);
            }
        } catch (Throwable ignored) {
        }
    }

    // ============================ 写配置 ============================

    /**
     * v256: 写配置**只走 Remote Preferences**。
     *
     * 不再先试 Settings.System —— 那条路在 Android 13+ 对自定义 key 必失败，
     * 试一次就弹一次错，用户看到的是"每次都写入失败"。
     * sp 为 null 说明模块还没连上（LSPosed 没启用/没重启），这时明确提示，
     * 而不是静默把值丢掉。
     */
    private void putInt(String key, int v) {
        android.content.SharedPreferences p = sp;
        if (p == null) {
            Toast.makeText(this, "模块未连接 —— 去 LSPosed 启用后重启手机",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            p.edit().putInt(key, v).apply();
            CfgProvider.notifyChanged(this);
            refreshPerm();
        } catch (Throwable t) {
            Toast.makeText(this, "写入失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void putFloat(String key, float v) {
        android.content.SharedPreferences p = sp;
        if (p == null) {
            Toast.makeText(this, "模块未连接 —— 去 LSPosed 启用后重启手机",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            p.edit().putFloat(key, v).apply();
            CfgProvider.notifyChanged(this);
            refreshPerm();
        } catch (Throwable t) {
            Toast.makeText(this, "写入失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openWriteSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    // ============================ 小组件 ============================

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(round(Theme.card(), 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    /** v275: 提示条目（灰字、带圆点前缀） */
    private TextView noteLine(String text) {
        TextView tv = new TextView(this);
        tv.setText("· " + text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(Theme.textDim());
        tv.setLineSpacing(dp(2), 1.05f);
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }


    private TextView sectionLabel(LinearLayout parent, String text, String hint) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Theme.text());
        tv.setPadding(0, dp(10), 0, dp(2));
        parent.addView(tv);
        if (hint != null) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            h.setTextColor(Theme.textDim());
            h.setPadding(0, 0, 0, dp(4));
            parent.addView(h);
        }
        return tv;
    }

    private SeekBar seekRow(LinearLayout parent, int max, int start,
                            SeekBar.OnSeekBarChangeListener l) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(start);
        sb.setOnSeekBarChangeListener(l);
        parent.addView(sb);
        return sb;
    }

    interface CompoundListener {
        void onChecked(boolean v);
    }

    /** v272: 数值输入回调 */
    interface OnNumber {
        void onNumber(float v);
    }

    /**
     * v272: 数值输入框 —— 滑块给手感，输入框给精度。
     *
     * 用户 2026-11-08：「你的这些滑块都是百分比的，能不能使用数值，我想要输入多少都行，
     * 但是不能破坏现在程序」。所以**滑块保留、输入框并列**，两者双向同步：
     *   · 拖滑块 → 同步刷新输入框（仅在输入框未获得焦点时，避免打断打字）
     *   · 输数字回车/失焦 → 夹到 [lo,hi] 后写配置，并同步把滑块拨过去
     * 非法输入（空/非数字）一律不写，只提示 —— 绝不破坏已有配置。
     */
    private EditText numberInput(LinearLayout parent, final float lo, final float hi,
                                 final OnNumber cb) {
        EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setSingleLine(true);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setTextColor(Theme.text());
        et.setHintTextColor(Theme.textDim());
        et.setHint("也可直接输入数值，回车生效（" + trimNum(lo) + " ~ " + trimNum(hi) + "）");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        et.setLayoutParams(lp);
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                commitNumber(v, lo, hi, cb);
                return true;
            }
        });
        et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (!has) commitNumber((TextView) v, lo, hi, cb);
            }
        });
        parent.addView(et);
        return et;
    }

    private void commitNumber(TextView v, float lo, float hi, OnNumber cb) {
        String raw = v.getText().toString().trim().replace(',', '.');
        if (raw.length() == 0) return;            // 空着不动，保留原值
        float x;
        try {
            x = Float.parseFloat(raw);
        } catch (Throwable t) {
            Toast.makeText(this, "不是有效数字，已忽略", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Float.isNaN(x) || Float.isInfinite(x)) return;
        if (x < lo) x = lo;
        if (x > hi) x = hi;
        cb.onNumber(x);
    }

    /** 只在输入框没有焦点时同步，避免打断用户打字 */
    private void setNum(EditText et, float v) {
        if (et == null || et.hasFocus()) return;
        et.setText(trimNum(v));
    }

    private static String trimNum(float v) {
        if (Math.abs(v - Math.round(v)) < 1e-4f) {
            return String.valueOf(Math.round(v));
        }
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private Switch switchRow(LinearLayout parent, String title, String hint,
                             final CompoundListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(clp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(Theme.text());
        col.addView(t);

        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        h.setTextColor(Theme.textDim());
        h.setPadding(0, dp(1), 0, 0);
        col.addView(h);

        row.addView(col);

        final Switch sw = new Switch(this);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                l.onChecked(v);
            }
        });
        row.addView(sw);

        parent.addView(row);
        return sw;
    }
}
