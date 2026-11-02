// scripts/update-changelog.js
// 在 extension/CHANGELOG.md 顶部插入一个新版本的条目。
//
// 幂等: 同一个版本号已存在时不做任何事 (按版本号判断, 不按日期 ——
// 本地时区与 CI 的 UTC 日期可能相差一天, 按日期判断会插入重复条目)。
//
// 用法: node scripts/update-changelog.js <version> [YYYY-MM-DD] [条目正文...]
//   node scripts/update-changelog.js 1.2.3
//   node scripts/update-changelog.js 1.2.3 2026-11-02 "- 新增 xxx" "- 修复 yyy"
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const changelogPath = path.join(root, 'extension', 'CHANGELOG.md');

const version = process.argv[2];
if (!version) {
  console.error('用法: node scripts/update-changelog.js <version> [YYYY-MM-DD] [条目正文...]');
  process.exit(1);
}

const date =
  process.argv[3] && /^\d{4}-\d{2}-\d{2}$/.test(process.argv[3])
    ? process.argv[3]
    : new Date().toISOString().slice(0, 10);

// 第 4 个及之后的参数作为条目正文 (每行一条), 未提供时使用通用占位说明
const body = process.argv.slice(4).filter((line) => line.trim().length > 0);
const lines = body.length > 0 ? body : ['- 详见 GitHub Release 与提交记录。'];

const existing = fs.existsSync(changelogPath) ? fs.readFileSync(changelogPath, 'utf8') : '';

// 版本号是否已出现过 (匹配 "## 1.2.3" / "## 1.2.3 - 2026-11-02" 两种写法)
const escaped = version.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
if (new RegExp(`^##\\s+${escaped}(?:\\s|$)`, 'm').test(existing)) {
  console.log(`>>> CHANGELOG 已包含 ${version}, 跳过`);
  process.exit(0);
}

const entry = `## ${version} - ${date}\n\n${lines.join('\n')}\n`;

let next;
if (existing.startsWith('# Changelog')) {
  // 保留 "# Changelog" 标题行, 把新条目插到标题之后、其余内容之前
  const idx = existing.indexOf('\n');
  const rest = existing.slice(idx + 1).replace(/^\n+/, '');
  next = existing.slice(0, idx + 1) + '\n' + entry + (rest ? '\n' + rest : '');
} else {
  next = `# Changelog\n\n${entry}\n${existing}`;
}

fs.writeFileSync(changelogPath, next, 'utf8');
console.log(`>>> CHANGELOG 已插入 ${version}`);
