# 课表岛 ClassIsland Mobile（非官方安卓适配版）

ClassIsland 桌面课表（[github.com/ClassIsland/ClassIsland](https://github.com/ClassIsland/ClassIsland)，GPL-3.0）的安卓轻量适配版。

## 版本矩阵（v1.6.0，一次构建产出三个包）

| 变体 | Gradle 任务 | 最低系统 | ABI | 说明 |
|---|---|---|---|---|
| **arm32** | `assembleArm32Debug` | Android 6.0 (API 23) | armeabi-v7a | 32 位老设备 |
| **arm64** | `assembleArm64Debug` | Android 6.0 (API 23) | arm64-v8a | 64 位设备 |
| **island** | `assembleIslandDebug` | Android 10 (API 29) | armeabi-v7a + arm64-v8a + x86_64 | 灵动岛适配版 |

- 三个包 `applicationId` 相同（`tech.classisland.mobile`），可互相覆盖安装；
  `versionName` 分别为 `1.6.0-arm32` / `1.6.0-arm64` / `1.6.0-island`。
- **island 版**与另外两个的功能差异：悬浮窗固定贴合屏幕顶部的挖孔/灵动岛区域
  （`layoutInDisplayCutoutMode` + `DisplayCutout` 定位，黑底白字，不可拖动），
  设置页会显示当前版本模式。
- arm32/arm64 的差异体现在打包 ABI 声明（`ndk.abiFilters`）。本应用为纯 Kotlin/Java，
  暂无 `.so`，因此两者运行逻辑一致；后续一旦引入 NDK 库，该拆分会自动生效。

## 本次修复的问题

### 1. 「今日」页"今日课程"无法完整显示 ✅
**根因**：`TodayFragment` 的 1 秒 ticker 里执行了 `rvToday.adapter = LessonAdapter(...)`，
每秒换一次 adapter 会清空 RecyclerView 全部子 View 并把锚点重置到第 0 项，
于是列表每秒跳回顶部，后面的课程永远看不到。

**修复**：
- adapter 只在 `onCreateView` 创建一次，之后只调用 `submit(...)` 提交数据；
- 数据未变化时不刷新；只有状态变化的行才 `notifyItemChanged`，滚动位置不再丢失；
- 列表显式 `isNestedScrollingEnabled = false`，由外层 ScrollView 统一滚动；
- 顺带：`Store` 增加档案 JSON 缓存，消除每秒一次的主线程文件读取。

### 2. 时间表编辑功能无法使用 ✅
**根因**：编辑页采用「渲染时 removeAllViews → 重建 Spinner → Spinner 的
`onItemSelected` 回调里再次 removeAllViews → 再建新 Spinner」的结构，
而 Spinner 在 attach/layout 后**必然回调一次** `onItemSelected`，
于是形成无限重建循环（界面卡死/白屏）。「课表」页签同理。

**修复**（`EditActivity` 重构）：
- 每个页签的页头（星期/时间表 Spinner、管理按钮）只在进入页签时构建一次；
- 交互回调只重建下方内容区，绝不触碰页头；
- 新增 `Spinner.latch(...)`：吞掉系统 attach 后的首次回调，从机制上杜绝自触发循环；
- 补充：同一星期可新建多份课表（单双周轮换），并新增课表选择器；
  时间表支持复制/删除；删除课表增加二次确认。

### 4. 自带灵动岛改为黑底白字 + 进度条 + 圆角可调 ✅
- 胶囊背景固定纯黑（`#000000`），文字全部改为白/浅灰，去掉"上课时按科目染色"的旧逻辑；
- 进度条从「点击展开后才可见」移到主区域，**收起状态下常驻显示**；
- 进度条颜色：上课时取当前科目主题色，其余状态为青色 `#26C6DA`；
- **圆角可调**：设置页 →「悬浮窗圆角」0~40dp（步长 2dp），改动立即重建悬浮窗生效，
  0dp 直角、40dp 接近官方胶囊形状。

### 4.1 课间休息时不再显示下节课名 ✅（反馈修复）
- 课间时主区域（今日页大字、灵动岛大字）原来错误地显示**下节课科目**，现改为只显示
  **当前状态词**，默认「课间休息」；
- 该状态词**可自定义**：设置页 →「课间显示文字」，可改成「下课了」「休息一下」等；
- 下一节的具体科目仍保留在下方的"下一节预告"行，不影响信息完整性。

### 5. 移植更多官方功能 ✅
- **上课状态常驻通知**：无需悬浮窗权限即可看到当前科目、节次、剩余时间与百分比进度
  （锁屏可见；内容变化时才刷新，不会每秒打断系统）。
- **今日进度**：今日页新增"已上 N/M 节 · 距放学 HH:MM:SS"。
- **教师信息**：今日页与灵动岛展开区显示授课老师。
- **临时调课快捷入口**：课表页新增「临时调课」按钮，且**长按某一天**可直接把当天换成
  另一份课表（也可清除）。
- **提醒去重**：闹钟提醒与悬浮窗兜底提醒共用一张去重表，同一节课只响一次（此前会重复通知）。
- **档案缓存**：`Store` 缓存档案 JSON，页面秒刷新不再读盘。

### 4.2 灵动岛大小可调 ✅
设置页 →「悬浮窗大小」0.7x ~ 1.6x（步长 0.1x，另有「复位」）。
按倍率**等比缩放**整个悬浮窗：文字字号、内边距、状态点直径、进度条高度与间距、圆角同步变化，
不是只改字号，所以小屏设备可以缩小、大屏可以放大。运行中修改即时生效。

### 4.3 课间显示逻辑统一 ✅
今日页 / 悬浮窗 / 桌面小组件三处共用同一套规则：
上课中 → 科目名；课间 → 课间文案（默认「课间休息」，可自定义）；放学/无课 → 状态词。

## 本轮新增的官方功能移植

| 官方功能 | 安卓实现 | 位置 |
|---|---|---|
| **档案**（多档案） | 多档案管理：新建（以当前课表为模板）/ 切换 / 重命名 / 删除。每个档案独立存为 `profile_<id>.json`，切换后提醒、悬浮窗、小组件全部同步刷新 | 设置 →「档案管理」 |
| **统计**（课时统计） | 本周课时统计页：总课时/总节数/总时长、逐日课时、科目课时排行（带比例条）、逐日课表明细，可翻上一周/下一周。口径同官方：45 分钟 = 1 课时、空课不计入、单双周只在对应周计入、已应用临时调课 | 今日页顶部「查看本周课时统计 ▸」 |
| **桌面课表组件** | 桌面小组件：显示状态、科目、倒计时与进度条，每分钟自动刷新，点击打开 App；课间同样使用自定义课间文案 | 长按桌面 → 小组件 → 课表岛 |
| **导入后立即生效** | 导入档案、编辑保存、切换档案后，桌面小组件与提醒排程同步更新 | — |

## 与 ClassIsland 官方档案的完全兼容 ✅

采用「**原始 JSON + 定点覆盖**」读写策略（`Profile.raw` / `Profile.rootRaw`）：

| 环节 | 行为 |
|---|---|
| 导入官方 JSON / ZIP | 逐实体深拷贝原始 JSON，**官方所有字段全量保留** |
| 在本 App 内编辑 | 只更新我们管理的字段（Name/起止时间/TimeType/科目/规则…） |
| 保存 / 导出 | 以原始 JSON 为底合并写出，未涉及的字段**原样带出** |

因此以下官方字段不会再丢失或被清空（旧版本会丢）：
- 根级 `Name`、`TempClassPlanId`、`IsOverlayClassPlanEnabled`、`OverlayClassPlanId`、`IsActive`
- 时间段 `IsHideDefault`、`DefaultClassId`（旧版会被强制清空 ✗）
- 科目/课表的 `SortIndex`、`Color` 等本 App 不认识的扩展字段
- `Classes[]` 每一项内部的自定义字段

对应测试：`app/src/test/java/tech/classisland/mobile/CompatibilityTest.kt`
（字段保真、写出幂等、编辑后扩展字段仍在、改写后台表规则正确）。

## 功能概览

- **今日页**：上课中/课间/放学状态、当前科目大字、每秒倒计时、时间段进度条、今日进度、下一节预告
- **课表页**：按周翻看、周一至周日节次与时间、单双周轮换、长按快速临时调课
- **档案导入**：ClassIsland 官方档案 JSON / `.zip`，内置示例档案
- **上下课提醒**：预备铃（提前分钟数可调）、上课、下课、放学；开机自动重排
- **外观设置**：悬浮窗圆角 0~40dp 可调、课间显示文字可自定义、悬浮窗位置可拖动/重置
- **上课状态常驻通知**：无需悬浮窗权限，锁屏可见当前科目与倒计时

## 构建

要求：JDK 11、Android SDK（compileSdk 33、build-tools 33.0.2）、Gradle 7.6.4（AGP 7.4.2）

```bash
gradle assembleArm32Debug assembleArm64Debug assembleIslandDebug
# 产物：app/build/outputs/apk/<flavor>/debug/*.apk
gradle test          # 运行单元测试
```

### 在 aarch64（ARM 服务器/容器）上构建

Google 只发布 x86_64 版 aapt2，需用 qemu-user 运行同版本官方 aapt2，并在
**用户级** `~/.gradle/gradle.properties` 配置（工程内配置优先级更高，互不影响）：

```properties
android.aapt2FromMavenOverride=/opt/android-sdk/bin/agp74-aapt2
```

> 注意：AGP 校验该路径必须以 `aapt2` 结尾（`SdkConstants.FN_AAPT2`），
> 所以封装脚本命名成 `xxx-aapt2` 而不是 `aapt2-xxx`。

## 说明

- 本应用为非官方适配，仅复用 ClassIsland 的档案数据格式，不含桌面端的天气、自动化、插件等
- Android 不开放第三方直接写入系统灵动岛：island 版的做法是把顶层窗口绘制到
  `DisplayCutout` 区域并使用与系统一致的黑底白字，视觉上与灵动岛融为一体
- 依据 GPL-3.0 开源
