# 品牌与命名（v3）

> **v3 变更（2026-11-19，用户决定）**：**newM-j-A 是工程代号（codename），不是最终品牌名。** 正式名等产品功能完成后再定。
> v1 的「矿港 / Mineport」退回候选池，不作废。
> 开发期所有显示位置（Gradle 项目名、app_name、文档标题）统一用 newM-j-A，目的只是消除多处命名不一致，不代表品牌承诺——对外分发物料上可以直接替换，成本极低。

## 一、结论

- 当前代号（项目名 + 软件显示名）：**newM-j-A** —— 仅供开发期使用
- 正式品牌名：**待定（TBD）**，在功能基本完成、准备对外大规模分发前确定
- 落地位置：settings.gradle.kts 的 rootProject.name、res/values/strings.xml 的 app_name
- 包名 **dev.mineport.launcher** 与代号无关，属于技术标识，不动。改包名会让已安装用户变成桌面上两个 App，所以包名只在首次商店发布前有唯一一次改动窗口。
- 内部技术名（MineportApplication / Theme.Mineport / MineportTheme / 日志 TAG / 线程名）同样不动：用户永远看不到，改名的收益为零、出错风险不为零。

### 候选池（正式命名时从这里起步）

| 候选 | 状态 | 备注 |
| --- | --- | --- |
| 矿港 / Mineport | 备选 | 中文优先策略，撞名风险中低，此前已做过一轮公开检索 |
| newM-j-A | 当前代号 | 极客感强、绝对干净，但口播与输入成本高，不适合做正式名 |

## 二、撞名核查（公开检索，非法律意见）

此前对 Mineport 做过一轮检索，结论保留（对正式命名仍有参考价值）：

| 名称 | 已有使用 | 风险 |
| --- | --- | --- |
| Mineport | Minecraft 服务器名（minerank.com/mineport）、Bukkit 插件 | 中低（不同类目） |
| Mine Port / Mine Portal | Android APK com.mw.mineportal（MC 类） | 中（同领域，最接近） |
| MiiNePort | Moxa 工业串口设备（含商标） | 中低 |
| Voxport | voxport.live / pypi 包 | 高（软件产品） |
| Blockhaven | Android APK「Blockhaven craft」（MC 类） | 高（同领域） |

**当前代号 newM-j-A 未做任何商标检索**，但作为代号无需检索——它不会印在分发物料上。正式名一旦选定，必须重做这一节。

## 三、品牌要素（待正式名确定后重写）

- 名称：newM-j-A（代号，待替换）
- 主色：信号青绿 #22D3A6（见 DESIGN-STYLE.md，与名称无关，可保留）
- 图标方向：暂按「字母 N 的方块化几何」做占位（一个方形容器 + 斜向缺口，青绿单色、不用渐变）；正式名确定后重做
- 启动动画：方块从中心堆叠成形，1.2 秒（沿用）
- 对外一句话：待定（不与代号绑定）
- 禁止表述：「FCL 手机版」「FCL 续作」「基于 FCL」（改用「复用开源社区的 JVM 与渲染方案」）

## 四、已完成的技术标识修改（2026-11-19 执行）

| 位置 | 现状 |
| --- | --- |
| app/build.gradle.kts namespace | dev.mineport.launcher |
| app/build.gradle.kts applicationId | dev.mineport.launcher |
| 源码目录 | src/main/java/dev/mineport/launcher |
| AndroidManifest 组件 | .MineportApplication / .game.GameActivity |
| cpp/jni_bridge.cpp | 导出符号 Java_dev_mineport_launcher_jvm_JvmLauncher_*（JNI 名随包名，改名必须同步） |
| strings.xml app_name | newM-j-A（代号） |
| settings.gradle.kts rootProject.name | newM-j-A（代号） |
| themes.xml | Theme.Mineport / Theme.Mineport.Game |
| proguard-rules.pro | keep dev.mineport.launcher.** |

> **不要改** com.tungsten.fclcore.** 的 keep 规则——那是上游 FCLCore 的包名，属于第三方库引用。

## 五、正式命名的执行清单（定名时照做）

### 筛选标准（四条，缺一不可）

1. **一次听写正确**：语音里念一遍，听者能直接拼出来，不需要问"哪个字母大写"。
2. **能当词念**：是一个可发音的整词，不是断续的字母组合。
3. **有语义联想**：能让人联想到「手机」「Java 版」「Minecraft」中的至少一个。
4. **避雷词**：不含 FCL、PCL、Minecraft、Mojang 等受保护或易引起官方联想的词。

### 核查项（三条）

1. 中国商标网：第 9 类（软件）、第 42 类（技术服务）有无相同或近似。
2. 同领域撞名：GitHub、Modrinth、Google Play、B站/贴吧已有项目。
3. 域名与社交账号可用性（.com / .dev / .app + GitHub 组织名）。

### 时间点

- 不晚于 **P2（模组生态 + 动作轮盘）结束**，即开始对外大规模分发之前。
- 改名成本：strings.xml 一行 + settings.gradle.kts 一行 + 文档若干，**一小时内**可完成；包名若也要品牌化，则须在首次商店发布前一次性迁移（已安装用户需卸载重装，属可接受代价）。

### 待办

1. 正式命名（按上面四条标准 + 三项核查）
2. 图标与启动动画定稿（与正式名同步）
3. 对外文案统一（README、商店描述、分享卡片里只出现正式名）
