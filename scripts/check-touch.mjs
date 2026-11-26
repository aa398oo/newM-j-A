#!/usr/bin/env node
/**
 * 触控链路静态自检。
 *
 * 无手机时用它对「事件从手指到 MC」这条链上的关键契约做断言，
 * 防止后续重构悄悄退回旧语义（DOWN 不按、UP 才补发、尺寸双真源等）。
 *
 * 用法：node scripts/check-touch.mjs
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

const checks = [];
const add = (name, ok, detail = '') => checks.push({ name, ok, detail });
const must = (name, src, pattern, why) =>
  add(name, src !== null && pattern.test(src), src === null ? '文件缺失' : why);

const host = read('app/src/main/java/dev/mineport/launcher/game/GameHost.kt');
const activity = read('app/src/main/java/dev/mineport/launcher/game/GameActivity.kt');
const jni = read('app/src/main/cpp/jni_bridge.cpp');
const render = read('app/src/main/cpp/render_bridge.cpp');
const surface = read('app/src/main/java/dev/mineport/launcher/game/GameSurfaceView.kt');

// B1：DOWN 必须立即 press，且按下坐标取 DOWN 点。
must('B1 DOWN 立即 press', host, /ACTION_DOWN[\s\S]{0,900}?inject\(downX, downY, ACTION_PRESS\)/,
  'TouchInjector 在 DOWN 分支里调用 inject(downX, downY, ACTION_PRESS)');
must('B1 自动 release 33ms', host, /CLICK_RELEASE_MS\s*=\s*33L/, 'CLICK_RELEASE_MS = 33L');
must('B1 长按 300ms', host, /LONG_PRESS_MS\s*=\s*300L/, 'LONG_PRESS_MS = 300L');
must('B1 长按取消自动松开', host, /longHeld\s*=\s*true/, 'longPressArm 把 longHeld 置 true');
must('B1 clickRelease 尊重 longHeld', host, /clickRelease[\s\S]{0,200}?held\s*&&\s*!longHeld/,
  'clickRelease 只在 held 且非长按时 release');

// B3：CANCEL 必须复位 FCL 输入状态。
must('B3 CANCEL 复位输入态', host, /ACTION_CANCEL[\s\S]{0,400}?nativeResetInputState\(\)/,
  'CANCEL 分支调用 nativeResetInputState');

// 单一路径：不能有真的监听器/回调实现（注释里提到历史做法不算）。
const codeOnly = (src) => (src ?? '').split('\n').filter((l) => !l.trim().startsWith('//')).join('\n');
add('无重复触摸路径',
  !/fun onTouchEvent\(/.test(codeOnly(surface)) && !/setOnTouchListener\s*[({]/.test(codeOnly(activity)),
  'GameSurfaceView 无 onTouchEvent 实现，GameActivity 无 setOnTouchListener 调用');

// B2：Surface 尺寸是唯一真源（onCreate 允许一次 displayMetrics 兜底，jvm-ready 后不许再覆盖）。
must('B2 bridgeOnSurfaceSize 定义', jni, /extern\s+"C"\s+void\s+bridgeOnSurfaceSize\(int\s+width,\s*int\s+height\)/,
  'jni_bridge.cpp 导出 bridgeOnSurfaceSize');
must('B2 nativeResize 调用它', render, /bridgeOnSurfaceSize\(width,\s*height\)/,
  'render_bridge.cpp nativeResize 末尾调用');
const metricsSets = (codeOnly(activity).match(/nativeSetScreenSize\(/g) ?? []).length;
add('B2 仅在 onCreate 兜底一次', metricsSets <= 1, 'nativeSetScreenSize 调用次数 = ' + metricsSets);

// 输入通道：critical_* 优先，反射兜底。
must('critical 通道已解析', jni, /critical_send_cursor_pos/, 'dlsym 了 critical_send_cursor_pos');
must('critical 队列开启', jni, /SetStackqueue\(1\)/, '启动时调用 critical_set_stackqueue(1)');
must('反射兜底保留', jni, /ensureMcInput\(/, '仍保留 ensureMcInput 反射路径');

// 观测：touch.log 落在 filesDir（run-as 可读）。
must('touch.log 可读路径', host, /filesDir,\s*"touch\.log"/, 'trace 写 files/touch.log');

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
