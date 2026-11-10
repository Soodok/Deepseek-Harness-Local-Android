// 把 package.json 的 dependencies 清空。
//
// 为什么是独立文件而不是 `node -e '...'`：那段代码里需要写一个换行
// （`JSON.stringify(...) + "\n"`），而外层的 shell / heredoc 会把它变成
// 真实换行 → 语法错误（实测 `Expected ',', got 'string literal'`）。
// 独立文件里写 `\n` 就是字面转义，不经过任何转义层。
const fs = require("fs");
const f = process.argv[2];
const p = JSON.parse(fs.readFileSync(f, "utf8"));
const before = Object.keys(p.dependencies || {}).join(" ");
p.dependencies = {};
fs.writeFileSync(f, JSON.stringify(p, null, 2) + "\n");
process.stdout.write(before);
