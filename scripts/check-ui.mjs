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
const contentTab = read('app/src/main/java/dev/mineport/launcher/ui/downloads/ContentTab.kt');
const contentInstaller = read('app/src/main/java/dev/mineport/launcher/mod/ContentInstaller.kt');
const modrinth = read('app/src/main/java/dev/mineport/launcher/mod/Modrinth.kt');
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
must('UX-18 主色接到 Activity', main, /Prefs\.accentColor\(prefs\.accent/,
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

// —— 下载页：横屏排版 ——
mustNot('下载页不用 TopAppBar', downloads, /TopAppBar\(/,
  '横屏可用高约 375dp，64dp 的 TopAppBar 比内容还贵，应改成一行紧凑标题');
must('下载页标题一行', downloads, /"下载"[\s\S]{0,300}?typography\.headlineSmall/,
  '标题用 headlineSmall 并排挨着版本信息');
must('下载页统计进标题行', downloads, /summary \?: "正在拉取版本清单…"/,
  '「最新 X · N 个版本」要显示在标题行，不能另占一行');
must('下载页整页滚动', downloads, /item\(key = "__header__"\)/,
  '版本统计与搜索框要作为 LazyColumn 的第 0 项，跟列表一起滚走');
must('下载页头部随滚动淡出', downloads, /graphicsLayer\s*\{[\s\S]{0,200}?alpha = headerAlpha/,
  '头部要用 graphicsLayer 按滚动距离淡出（draw 阶段读 state，不逐帧重组）');
must('下载页搜索框不用浮动 label', downloads, /placeholder = \{ Text\("搜版本号/,
  'label 会额外占 16dp 并让输入框长到 72dp，改用 placeholder');

// —— 下载页：附属内容（模组 / 光影 / 材质 / 地图）——
must('下载页五个分类', downloads, /VERSION\("版本"[\s\S]{0,260}?MAP\("地图"/,
  '分类应含 版本 / 模组 / 光影 / 材质 / 地图');
must('下载页按分类切内容', downloads, /ContentTab\(kind = kind, instance = target/,
  '非版本分类交给 ContentTab(kind, instance)');
must('下载页目标版本可选', downloads, /DropdownMenu\(expanded = menuOpen/,
  '内容要装进某个已装版本，必须有目标版本选择器');
must('下载页读已装实例', downloads, /container\.instanceStore\.instances\.collectAsState\(\)/,
  '目标版本来自 InstanceStore，不能另存一份');
must('下载页入口传容器', rootScreen, /DownloadsScreen\(\s*[\s\S]{0,80}?container = container/,
  'RootScreen 要把 AppContainer 传给下载页');
must('内容分栏按类目搜索', contentTab, /ModrinthClient\.searchProjects\(/, '走 searchProjects(type=…)');
must('内容分栏装到目标版本', contentTab, /ContentInstaller\.install\(/, '安装落到目标版本目录');
must('内容分栏无版本引导', contentTab, /"还没有版本"/, '没有版本时给去创建版本的入口');
must('内容落点 mods', contentInstaller, /ProjectType\.MOD, ProjectType\.MAP -> File\(instanceDir, "mods"\)/,
  '模组与地图（worldgen 模组）都进 mods/');
must('光影落点 shaderpacks', contentInstaller, /ProjectType\.SHADER -> File\(instanceDir, "shaderpacks"\)/,
  '光影是 zip，进 shaderpacks/');
must('材质落点 resourcepacks', contentInstaller, /ProjectType\.RESOURCE_PACK -> File\(instanceDir, "resourcepacks"\)/,
  '材质是 zip，进 resourcepacks/');
must('搜索按类目拼 facets', modrinth, /"project_type:" \+ type\.facet/,
  'facets 必须按类目拼，不能写死成 mod');
must('地图走 worldgen', modrinth, /MAP\("mod", "worldgen"\)/,
  'Modrinth 没有 world 类型，地图用 mod + worldgen 近似');
must('下载页搜索框不用浮动 label', downloads, /placeholder = \{ Text\("搜版本号/,
  'label 会额外占 16dp 并让输入框长到 72dp，改用 placeholder');

// —— 下载页：点版本进安装页（PCL 式安装预览）——
const versionInstall = read('app/src/main/java/dev/mineport/launcher/ui/downloads/VersionInstallScreen.kt');
must('点版本进安装页', downloads, /if \(pending != null\) \{\s*VersionInstallScreen\(/,
  '版本行点了要进安装页，不是直接弹确认框');
must('安装页有版本名输入', versionInstall, /BasicTextField\([\s\S]{0,900}?"版本名称"/,
  '要能改版本名（做进预览卡里，省横屏高度）');
must('安装页列 Minecraft 本体', versionInstall, /title = "Minecraft"/, '清单第一行是本体');
must('安装页可选 Fabric', versionInstall, /title = "Fabric"/, 'Fabric 行可选 loader 版本');
must('安装页 Forge 明确未支持', versionInstall, /title = "Forge"[\s\S]{0,160}?暂未支持/,
  'Forge 要显式说没接，不能装作能装');
must('安装页列 Fabric 版本', versionInstall, /FabricLoaderInstaller\.listLoaders\(/,
  'loader 版本要从 Fabric meta 拉，不能用默认值糊过去');
must('安装页开始安装按钮', versionInstall, /Text\(if \(busy\) "安装中…" else "开始安装"\)/,
  '底栏要有开始安装');
must('安装页先装原版再装加载器', versionInstall,
  /if \(!vanillaJson\.isFile\)[\s\S]{0,1200}?FabricLoaderInstaller\.install\(/,
  '原版没装要先装本体，再装 Fabric');
must('安装页落成实例', versionInstall, /container\.instanceStore\.upsert\(instance\)/,
  '装完要建版本记录，否则下载页目标里看不到');
must('安装页跳过已装原版', versionInstall, /versions\/\$\{version\.id\}\/\$\{version\.id\}\.json/,
  '已经装过原版时不该重复下 200MB');

// —— 更多页 ——
must('更多页版本管理', more, /"版本管理"/, '更多页要有版本管理入口');
must('更多页诊断入口', more, /"诊断与日志"/, '更多页要有诊断入口');
must('更多页关于信息', more, /AboutCard\(container\.deviceProfile\)/, '更多页展示设备画像');

// —— UX-20 动效令牌 ——
must('动效四档时长', motion, /MicroMs = 120[\s\S]{0,200}?SmallMs = 180[\s\S]{0,200}?MediumMs = 260[\s\S]{0,200}?LargeMs = 340/,
  'UX-20 时长表：micro 120 / small 180 / medium 260 / large 340');
must('页面转场用 medium', motion, /ScreenMs = MediumMs/, '页面切换统一走 260ms，不各写各的');
must('按压弹簧阻尼 0.8', motion, /PressSpring = spring<Float>\(dampingRatio = 0\.8f/,
  'UX-20：轻微回弹不晃，阻尼 0.8');
must('强调曲线 EaseOutCubic', motion, /val EaseOutCubic = CubicBezierEasing\(/, '数值滚动要强调曲线');
must('rail 选中图标 1.1', rootScreen, /targetValue = if \(selected\) 1\.1f else 1f/, 'UX-20：图标 1.0 → 1.1');
must('rail 蓝条高度动画', rootScreen, /animateDpAsState\([\s\S]{0,200}?26\.dp else 0\.dp/,
  'UX-20：3dp 蓝条高度动画，不是硬跳');
const emptyState = read('app/src/main/java/dev/mineport/launcher/ui/components/EmptyState.kt');
must('空状态图标弹性入场', emptyState, /spring\(dampingRatio = 0\.62f[\s\S]{0,1200}?0\.375f/,
  'UX-20：空状态图标 24dp → 64dp 弹性入场');
must('动效关掉即终态', motion, /if \(!enabled\) \{[\s\S]{0,80}?snapTo\(1f\)/,
  '关掉动效后不能停在 0 透明度');

// —— 顶栏压缩：四页都不用 TopAppBar，改用 44dp 的 PageHeader ——
const pageHeader = read('app/src/main/java/dev/mineport/launcher/ui/components/PageHeader.kt');
const settings = read('app/src/main/java/dev/mineport/launcher/ui/settings/SettingsScreen.kt');
must('PageHeader 存在', pageHeader, /fun PageHeader\(/, '四页共用的紧凑标题行');
must('PageHeader 用 headlineSmall', pageHeader, /style = MaterialTheme\.typography\.headlineSmall/,
  '标题字号跟 TopAppBar 一致，只是行高更紧');
must('PageHeader 垂直内边距 6dp', pageHeader, /top = 6\.dp[\s\S]{0,40}?bottom = 2\.dp/,
  'UX-14：横屏一行 44dp，比 TopAppBar 的 64dp 省 20dp');
for (const [label, src] of [['设置', settings], ['个性化', personalize], ['更多', more], ['版本', instances]]) {
  mustNot(`${label}页不用 TopAppBar`, src, /TopAppBar\(/,
    'TopAppBar 在横屏吃掉一整行，四页统一换成 PageHeader');
  must(`${label}页用 PageHeader`, src, new RegExp('PageHeader\\('), '四页统一走 PageHeader');
}
must('设置页标题固定内容滚动', settings,
  /PageHeader\("设置"\)[\s\S]{0,240}?weight\(1f\)[\s\S]{0,200}?verticalScroll/, '标题不跟着内容滚走');
must('版本页标题行带返回', instances, /leading = if \(onBack == null\)/, 'onBack 为空时不显示返回');
must('版本页导入按钮进标题行', instances, /actions = \{[\s\S]{0,200}?"导入目录"/, '三个导入入口塞进行内 actions');
// —— UX-16 首页三栏 ——
const home = read('app/src/main/java/dev/mineport/launcher/ui/home/HomeScreen.kt');
must('首页用 PageHeader', home, /PageHeader\([\s\S]{0,80}?title = "启动"/, 'UX-16：标题行与 rail 对齐');
must('首页左右两栏', home, /HeroCard\([\s\S]{0,400}?Modifier\.weight\(1\.15f\)[\s\S]{0,400}?GalleryPane\([\s\S]{0,400}?weight\(0\.85f\)/,
  'UX-16：左主角卡 1.15、右版本画廊 0.85');
must('首页封面 16:7', home, /aspectRatio\(16f \/ 7f\)/, '横屏封面不能太高，否则启动按钮出屏');
must('首页封面用主色渐变', home, /Brush\.linearGradient\(/, '不依赖网络封面图，先按主题色生成');
must('首页加载器色块', home, /Loader\.FABRIC -> Color\(0xFFE8C9A0\)[\s\S]{0,400}?Loader\.VANILLA -> Color\(0xFFA0A8B4\)/,
  '加载器在列表里要一眼能分辨');
must('首页准备度三 chip', home, /"\+ 装 Fabric"[\s\S]{0,900}?"\+ 加光影"[\s\S]{0,500}?"轮盘 ✓"/,
  'UX-16：模组/光影/轮盘三连，未就绪时变成行动号召');
must('首页版本画廊两列', home, /LazyVerticalGrid\([\s\S]{0,160}?GridCells\.Fixed\(2\)/, '右栏是网格');
must('首页新建版本入口', home, /NewCard\(onClick = onDownload\)/, '最后一格进下载页');
must('首页扫准备度', home, /fun scanReady\([\s\S]{0,900}?shaderpacks/, '设备信息挪到更多页，首页只留准备度扫描');
must('首页启动走 GameActivity', home, /GameActivity\.intent\(context, primary\.id\)/, '启动入口只有一个，别绕回旧路径');
must('RootScreen 传下载入口', rootScreen, /onOpenDownloads = \{ navigateTab\("downloads"\) \}/, '「+ 装 Fabric」要能跳到下载页');

// —— UX-17 下载页分类完善：内容搜索分页 ——
must('搜索支持 offset', modrinth, /offset: Int = 0/, 'UX-17：加载更多靠 offset，不能只吃第一页');
must('URL 拼 offset', modrinth, /&limit=\$limit&offset=\$offset/, 'Modrinth search 的 offset 从 0 起');
must('每页 20 条', contentTab, /private const val PAGE_SIZE = 20/, '与 Modrinth 默认 limit 对齐');
must('加载更多按钮', contentTab, /Text\("加载更多（已有 "/, '滑到底再决定要不要下一页，省流量');
must('追加按 project_id 去重', contentTab, /val known = hits\.mapTo\(HashSet\(\)\) \{ it\.project_id \}[\s\S]{0,200}?known\.add\(it\.project_id\)/,
  '服务端排序会抖，同一项目可能跨页重复');
must('到底有提示', contentTab, /"共 " \+ hits\.size \+ " 条，到底了"/, '没有更多时要明确收尾，不能一直转圈');
must('换关键词重置列表', contentTab, /hits = emptyList\(\)[\s\S]{0,400}?searchProjects\(/, '新搜索必须清空旧页，否则 offset 会错位');

// —— UX-19 .mptheme 主题包 ——
const themePack = read('app/src/main/java/dev/mineport/launcher/ui/theme/ThemePack.kt');
const motionStage = read('app/src/main/java/dev/mineport/launcher/ui/motion/MotionStage.kt');
const commandBlock = read('app/src/main/java/dev/mineport/launcher/ui/motion/CommandBlock.kt');
const propOverlay = read('app/src/main/java/dev/mineport/launcher/ui/motion/PropOverlay.kt');
must('主题包模型', themePack, /@Serializable[\s\S]{0,80}?data class ThemePack\(/, 'UX-19：主题包是一个可序列化的清单');
must('主题包扩展名', themePack, /const val EXTENSION = "\.mptheme"/, '对外约定 .mptheme');
must('主题包是 zip', themePack, /ZipOutputStream\([\s\S]{0,4000}?ZipInputStream\(/, '以后要塞预览图/字体，json 直接当文件不够');
must('清单文件名 theme.json', themePack, /private const val MANIFEST = "theme\.json"/, '包内约定');
must('允许子目录清单', themePack, /substringAfterLast\('\/'\) == MANIFEST/, '别人打包常多套一层文件夹');
must('主题包校验', themePack, /fun problem\(pack: ThemePack\): String\?/, '格式版本/尺寸/透明度越界要拦');
must('主题包应用写 HUD', themePack, /padPrefs\.actionPadSizeDp = pack\.padSizeDp[\s\S]{0,120}?padPrefs\.actionPadAlpha = pack\.padAlpha/,
  '主题包一起带轮盘外观');
must('导出走 SAF', personalize, /ActivityResultContracts\.CreateDocument\(ThemePackStore\.MIME\)/, '不写死私有目录，用户能选保存位置');
must('导入走 SAF', personalize, /ActivityResultContracts\.OpenDocument\(\)/, '能直接从聊天软件/网盘选');
must('导入后刷新 HUD 镜像', personalize, /onApplied = \{[\s\S]{0,300}?hudSizeDp = padPrefs\.actionPadSizeDp/, 'PadPrefs 没有 Compose 状态，要手动同步');
must('自定义主色解析', prefs, /fun parseHex\(hex: String\): androidx\.compose\.ui\.graphics\.Color\?/, '主题包可以带任意颜色，不限于五个预设');
must('自定义主色回写', prefs, /fun toHex\(color: androidx\.compose\.ui\.graphics\.Color\): String/, '导出时要把当前色写成 #RRGGBB');
must('选预设清掉自定义', prefs, /accentCustom = null[\s\S]{0,120}?remove\(KEY_ACCENT_CUSTOM\)/, '否则个性化页会同时出现两个选中');
must('MainActivity 传自定义色', main, /Prefs\.accentColor\(prefs\.accent, prefs\.accentCustom\)/, '自定义色要真的进主题');
must('个性化页有自定义色位', personalize, /prefs\.accentCustom\?\.let \{ hex ->/, '导入任意色后要能看到并保留它');

// ── UX-22 T1：命令方块动画宿主 ─────────────────────────────
must('UX-22 动画宿主', motionStage, /class MotionStage\(/, '四层并行共用一个动画值容器');
must('换事件取消旧事件', motionStage, /private val mutex = MutatorMutex\(\)[\s\S]{0,2600}?mutex\.mutate/, '可打断靠 MutatorMutex，不靠手写取消');
must('方块动画值齐全', motionStage, /val blockY = Animatable\(0f\)[\s\S]{0,400}?val blockMaterial = Animatable\(0f\)/, '位移/缩放/旋转/材质都要能单独驱动');
must('碎裂与丝带预留', motionStage, /val propProgress = Animatable\(0f\)[\s\S]{0,400}?val shatter = Animatable\(0f\)/, 'T5/T6 直接接值，不用改宿主');
must('撞击用 spring 回弹', motionStage, /spring\(dampingRatio = 0\.30f, stiffness = 1100f\)/, '过冲一次比 tween 更像被撞');
must('白闪用关键帧', motionStage, /keyframes \{[\s\S]{0,300}?220/, '只有单帧级别的视觉才用关键帧');
must('关动效直接落终态', motionStage, /if \(reduceMotion\) \{[\s\S]{0,200}?snapTo/, '同一个入口，不做第二套逻辑');
must('逻辑阶段只做展示', motionStage, /var stage by mutableStateOf\(MotionStageState\.Idle\)/, 'State 只表达阶段，动画值走 Animatable');
must('事件枚举', motionStage, /enum class MotionIntent \{[\s\S]{0,200}?TabImpact/, 'T1 先接导航撞击');
must('方块纯 Canvas 绘制', commandBlock, /fun DrawScope\.drawCommandBlock\(accent: Color, material: Float, flash: Float\)/, '禁止切图，40dp→160dp 不糊');
must('等距三面', commandBlock, /val top = Path\(\)\.apply \{[\s\S]{0,600}?val right = Path\(\)\.apply/, '顶面菱形 + 左右侧面');
must('颜色跟主色', commandBlock, /lerp\(base, accent, material \* 0\.8f\)/, '唤醒程度由材质值控制');
must('动画只进 graphicsLayer', commandBlock, /graphicsLayer \{[\s\S]{0,300}?stage\.blockScale\.value/, '绘制阶段读 state，不触发重组');
mustNot('禁止重度模糊', commandBlock, /\.blur\(/, '模糊用径向渐变叠色替代');
mustNot('禁止第三方动画库', motionStage, /Lottie|WebView|ExoPlayer/, '只用 Compose 动画 API');
must('方块挂到外壳', rootScreen, /CommandBlock\([\s\S]{0,160}?stage = stage/, '方块常驻 rail 底部');
must('导航触发撞击', rootScreen, /stage\.play\(MotionIntent\.TabImpact\)/, '点 rail 任意项都撞一下');
// ── UX-22 T2：rail 拖拽 / 惯性 / 吸附 ─────────────────────
must('rail 可拖拽', rootScreen, /pointerInput\(maxPullPx\)[\s\S]{0,200}?detectVerticalDragGestures/, '橡皮筋物理要不依赖列表滚动容器');
must('拖 48dp 到满量程', rootScreen, /val maxPullPx = with\(density\) \{ 48\.dp\.toPx\(\) \}/, '太小会把点击误判成拖拽');
must('松手按速度弹回', rootScreen, /tracker\.calculateVelocity\(\)\.y \/ maxPullPx[\s\S]{0,80}?settleRail\(v \* 0\.35f\)/, '惯性方向要跟手指走');
must('惯性走 initialVelocity', motionStage, /initialVelocity = velocity/, 'spring() 本身没有速度参数，速度要给 animateTo');
mustNot('不用 animateDecay', motionStage, /animateDecay/, '吸附目标已知，decay 再 animateTo 会速度断点');
must('松手后落地抖动', motionStage, /railPull\.animateTo\([\s\S]{0,900}?blockScale\.animateTo\(0\.96f/, '先弹回再抖一下，才有「落地」感');
must('方块跟着下坠', commandBlock, /translationY = stage\.railPull\.value \* 14\.dp\.toPx\(\)/, '方块是导航的载体，要跟着被拽');
must('rail 橡皮筋位移', rootScreen, /translationY = stage\.railPull\.value \* 8\.dp\.toPx\(\)/, '整列只动 8dp，是拉橡皮筋不是滚动');
// ── UX-22 T3：道具撞击（下载箭头 / 设置齿轮）─────────────
must('道具层是独立 overlay', propOverlay, /fun PropOverlay\([\s\S]{0,400}?Canvas\(modifier\.fillMaxSize\(\)\)/, '道具跨度远超方块那 42dp，画进方块会被裁掉');
must('箭头纯 Path 绘制', propOverlay, /val path = Path\(\)\.apply \{[\s\S]{0,300}?lineTo\(tipX \+ len \* 0\.56f/, '禁止用切图，箭头要跟主色走');
must('齿轮 8 齿', propOverlay, /val points = 32/, '32 个采样点每 4 个交替半径 = 8 齿');
must('齿轮扣合旋转', propOverlay, /rotate\(degrees = -45f \+ 45f \* p/, '从斜插转到对正才是「扣合」');
must('齿轮中心挖孔', propOverlay, /PathOperation\.Difference/, 'overlay 是透明的，挖孔不能用背景色盖');
must('冲入用减速曲线', motionStage, /CubicBezierEasing\(0\.1f, 0\.9f, 0\.2f, 1f\)/, '前 60% 走完 90% 距离，撞前减速才有重量');
must('撞击后道具弹开', motionStage, /propRecoil\.animateTo\(1f/, '被顶回来才有反作用力');
must('道具用枚举而不是位图', motionStage, /enum class MotionProp \{ None, Arrow, Gear, Brush \}/, '以后加刷子/丝带只需加枚举值');
must('下载页用箭头', rootScreen, /"downloads" -> stage\.play\(MotionIntent\.DownloadImpact\)/, '按 tab 分派不同道具');
must('设置页用齿轮', rootScreen, /"settings" -> stage\.play\(MotionIntent\.SettingsImpact\)/, '设置=精密扣合，下载=暴力撞击');
must('方块位置上报给 overlay', commandBlock, /coords\.boundsInRoot\(\)\.center/, 'overlay 要知道往哪儿飞');
mustNot('道具不用 ImageVector', propOverlay, /ImageVector|painterResource/, '矢量图也要 Canvas 手绘');
// ── UX-22 T4：个性化刷子 + 材质逐段显现 ─────────────────
must('个性化页用刷子', rootScreen, /"personalize" -> stage\.play\(MotionIntent\.PersonalizeBrush\)/, '每个 tab 有专属道具，个性化=粉刷');
must('刷子是独立枚举', motionStage, /enum class MotionProp \{ None, Arrow, Gear, Brush \}/, '刷子和箭头/齿轮共用一套 overlay');
must('刷子与材质同源同步', motionStage, /launch \{ blockMaterial\.animateTo\(1f, tween\(Motion\.MediumMs, easing = LinearEasing\)\) \}/, '刷到哪亮到哪，两条时间轴必须一致');
must('材质用 clipRect 裁切', commandBlock, /clipRect\(right = size\.width \* mat\)/, '免逐像素混合，O(1) 裁切');
must('底层保留命令方块原色', commandBlock, /drawCommandBlock\(accent = accent, material = 0f, flash = flash\)/, '刷不到的地方还得是绿的');
must('刷柄斜 18 度', propOverlay, /rotate\(degrees = 18f, pivot = Offset\(x, y\)\)/, '正着画像块橡皮');
must('刷完方块轻抬', motionStage, /blockScale\.animateTo\(1\.03f, tween\(90, easing = Motion\.Ease\)\)/, '刷完要"活"过来一下');
mustNot('材质不做模糊过渡', commandBlock, /Modifier\.blur|RenderEffect/, '红线：禁重度 blur');
let failed = 0;
for (const check of checks) {
  if (!check.ok) failed += 1;
  console.log(`${check.ok ? 'PASS' : 'FAIL'}  ${check.name}${check.ok ? '' : `  ← ${check.detail}`}`);
}
console.log(`\n${checks.length - failed}/${checks.length} passed`);
process.exit(failed === 0 ? 0 : 1);
