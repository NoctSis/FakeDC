package com.lumen.fakedc;

/**
 * v173: **明/暗双主题色板**。
 *
 * 用户确认："就用这个暗色规范来写"、"我喜欢暗色全白太亮了"。
 *
 * 设计依据 Material Design Dark 规范：
 *   · 背景 #121212（比纯黑柔和，且省电）
 *   · 卡片表面 #1E1E1E（白卡在暗色下太刺眼，故所有卡片统一深灰）
 *   · 主文字 87% 白、次文字 60% 白（不用纯白，降低对比疲劳）
 *   · 主色用 Blue 200 (#90CAF9) 而非亮色的 Blue 600 (#3B6FE0)
 *     —— 暗色下深色主字会"糊"在深底上看不清
 *
 * 用法：所有 UI 代码读这里的字段，不硬编码颜色。
 * 切换方式：{@link #setDark(boolean)}，在 Activity onCreate 里调一次。
 */
public final class Theme {

    /** 当前是否暗色（默认 true，用户偏好暗色） */
    private static boolean dark = true;

    // ---- 亮色板 ----
    private static final int LIGHT_BG = 0xFFF4F5F7;
    private static final int LIGHT_CARD = 0xFFFFFFFF;
    private static final int LIGHT_TEXT = 0xFF141414;
    private static final int LIGHT_TEXT_DIM = 0xFF8C93B0;
    private static final int LIGHT_ACCENT = 0xFF3B6FE0;
    private static final int LIGHT_ACCENT_SOFT = 0xFFEAF2FF;
    private static final int LIGHT_ORANGE = 0xFFFF8A3D;
    private static final int LIGHT_BORDER = 0xFFDCE1EA;
    private static final int LIGHT_GRID = 0xFFEDEFF3;
    private static final int LIGHT_OK = 0xFF1B8A3A;
    private static final int LIGHT_WARN = 0xFFB8860B;
    private static final int LIGHT_BAD = 0xFFB00020;

    // ---- 暗色板（Material Design Dark）----
    private static final int DARK_BG = 0xFF121212;
    private static final int DARK_CARD = 0xFF1E1E1E;
    private static final int DARK_TEXT = 0xDEFFFFFF;        // 87% 白
    private static final int DARK_TEXT_DIM = 0x99FFFFFF;    // 60% 白
    private static final int DARK_ACCENT = 0xFF90CAF9;      // Blue 200
    private static final int DARK_ACCENT_SOFT = 0xFF1F2A38; // 深蓝灰，hero 卡底
    private static final int DARK_ORANGE = 0xFFFFB74D;      // Orange 300
    private static final int DARK_BORDER = 0x2EDFFFFF;      // 18% 白
    private static final int DARK_GRID = 0x1FFFFFFF;        // 12% 白
    private static final int DARK_OK = 0xFF81C784;          // Green 300
    private static final int DARK_WARN = 0xFFFFD54F;        // Yellow 300
    private static final int DARK_BAD = 0xFFE57373;         // Red 300

    private Theme() {
    }

    public static void setDark(boolean d) {
        dark = d;
    }

    public static boolean isDark() {
        return dark;
    }

    // ---- 取色接口 ----
    public static int bg()          { return dark ? DARK_BG : LIGHT_BG; }
    public static int card()        { return dark ? DARK_CARD : LIGHT_CARD; }
    public static int text()        { return dark ? DARK_TEXT : LIGHT_TEXT; }
    public static int textDim()     { return dark ? DARK_TEXT_DIM : LIGHT_TEXT_DIM; }
    public static int accent()      { return dark ? DARK_ACCENT : LIGHT_ACCENT; }
    public static int accentSoft()  { return dark ? DARK_ACCENT_SOFT : LIGHT_ACCENT_SOFT; }
    public static int orange()      { return dark ? DARK_ORANGE : LIGHT_ORANGE; }
    public static int border()      { return dark ? DARK_BORDER : LIGHT_BORDER; }
    public static int grid()        { return dark ? DARK_GRID : LIGHT_GRID; }
    public static int ok()          { return dark ? DARK_OK : LIGHT_OK; }
    public static int warn()        { return dark ? DARK_WARN : LIGHT_WARN; }
    public static int bad()         { return dark ? DARK_BAD : LIGHT_BAD; }

    /** 状态圆点：运行中 */
    public static int dotRun()      { return ok(); }
    /** 状态圆点：未运行/旁路 */
    public static int dotIdle()     { return dark ? 0xFF616161 : 0xFFB2B2B2; }
    /** 阈值分隔线（半透明主色，画在曲线上） */
    public static int accentFaint() {
        return dark ? 0x5590CAF9 : 0x553B6FE0;
    }
    /** 数值徽章背板（曲线卡右下角「最暗 X%」） */
    public static int badgeBg() {
        return dark ? 0xF2252525 : 0xF2F4F7FB;
    }
    /** Switch 关态轨道（30% 白） */
    public static int swTrackOff()  { return dark ? 0x4DFFFFFF : 0xFFDCE1EA; }
    /** Switch 关态滑块（70% 白） */
    public static int swThumbOff()  { return dark ? 0xB3FFFFFF : 0xFFF4F5F7; }
    /** Switch 开态轨道（50% 主色） */
    public static int swTrackOn()   { return dark ? 0x8090CAF9 : 0x803B6FE0; }
    /** Switch 开态滑块（主色） */
    public static int swThumbOn()   { return accent(); }
    /** EditText 提示文字（hint） */
    public static int editHint()    { return dark ? 0x99FFFFFF : 0xFF999999; }
}
