# 引用与致谢

本项目的**机制设计**来自对若干同类实现的阅读与实测，**代码为独立编写**（Java，架构与原作不同）。
本文件说明每处引用的性质、上游许可证状态，以及为什么可以这样引用。

---

## 1. 设计与机制参照（不含其代码）

### ztc1997 / FakeDCBacklight — 本项目的思路来源

- 仓库：<https://github.com/ztc1997/FakeDCBacklight>
- 语言：Kotlin ｜ 许可证：**仓库内没有 LICENSE 文件** ｜ 状态：**已归档**（最后提交 2024-02-06）
- 包名：`com.ztc1997.fakedcbacklight`

**引用了什么（思路）**

| 思路 | 说明 |
|---|---|
| 核心手法 | 亮度低于某个下限时，**把面板背光钉住不降**，再用上层手段把画面压暗，从而让面板始终工作在高占空比区间 |
| 关键前提 | 「最低亮度」是 **HAL 背光值**，不是 0~100%。上游 README 明确写了这一点（例：小米 12s 是 0~50%，实测最低 7.99%），我们据此建立了自己的换算与标定流程 |
| 已知代价 | 上游 README 记录「会导致其它颜色管理功能（色温管理、护眼模式等）失效」——我们在设计时主动规避了这条路径 |

**没有引用什么（代码）**

上游压暗用的是系统 `Settings.Secure.reduce_bright_colors_level`（RBC）叠加层，我们**没有采用**：

- 我们的做法是写 `DisplayTransformManager` 的 4242 槽位（标量色彩矩阵），自算 `gain = (t/minB)^(1/2.2)`；
- 原因是我们实测 RBC 在 level ≥ 35 时会触发 `policy=DIM`，把亮度锁成与输入无关的常量（详见知识库 `06-决策记录.md` D-015）。这正是上游「亮度会被锁死」问题的机制来源，我们花了多个版本才绕开，不会走回去。

因此两边虽然目标一致，但**实现路径不同、代码不同语言、不同结构**，不存在代码复制关系。

> ⚠️ **因为上游没有许可证**（默认保留所有权利），它的源码**不能随本仓库一起分发**。
> 处理方式：`fakedc-upstream/`（上游完整源码副本）**已从版本库索引中移除并加入 `.gitignore`**，
> 文件保留在本地仅作对照。克隆本仓库的人不会拿到上游代码。

### HyperLux（闭源 APK）— 行为层参照

- 形态：第三方闭源 LSPosed 模块（自动亮度管理），我们反编译后**只读其行为与设计**用于对照
- 引用内容：写亮度后的「静默窗」防回读、面板租约、暗/亮切换需「持续满足」才动作等设计思路
- 记录：知识库 `07-HyperLux参照.md`
- **未使用其任何代码**，也未分发其产物

---

## 2. 运行/编译依赖（第三方组件）

| 组件 | 用途 | 许可证 | 说明 |
|---|---|---|---|
| [libxposed/api](https://github.com/libxposed/api) `102.0.0` | 模块 API（编译期 `compileOnly`，运行期由框架提供） | **Apache-2.0** | 仅调用其公开 API，未修改、未内联 |
| [LSPosed](https://github.com/LSPosed/LSPosed) | 运行框架（把模块注入 `system_server`） | **GPL-3.0** | 本模块是它的插件，不链接、不包含其代码 |
| Android SDK / `android.jar` | 编译期平台符号 | 见 Android SDK 条款 | 仅编译期使用，不随产物分发 |
| R8 / d8（Android SDK build-tools） | dex 编译与压缩 | Apache-2.0 | 构建工具 |

### 经典 Xposed API 桩（仅签名）

> 📁 以下桩文件位于本地的 `fakedc-legacy/`（旧实现，**未随本仓库发布**），此处仅作说明。

`fakedc-legacy/stub/de/robv/android/xposed/**` 是**手写的空签名桩**，只为让旧代码在无框架环境下通过编译：

- 只包含类名、方法签名与空实现，**不含**任何 [rovo89/XposedBridge](https://github.com/rovo89/XposedBridge)（Apache-2.0）的实现代码；
- `fakedc-api102/`（当前主力线）**不使用**这些桩，走的是 libxposed API 102。

---

## 3. 参考的公开资料

| 资料 | 用于 |
|---|---|
| Android 源码（`DisplayPowerController` / `DisplayTransformManager` / `LocalDisplayAdapter` 等） | 确认 hook 挂点与参数语义 |
| MTK / MIUI 显示相关 sysfs 节点（`disp_param`、`dc_status`、`lcd-backlight`） | 判定本机是否存在硬件 DC（结论：本机不可用） |

这些是平台行为与硬件接口，不涉及代码引用。

---

## 4. 一句话总结

> **思路有出处，代码自己写。**
> 上游（FakeDCBacklight）明确了「钉背光 + 压暗」这条路走得通，也留下了「HAL 最低亮度不是 0~100%」这个关键前提；
> 具体实现、配置通道、矩阵补偿、状态机与诊断体系均为本项目独立完成。
> 上游无许可证，故其源码不随本仓库分发；本项目亦不复制其代码。
