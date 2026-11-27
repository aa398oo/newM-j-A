#!/usr/bin/env node
// 校验 Kotlin external 声明与 C++ 导出的 JNI 符号一一对应。
//
// 规则：Java_ + 包名(点换下划线) + "_" + 类/对象名 + "_" + 方法名。
// 这条一旦对不上，真机上就是 UnsatisfiedLinkError（而且往往要跑起来才看得到）。
//
// 用法：node scripts/check-jni.mjs [项目根目录，默认当前目录]

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const scriptDir = import.meta.dirname ?? ".";
const root = process.argv[2] || scriptDir + "/..";
const ktDir = join(root, "app/src/main/java");
const cppDir = join(root, "app/src/main/cpp");

function walk(dir, ext, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) walk(full, ext, out);
    else if (name.endsWith(ext)) out.push(full);
  }
  return out;
}

function decodeSymbol(sym) {
  // JNI 对下划线有 _1 转义，这里只处理项目里实际出现的简单标识符。
  return sym.replaceAll("_1", "_").replaceAll("_", ".");
}

const expected = [];

for (const file of walk(ktDir, ".kt")) {
  const text = readFileSync(file, "utf8");
  if (!text.includes("external fun")) continue;
  const rel = relative(root, file).replaceAll("\\", "/");
  const pkgMatch = text.match(/^package\s+([\w.]+)/m);
  if (!pkgMatch) {
    console.log(`FAIL ${rel}: 有 external fun 但没有 package 声明`);
    continue;
  }
  const pkg = pkgMatch[1];
  const lines = text.split("\n");
  let owner = null;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const ownerMatch = line.match(/^\s*(?:public\s+|internal\s+|private\s+)?(?:object|class)\s+(\w+)/);
    if (ownerMatch) owner = ownerMatch[1];
    const funMatch = line.match(/external\s+fun\s+(\w+)/);
    if (funMatch) {
      if (!owner) {
        console.log(`FAIL ${rel}:${i + 1}: 无法确定 external fun 所在的类/对象`);
        continue;
      }
      expected.push({
        symbol: ["Java", ...pkg.split("."), owner, funMatch[1]].join("_"),
        where: `${rel}:${i + 1}`,
      });
    }
  }
}

const cppText = walk(cppDir, ".cpp").map((f) => readFileSync(f, "utf8")).join("\n");
const exported = new Set([...cppText.matchAll(/\b(Java_[A-Za-z0-9_]+)\s*\(/g)].map((m) => m[1]));

let errors = 0;
for (const { symbol, where } of expected) {
  if (!exported.has(symbol)) {
    errors++;
    console.log(`FAIL ${where}: Kotlin 声明 ${decodeSymbol(symbol)} 在 C++ 里没有导出 ${symbol}`);
  }
}

for (const symbol of exported) {
  if (!expected.some((e) => e.symbol === symbol)) {
    errors++;
    console.log(`FAIL C++ 导出了 ${symbol}（${decodeSymbol(symbol)}），但 Kotlin 侧没有对应 external 声明`);
  }
}

console.log(`checked ${expected.length} native declarations against ${exported.size} exports, ${errors} errors`);
process.exit(errors > 0 ? 1 : 0);
