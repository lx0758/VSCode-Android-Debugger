// scripts/set-version.js
// 以 git tag 为版本号的唯一真相源, 把版本同步到仓库中所有声明版本的位置:
//   - extension/package.json       (Marketplace 实际发布的版本)
//   - extension/package-lock.json  (与 package.json 保持一致)
//   - package.json                 (monorepo 根包)
//   - adapter/build.gradle.kts     (adapter jar 的 manifest 版本)
//
// 用法: node scripts/set-version.js v1.2.3
//
// 说明:
//   - Marketplace 不接受 semver 预发布版本 (1.2.3-beta.1), 因此只接受 vX.Y.Z
//     三段式; 预发布请用纯版本号发布。
//   - package.json 用正则精确替换第一个 version 字段, 不重新序列化整个文件,
//     避免产生纯格式噪音的 diff。
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');

const raw = process.argv[2];
if (!raw) {
  console.error('用法: node scripts/set-version.js <tag>   例如: node scripts/set-version.js v1.2.3');
  process.exit(1);
}

const version = raw.replace(/^v/, '');
if (!/^\d+\.\d+\.\d+$/.test(version)) {
  console.error(
    `版本号非法: "${raw}"\n` +
    '只接受 vX.Y.Z 三段式数字版本 (Marketplace 不支持 1.2.3-beta.1 这类预发布版本号)。'
  );
  process.exit(1);
}

/** 替换 JSON 文件里第一个出现的 "version" 字段, 保持原有缩进与键顺序 */
function replaceFirstVersion(file, newVersion) {
  const text = fs.readFileSync(file, 'utf8');
  const re = /("version"\s*:\s*")([^"]*)(")/;
  if (!re.test(text)) {
    console.warn(`! 未在 ${path.relative(root, file)} 中找到 version 字段, 已跳过`);
    return;
  }
  fs.writeFileSync(file, text.replace(re, `$1${newVersion}$3`), 'utf8');
}

// 1. extension/package.json —— 发布到 Marketplace 的版本
replaceFirstVersion(path.join(root, 'extension', 'package.json'), version);

// 2. extension/package-lock.json —— 顶层与 packages[""] 两处都要同步
const lockPath = path.join(root, 'extension', 'package-lock.json');
const lock = JSON.parse(fs.readFileSync(lockPath, 'utf8'));
lock.version = version;
if (lock.packages && lock.packages['']) {
  lock.packages[''].version = version;
}
fs.writeFileSync(lockPath, JSON.stringify(lock, null, 2) + '\n', 'utf8');

// 3. 根 package.json —— monorepo 版本
replaceFirstVersion(path.join(root, 'package.json'), version);

// 4. adapter/build.gradle.kts —— adapter jar 的 manifest 版本
const gradlePath = path.join(root, 'adapter', 'build.gradle.kts');
const gradle = fs.readFileSync(gradlePath, 'utf8');
const next = gradle.replace(/^version\s*=\s*".*"$/m, `version = "${version}"`);
if (next === gradle) {
  console.warn('! 未在 adapter/build.gradle.kts 中找到 version 字段, 已跳过');
} else {
  fs.writeFileSync(gradlePath, next, 'utf8');
}

console.log(`>>> 版本已同步为 ${version}`);
