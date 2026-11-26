import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, extname, relative } from "node:path";

const root = process.argv[2] || ".";
const exts = new Set([".kt", ".kts", ".cpp", ".h", ".hpp"]);
const files = [];

function walk(dir) {
  for (const name of readdirSync(dir)) {
    if (name === "build" || name === ".gradle" || name === ".git" || name === ".cxx") continue;
    if (name === ".toolchain" || name === ".runtime-cache" || name === ".idea" || name === ".kotlin") continue;
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) walk(full);
    else if (exts.has(extname(name))) files.push(full);
  }
}
walk(root);

function strip(text) {
  let out = "";
  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text[i];
    const c2 = text[i + 1];
    if (c === "/" && c2 === "/") {
      while (i < n && text[i] !== "\n") i++;
      continue;
    }
    if (c === "/" && c2 === "*") {
      i += 2;
      while (i < n && !(text[i] === "*" && text[i + 1] === "/")) i++;
      i += 2;
      continue;
    }
    if (c === "R" && c2 === '"' && text[i + 2] === "(") {
      i += 3;
      while (i < n && !(text[i] === ")" && text[i + 1] === '"')) i++;
      i += 2;
      continue;
    }
    if (c === '"' || c === "'") {
      const quote = c;
      i++;
      while (i < n) {
        if (text[i] === "\\") { i += 2; continue; }
        if (text[i] === quote) { i++; break; }
        i++;
      }
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

const pairs = { ")": "(", "]": "[", "}": "{" };
let bad = 0;
let totalLines = 0;

for (const file of files) {
  const raw = readFileSync(file, "utf8");
  totalLines += raw.split("\n").length;
  const code = strip(raw);
  const stack = [];
  const lines = code.split("\n");
  let broken = null;
  for (let ln = 0; ln < lines.length && !broken; ln++) {
    for (const ch of lines[ln]) {
      if ("([{".includes(ch)) stack.push({ ch, ln: ln + 1 });
      else if (")]}".includes(ch)) {
        const top = stack.pop();
        if (!top || top.ch !== pairs[ch]) broken = "line " + (ln + 1) + ": unexpected " + ch;
      }
    }
  }
  if (!broken && stack.length) broken = "unclosed " + stack[stack.length - 1].ch + " from line " + stack[stack.length - 1].ln;
  if (broken) {
    bad++;
    console.log("FAIL " + relative(root, file) + " -> " + broken);
  }
}
console.log("checked " + files.length + " files, " + totalLines + " lines, " + bad + " unbalanced");
