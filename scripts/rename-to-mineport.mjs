import fs from "fs";
import path from "path";

const root = ".";
const oldDot = "com.tungsten.fclandroid";
const newDot = "dev.mineport.launcher";
const oldSlash = "com/tungsten/fclandroid";
const newSlash = "dev/mineport/launcher";

// 1. move source tree
const srcDir = path.join(root, "app/src/main/java/com/tungsten/fclandroid");
const dstDir = path.join(root, "app/src/main/java/dev/mineport/launcher");
fs.mkdirSync(path.dirname(dstDir), { recursive: true });
if (fs.existsSync(srcDir)) {
  fs.renameSync(srcDir, dstDir);
  // clean empty com/tungsten
  try {
    const t = path.join(root, "app/src/main/java/com/tungsten");
    if (fs.readdirSync(t).length === 0) fs.rmdirSync(t);
    const c = path.join(root, "app/src/main/java/com");
    if (fs.readdirSync(c).length === 0) fs.rmdirSync(c);
  } catch (e) {}
}

// 2. rename FCLApplication.kt
const appKt = path.join(dstDir, "FCLApplication.kt");
const mineKt = path.join(dstDir, "MineportApplication.kt");
if (fs.existsSync(appKt)) fs.renameSync(appKt, mineKt);

// 3. walk & replace
const exts = new Set([".kt", ".kts", ".xml", ".pro", ".cpp", ".h", ".md"]);
const skipDirs = new Set(["docs", "build", ".gradle", ".git"]);
let changed = 0;
function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    const st = fs.statSync(p);
    if (st.isDirectory()) {
      if (skipDirs.has(name)) continue;
      walk(p);
      continue;
    }
    if (!exts.has(path.extname(name))) continue;
    let text = fs.readFileSync(p, "utf8");
    const before = text;
    text = text.split(oldSlash).join(newSlash);
    text = text.split(oldDot).join(newDot);
    text = text.split("Theme.FCLAndroid").join("Theme.Mineport");
    text = text.split("FCLApplication").join("MineportApplication");
    text = text.split("FCLTheme").join("MineportTheme");
    text = text.split("FCLAndroid").join("Mineport");
    if (text !== before) {
      fs.writeFileSync(p, text, "utf8");
      changed++;
      console.log("updated " + p);
    }
  }
}
walk(root);
console.log("files changed: " + changed);
