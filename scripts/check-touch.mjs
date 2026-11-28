#!/usr/bin/env node
/**
 * 触控链路静态自检。
 *
 * 无手机时用它对「事件从手指到 MC」这条链上的关键契约做断言，
 * 防止后续重构悄悄退回旧语义（DOWN 不按、UP 才补发、尺寸双真源等）。
 *
 * 用法：node scripts/check-touch.mjs
 */
import { readFileSync, readdirSync } from 'node:fs';
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
  add(name, src !== null && pattern.test(src), src === null ? '文件缺失' : why);

const host = read('app/src/main/java/dev/mineport/launcher/game/GameHost.kt');
const activity = read('app/src/main/java/dev/mineport/launcher/game/GameActivity.kt');
const jni = read('app/src/main/cpp/jni_bridge.cpp');
const render = read('app/src/main/cpp/render_bridge.cpp');
const surface = read('app/src/main/java/dev/mineport/launcher/game/GameSurfaceView.kt');
const renderer = read('app/src/main/java/dev/mineport/launcher/game/NativeRenderer.kt');

// B1：DOWN 必须立即 press，且按下坐标取 DOWN 点（多点触控改造后入口是 pointerDown）。
must('B1 DOWN 立即 press', host, /fun pointerDown\([\s\S]{0,900}?inject\(x, y, ACTION_PRESS\)/,
  'TouchInjector.pointerDown 里调用 inject(x, y, ACTION_PRESS)');
must('B1 自动 release 33ms', host, /CLICK_RELEASE_MS\s*=\s*33L/, 'CLICK_RELEASE_MS = 33L');
must('B1 长按 300ms', host, /LONG_PRESS_MS\s*=\s*300L/, 'LONG_PRESS_MS = 300L');
must('B1 长按取消自动松开', host, /longHeld\s*=\s*true/, 'longPressArm 把 longHeld 置 true');
must('B1 clickRelease 尊重 longHeld', host, /clickRelease[\s\S]{0,200}?held\s*&&\s*!longHeld/,
  'clickRelease 只在 held 且非长按时 release');

// B3：CANCEL 必须复位 FCL 输入状态（多点触控后收敛到 cancelAll）。
add('B3 CANCEL 走 cancelAll', /ACTION_CANCEL[\s\S]{0,400}?cancelAll\(\)/.test(codeOnly(host)),
  'ACTION_CANCEL -> injector.cancelAll()');
add('B3 cancelAll 复位输入态', /fun cancelAll\(\)[\s\S]{0,900}?nativeResetInputState\(\)/.test(codeOnly(host)),
  'cancelAll() 内调用 nativeResetInputState()');

// 单一路径：不能有真的监听器/回调实现（注释里提到历史做法不算）。
add('无重复触摸路径',
  !/fun onTouchEvent\(/.test(codeOnly(surface)) && !/setOnTouchListener\s*[({]/.test(codeOnly(activity)),
  'GameSurfaceView 无 onTouchEvent 实现，GameActivity 无 setOnTouchListener 调用');

// B2：Surface 尺寸是唯一真源（onCreate 允许一次 displayMetrics 兜底，jvm-ready 后不许再覆盖）。
must('B2 bridgeOnSurfaceSize 定义', jni, /extern\s+"C"\s+void\s+bridgeOnSurfaceSize\(int\s+width,\s*int\s+height\)/,
  'jni_bridge.cpp 导出 bridgeOnSurfaceSize');
must('B2 nativeResize 调用它', render, /bridgeOnSurfaceSize\(width,\s*height\)/,
  'render_bridge.cpp nativeResize 末尾调用');
// B2 修订：尺寸写入点必须在 game/ 目录里全局唯一（之前只扫 GameActivity，漏掉
// GameSurfaceView.surfaceChanged 的第二处写入，给了假绿灯）。
const gameDir = 'app/src/main/java/dev/mineport/launcher/game';
const gameFiles = readdirSync(join(root, gameDir))
  .filter((f) => f.endsWith('.kt'))
  .map((f) => ({ name: f, text: read(gameDir + '/' + f) }));
// NativeRenderer.kt 里是 external 声明本身，不算调用站点。
const metricsSites = gameFiles
  .filter((f) => f.name !== 'NativeRenderer.kt')
  .filter((f) => /nativeSetScreenSize\s*\(/.test(codeOnly(f.text)))
  .map((f) => f.name);
add('B2 nativeSetScreenSize 调用站点仅 GameActivity 一处', metricsSites.length <= 1 && (metricsSites[0] ?? 'GameActivity.kt') === 'GameActivity.kt',
  '调用站点 = ' + (metricsSites.join(',') || 'none'));

// 键盘口径：按下必须传 KEY_PRESS(1)、松手传 KEY_RELEASE(0)。
must('KEY_PRESS/KEY_RELEASE 常量已定义', renderer, /const\s+val\s+KEY_PRESS\s*=\s*1[\s\S]*const\s+val\s+KEY_RELEASE\s*=\s*0|const\s+val\s+KEY_RELEASE\s*=\s*0[\s\S]*const\s+val\s+KEY_PRESS\s*=\s*1/,
  'NativeRenderer 定义常量');
const wheelPress = (codeOnly(activity).match(/nativeSendKey\([^)]*KEY_PRESS\)/g) ?? []).length;
const wheelRelease = (codeOnly(activity).match(/nativeSendKey\([^)]*KEY_RELEASE\)/g) ?? []).length;
add('轮盘按下传 KEY_PRESS、松手传 KEY_RELEASE', wheelPress === 1 && wheelRelease === 1,
  'KEY_PRESS 站点 ' + wheelPress + ' / KEY_RELEASE 站点 ' + wheelRelease);

// 多点触控（十指）：归属必须按 pointerId 记，不能用整条手势的单一 owner。
add('多指归属按 pointerId 记账', /SparseIntArray/.test(codeOnly(host)) && /owners\.put\(/.test(codeOnly(host)),
  'GameHostLayout 用 SparseIntArray 记 pointerId -> owner');
add('注入器有 pointerDown/Move/Up 三入口',
  /fun pointerDown\(/.test(codeOnly(host)) && /fun pointerMove\(/.test(codeOnly(host))
  && /fun pointerUp\(/.test(codeOnly(host)),
  'TouchInjector 暴露 pointerDown/pointerMove/pointerUp');
add('轮盘命中在宿主里手动算（不依赖 Compose 触摸）',
  /private fun pickWheelIndex\(/.test(codeOnly(host)) && /private fun inWheelCircle\(/.test(codeOnly(host)),
  'GameHostLayout 自己算扇区，Compose 只负责画');
add('ActionWheel 不再接收触摸', !/pointerInput/.test(codeOnly(read('app/src/main/java/dev/mineport/launcher/input/ActionWheel.kt'))),
  'ActionWheel 已去掉 pointerInput，改为 active 参数驱动');
add('不再使用 PopupWindow 装轮盘', !/PopupWindow\s*\(/.test(codeOnly(activity)),
  'GameActivity 不再 new PopupWindow');

// 输入通道：critical_* 优先，反射兜底。
must('critical 通道已解析', jni, /critical_send_cursor_pos/, 'dlsym 了 critical_send_cursor_pos');
must('critical 队列开启', jni, /SetStackqueue\(1\)/, '启动时调用 critical_set_stackqueue(1)');
must('反射兜底保留', jni, /ensureMcInput\(/, '仍保留 ensureMcInput 反射路径');

// 观测：touch.log 落在 filesDir（run-as 可读）。
must('touch.log 可读路径', host, /filesDir,\s*"touch\.log"/, 'trace 写 files/touch.log');

// ── UX-21 局内按键：收起/展开、死区、鼠标键通道 ────────────────────────────
const pad = read('app/src/main/java/dev/mineport/launcher/input/ActionPad.kt');
add('UX-21 收起画小球 / 展开画轮盘',
  /if \(actionPad\.expanded\)[\s\S]{0,200}?ActionWheel\(/.test(codeOnly(activity))
  && /ActionPadOverlay\(/.test(codeOnly(activity)),
  'GameActivity 按 actionPad.expanded 在 ActionWheel / ActionPadOverlay 间切换');
add('UX-21 收起态小球命中 56dp',
  /ballHitPx[\s\S]{0,60}?28f \* density/.test(codeOnly(host)),
  '收起态只让 28dp 半径（56dp 直径）吃触摸，其余交给摇杆/游戏');
add('UX-21 中心死区 32dp 绝对值',
  /centerPx[\s\S]{0,60}?32f \* density/.test(codeOnly(host)),
  '中心死区与收起热区都用 32dp 绝对值，不用半径比例');
add('UX-21 不再写死 24f 内径',
  !/innerPx\s*=\s*24f/.test(codeOnly(host)),
  'innerPx 由 centerPx（dp 换算）传入，不再写裸像素');
add('UX-21 点球只展开不双切换',
  /private fun claimWheel\([\s\S]{0,900}?pad\.expand\(\)[\s\S]{0,160}?return true/.test(codeOnly(host)),
  'claimWheel 展开后立刻 return，不再落到「点中心收起」分支');
add('UX-21 收起态不参与扇区命中',
  /!pad\.expanded\)\s*return -1/.test(codeOnly(host)),
  'pickWheelIndex 在收起态返回 -1');
add('UX-21 收起前先松键',
  /fun collapse\(\)\s*\{\s*up\(\)/.test(codeOnly(pad)),
  'ActionPad.collapse() 先 up()，避免收起来时按键卡在按下态');
add('UX-21 手指搭在轮盘上不自动收起',
  /pointersDown\s*>\s*0/.test(codeOnly(pad)),
  'autoCollapseIfIdle 检查 pointersDown，4 秒内有手指就保持展开');

must('UX-21 鼠标键 JNI 入口', jni,
  /Java_dev_mineport_launcher_game_NativeRenderer_nativeSendMouseButton/,
  'jni_bridge.cpp 导出 nativeSendMouseButton');
must('UX-21 鼠标键走 critical_send_mouse_button', jni,
  /g_criticalMouseButton\(button,\s*action == 0 \? 0 : 1, 0\)/,
  'nativeSendMouseButton 调 critical_send_mouse_button');
must('UX-21 NativeRenderer 声明鼠标键', renderer,
  /external fun nativeSendMouseButton\(button: Int, action: Int\)/,
  'Kotlin 侧 external fun nativeSendMouseButton');
add('UX-21 攻/副两槽接通 native',
  (codeOnly(activity).match(/nativeSendMouseButton\(/g) ?? []).length >= 4,
  '按下/松开各两处（左键+右键）');
add('UX-21 鼠标键不再只记日志', !/鼠标键暂未接通/.test(activity),
  'GameActivity 不再对 MOUSE_* 只 Log.w');

const failed = checks.filter((c) => !c.ok);
for (const c of checks) {
  console.log((c.ok ? '  OK   ' : ' FAIL  ') + c.name + '  —  ' + c.detail);
}
console.log('');
if (failed.length === 0) {
  console.log('[check-touch] ' + checks.length + ' 项全部通过');
  process.exit(0);
}
console.log('[check-touch] ' + failed.length + '/' + checks.length + ' 项失败');
process.exit(1);
