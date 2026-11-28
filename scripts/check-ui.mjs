#!/usr/bin/env node
/**
 * 界面骨架静态自检（UX-14 全站横屏 + 左侧 rail、UX-15 配色、UX-18 个性化）。
 *
 * 无手机时用它对「导航结构 / 配色定案 / 偏好存储」这些容易被后续改动悄悄改回去的
 * 契约做断言。触控链路另有 scripts/check-touch.mjs。
 *
 * 用法：node scripts/check-ui.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (rel) => {
  try {
    return readFileSync(join(root, rel), 'utf8');
  } catch {
    return null;
  }
};

// 只留代码行（剥掉整行注释），避免把「历史做法」的注释当成实现命中。
const codeOnly = (src) => (src ?? '').split('\n').filter((l) => !l.trim().startsWith('//')).join('\n');

const checks = [];
const add = (name, ok, detail = '') => checks.push({ name, ok, detail });
const must = (name, src, pattern, why) =>
  add(name, src !== null && pattern.test(codeOnly(src)), src === null ? '文件缺失' : why);
const mustNot = (name, src, pattern, why) =>
  add(name, src !== null && !pattern.test(codeOnly(src)), src === null ? '文件缺失' : why);

const manifest = read('app/src/main/AndroidManifest.xml');
const rootScreen = read('app/src/main/java/dev/mineport/launcher/ui/RootScreen.kt');
const theme = read('app/src/main/java/dev/mineport/launcher/ui/theme/Theme.kt');
const prefs = read('app/src/main/java/dev/mineport/launcher/ui/prefs/Prefs.kt');
const personalize = read('app/src/main/java/dev/mineport/launcher/ui/personalize/PersonalizeScreen.kt');
const more = read('app/src/main/java/dev/mineport/launcher/ui/more/MoreScreen.kt');
const motion = read('app/src/main/java/dev/mineport/launcher/ui/motion/Motion.kt');
const main = read('app/src/main/java/dev/mineport/launcher/MainActivity.kt');
const app = read('app/src/main/java/dev/mineport/launcher/MineportApplication.kt');
const instances = read('app/src/main/java/dev/mineport/launcher/ui/instances/InstancesScreen.kt');
const downloads = read('app/src/main/java/dev/mineport/launcher/ui/downloads/DownloadsScreen.kt');
const padPrefs = read('app/src/main/java/dev/mineport/launcher/input/PadPrefs.kt');
const gameActivity = read('app/src/main/java/dev/mineport/launcher/game/GameActivity.kt');

// —— UX-14 全站横屏 ——
must('UX-14 MainActivity 横屏', manifest, /MainActivity[\s\S]{0,400}?screenOrientation="sensorLandscape"/,
  'MainActivity 的 screenOrientation 应为 sensorLandscape');
mustNot('UX-14 不再锁竖屏', manifest, /screenOrientation="portrait"/,
  'manifest 里不应再有 portrait（GameActivity 是 sensorLandscape）');
must('UX-14 旋转不重建', manifest, /MainActivity[\s\S]{0,400}?smallestScreenSize/,
  'MainActivity 的 configChanges 要含 smallestScreenSize');
must('UX-14 rail 宽 80dp', rootScreen, /\.width\(80\.dp\)/, '左侧导航 rail 宽度应为 80dp');
must('UX-14 选中态 3dp 蓝条', rootScreen, /\.width\(3\.dp\)[\s\S]{0,160}?background\(cs\.primary/,
  '选中项左侧贴边 3dp 主蓝条');
must('UX-14 rail 底部版本号', rootScreen, /BuildConfig\.VERSION_NAME[\s\S]{0,40}?-alpha/,
  'rail 底部显示 v{VERSION_NAME}-alpha');
must('UX-14 五项导航', rootScreen, /NavTab\("home", "启动"[\s\S]{0,400}"more", "更多"/,
  '导航项顺序：启动 / 下载 / 设置 / 个性化 / 更多');
must('UX-14 不用 Material NavigationRail', rootScreen, /private fun NavRail\(/,
  '自绘 NavRail（Material NavigationRail 自带胶囊选中背景，不符合定案）');
must('UX-14 无底部导航栏', rootScreen, /^\s*NavHost\(/m, '根布局应是 Row(rail + NavHost)');
mustNot('UX-14 已移除 NavigationBar', rootScreen, /NavigationBar(Item)?\(/, '不应再用底部 NavigationBar');
must('UX-14 六个路由', rootScreen,
  /composable\("home"\)[\s\S]{0,1200}?composable\("instances"\)[\s\S]{0,600}?composable\("downloads"\)[\s\S]{0,600}?composable\("settings"\)[\s\S]{0,600}?composable\("personalize"\)[\s\S]{0,600}?composable\("more"\)/,
  'home / instances / downloads / settings / personalize / more 六个 destination');
must('UX-14 版本页可返回', instances, /fun InstancesScreen\(container: AppContainer, onBack: \(\(\) -> Unit\)\? = null\)/,
  'InstancesScreen 需要可选 onBack（子页面形态）');
must('UX-14 返回图标', instances, /ArrowBack/, '子页面 TopAppBar 要有 ArrowBack');
must('UX-14 rail 让开刘海', rootScreen, /WindowInsets\.safeDrawing/,
  'rail 要 windowInsetsPadding(safeDrawing)');

// —— UX-15 蓝白配色 ——
must('UX-15 品牌主蓝', theme, /0xFF1E5EFF/, '主蓝 #1E5EFF');
must('UX-15 浅色背景', theme, /0xFFF4F8FF/, '浅色背景起点 #F4F8FF');
must('UX-15 卡片边框', theme, /0xFFE3EAF5/, '卡片 1dp 边框 #E3EAF5');
must('UX-15 危险色', theme, /0xFFEF4444/, '危险色 #EF4444');
must('UX-15 深色背景保留', theme, /0xFF0E1116/, '深色背景 #0E1116（游戏内 HUD 必须深色）');
must('UX-15 主色可换', theme, /private fun lightScheme\(accent: Color\)/, 'lightScheme(accent) 参数化');
must('UX-15 onPrimary 按亮度', theme, /accent\.luminance\(\)/, 'onPrimary 按主色亮度选黑/白字');
must('UX-15 卡片容器色补齐', theme, /surfaceContainerHighest = Color\(0xFFE8F0FF\)/,
  'Card 默认容器色是 surfaceContainerHighest，不显式给值会掉回 M3 淡紫');
must('UX-15 chip 选中色', theme, /secondaryContainer = lerp\(accent, Color\.White, 0\.88f\)/,
  'FilterChip 选中态吃 secondaryContainer，必须跟着主色走');

// —— UX-18 个性化 ——
must('UX-18 Prefs 存主题', prefs, /"theme"/, 'SharedPreferences 键 theme');
must('UX-18 Prefs 存主色', prefs, /"accent"/, '键 accent');
must('UX-18 Prefs 存动效', prefs, /"animations"/, '键 animations');
must('UX-18 默认浅色', prefs, /THEME_LIGHT\) \?: THEME_LIGHT/, '默认值应是 THEME_LIGHT');
must('UX-18 Prefs 进程级单例', app, /val uiPrefs: Prefs = Prefs\(app\)/, 'AppContainer 里放 uiPrefs');
must('UX-18 主题接到 Activity', main, /prefs\.darkTheme\(systemDark\)/,
  'MainActivity 用 Prefs 决定 darkTheme');
must('UX-18 主色接到 Activity', main, /Prefs\.accentColor\(prefs\.accent\)/,
  'MainActivity 用 Prefs.accentColor 取主色');
must('UX-18 动效开关下发', main, /LocalAnimationsEnabled provides prefs\.animations/,
  'CompositionLocalProvider(LocalAnimationsEnabled provides ...)');
must('UX-18 动效开关定义', motion, /val LocalAnimationsEnabled = staticCompositionLocalOf/,
  'Motion.kt 定义 LocalAnimationsEnabled');
must('UX-18 关动画立刻完成', motion, /if \(!enabled\)[\s\S]{0,120}?snapTo\(1f\)/,
  'EnterFromBottom 在关闭动画时直接落到终态');
must('UX-18 四组设置', personalize, /SectionCard\("主题"[\s\S]{0,8000}?SectionCard\("主色调"[\s\S]{0,8000}?SectionCard\("动效"[\s\S]{0,8000}?SectionCard\("游戏内 HUD"/,
  '主题 / 主色调 / 动效 / 游戏内 HUD 四组');
must('UX-18 实时预览', personalize, /private fun PreviewCard\(/, '右侧预览卡');
must('UX-18 HUD 轮盘尺寸', padPrefs, /var actionPadSizeDp: Int/, 'PadPrefs 要有 actionPadSizeDp');
must('UX-18 HUD 位置预设', padPrefs, /fun setActionPadCorner\(left: Boolean\)/, '左下/右下预设');
must('UX-18 HUD 尺寸进游戏', gameActivity, /prefs\.actionPadSizeDp \* density/,
  'GameActivity 的轮盘外框用 prefs.actionPadSizeDp');

// —— 下载页横屏排版 ——
mustNot('下载页不用 TopAppBar', downloads, /TopAppBar\(/,
  '横屏可用高约 375dp，64dp 的 TopAppBar 比内容还贵，应改成一行紧凑标题');
must('下载页头部压成一行', downloads, /headlineSmall[\s\S]{0,600}?TextButton\(onClick = onReload\)/,
  '标题 / 版本统计 / 刷新要并排在同一行');
must('下载页列表占满剩余高度', downloads, /LazyColumn\([\s\S]{0,160}?Modifier\.weight\(1f\)/,
  'LazyColumn 必须 weight(1f)；fillMaxSize 会把上面的头部挤出可视区');
must('下载页搜索框不用浮动 label', downloads, /placeholder = \{ Text\("搜版本号/,
  'label 会额外占 16dp 并让输入框长到 72dp，改用 placeholder');

// —— 更多页 ——
must('更多页版本管理', more, /"版本管理"/, '更多页要有版本管理入口');
must('更多页诊断入口', more, /"诊断与日志"/, '更多页要有诊断入口');
must('更多页关于信息', more, /AboutCard\(container\.deviceProfile\)/, '更多页展示设备画像');

let failed = 0;
for (const check of checks) {
  if (!check.ok) failed += 1;
  console.log(`${check.ok ? 'PASS' : 'FAIL'}  ${check.name}${check.ok ? '' : `  ← ${check.detail}`}`);
}
console.log(`\n${checks.length - failed}/${checks.length} passed`);
process.exit(failed === 0 ? 0 : 1);
