import fs from "fs";
const edits = [
  ["app/src/main/res/values/strings.xml", [["<string name=\"app_name\">FCL<", "<string name=\"app_name\">矿港<"]]],
  ["app/src/main/java/dev/mineport/launcher/ui/home/HomeScreen.kt", [["\"FCL 安卓版\"", "\"矿港\""]]],
  ["app/src/main/java/dev/mineport/launcher/ui/settings/SettingsScreen.kt", [
    ["\"FCL Android 0.1.0", "\"Mineport 0.1.0"],
    ["JVM 层计划复用 FCLCore", "JVM 层复用开源社区的 FCLCore"]
  ]],
  ["app/src/main/cpp/render_bridge.cpp", [["\"FCLRender\"", "\"MineportRender\""]]],
  ["app/src/main/cpp/jni_bridge.cpp", [["\"FCLJNI\"", "\"MineportJNI\""]]],
  ["app/src/main/java/dev/mineport/launcher/game/GameSurfaceView.kt", [["\"FCLSurface\"", "\"MineportSurface\""]]],
  ["app/src/main/java/dev/mineport/launcher/jvm/JvmLauncher.kt", [["\"FCLJVM\"", "\"MineportJVM\""]]]
];
for (const [file, pairs] of edits) {
  let t = fs.readFileSync(file, "utf8");
  const before = t;
  for (const [a, b] of pairs) t = t.split(a).join(b);
  if (t !== before) { fs.writeFileSync(file, t, "utf8"); console.log("fixed " + file); }
  else console.log("NO MATCH " + file);
}
