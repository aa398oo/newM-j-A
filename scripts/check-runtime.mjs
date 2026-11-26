#!/usr/bin/env node
// 校验运行时二进制的 ABI，并顺带检查 16 KB 页兼容性（决策台账未决项 2）。
//
// 用法：
//   node scripts/check-runtime.mjs            有错则退出码 1
//   node scripts/check-runtime.mjs --warn-only  只报告，不因缺文件失败
//
// 检查范围：
//   app/src/main/jniLibs/arm64-v8a/*.so   （System.loadLibrary / dlopen 依赖解析用）
//   app/src/main/assets/runtime/**/*.so   （解到 filesDir 后 dlopen libjvm 用）
//
// 判定：ELF64、小端、e_machine=0xB7(AArch64)；PT_LOAD 的 p_align < 0x4000 时提示
// 16 KB 页风险（Android 15+ 部分设备 getconf PAGE_SIZE=16384，未重打包会
// “has invalid ELF program header”）。

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const root = process.cwd();
const warnOnly = process.argv.includes("--warn-only");
const errors = [];
const warnings = [];

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) walk(full, out);
    else if (name.endsWith(".so")) out.push(full);
  }
  return out;
}

function checkElf(file) {
  const buf = readFileSync(file);
  const rel = relative(root, file);
  if (buf.length < 64 || buf[0] !== 0x7f || buf[1] !== 0x45 || buf[2] !== 0x4c || buf[3] !== 0x46) {
    errors.push(`${rel}: 不是 ELF 文件`);
    return null;
  }
  if (buf[4] !== 2) {
    errors.push(`${rel}: 不是 64 位 ELF（class=${buf[4]}）`);
    return null;
  }
  if (buf[5] !== 1) {
    errors.push(`${rel}: 不是小端 ELF`);
    return null;
  }
  const machine = buf.readUInt16LE(18);
  if (machine !== 0xb7) {
    errors.push(`${rel}: 架构不是 AArch64（e_machine=0x${machine.toString(16)}）`);
    return null;
  }

  // 读 PT_LOAD 的 p_align，用于 16 KB 页判断。
  const phoff = Number(buf.readBigUInt64LE(32));
  const phentsize = buf.readUInt16LE(54);
  const phnum = buf.readUInt16LE(56);
  let minAlign = null;
  for (let i = 0; i < phnum; i++) {
    const off = phoff + i * phentsize;
    if (off + 56 > buf.length) break;
    const type = buf.readUInt32LE(off);
    if (type !== 1) continue; // PT_LOAD
    const align = Number(buf.readBigUInt64LE(off + 48));
    if (align > 0 && (minAlign === null || align < minAlign)) minAlign = align;
  }
  if (minAlign !== null && minAlign < 0x4000) {
    warnings.push(
      `${rel}: PT_LOAD align=0x${minAlign.toString(16)}（< 0x4000），在 16 KB 页设备上可能 dlopen 失败`
    );
  }
  return { machine, minAlign };
}

const jniLibs = walk(join(root, "app/src/main/jniLibs/arm64-v8a"));
const assetRuntime = walk(join(root, "app/src/main/assets/runtime"));
const all = [...jniLibs, ...assetRuntime];

if (all.length === 0) {
  const msg = "没有找到任何 .so：jniLibs 与 assets/runtime 都是空的";
  if (warnOnly) warnings.push(msg);
  else errors.push(msg);
}

for (const file of all) checkElf(file);

const hasLibjvm = all.some((f) => f.replaceAll("\\", "/").endsWith("libjvm.so"));
if (!hasLibjvm) {
  const msg = "缺少 libjvm.so（应在 app/src/main/assets/runtime/lib/server/ 或 jniLibs/arm64-v8a/）";
  if (warnOnly) warnings.push(msg);
  else errors.push(msg);
}

for (const w of warnings) console.log("WARN " + w);
for (const e of errors) console.log("FAIL " + e);
console.log(
  `checked ${all.length} .so (jniLibs ${jniLibs.length}, assets ${assetRuntime.length}), ` +
    `${errors.length} errors, ${warnings.length} warnings`
);

process.exit(errors.length > 0 ? 1 : 0);
