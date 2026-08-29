// scripts/build-all.js
// Full build: adapter jar -> copy to extension/resources/ -> tsc.
// Each step is a separate child process; failures abort. No shell parsing,
// no `&&`, no `cd` — works on Windows + Samba + PowerShell + Linux.
const { spawnSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const root = path.join(__dirname, '..');
const adapterDir = path.join(root, 'adapter');
const extensionDir = path.join(root, 'extension');
const extensionResources = path.join(extensionDir, 'resources');
const jarName = 'adapter.jar';

function run(cmd, args, opts = {}) {
  const tag = `>>> ${cmd} ${args.join(' ')}${opts.cwd ? `  (cwd=${opts.cwd})` : ''}`;
  console.log(`\n${tag}\n`);
  const r = spawnSync(cmd, args, { stdio: 'inherit', ...opts });
  if (r.error) {
    console.error(`!!! ${r.error.message}`);
    process.exit(1);
  }
  if (r.status !== 0) process.exit(r.status);
}

// 1. Adapter (Gradle via Java)
run(
  'java',
  [
    '-cp',
    'gradle/wrapper/gradle-wrapper.jar',
    'org.gradle.wrapper.GradleWrapperMain',
    '--console=plain',
    'shadowJar',
    '-x',
    'test',
  ],
  { cwd: adapterDir }
);

// 2. Copy adapter jar -> extension/resources/
fs.mkdirSync(extensionResources, { recursive: true });
const jarSrc = path.join(adapterDir, 'build', 'libs', jarName);
const jarDest = path.join(extensionResources, jarName);
fs.copyFileSync(jarSrc, jarDest);
console.log(`\n>>> copy:adapter  ${jarSrc}\n             -> ${jarDest}\n`);

// 3. Extension (tsc) — Windows needs shell:true so cmd.exe can resolve npm.cmd
run('npm', ['--prefix', extensionDir, 'run', 'build'], {
  cwd: extensionDir,
  shell: process.platform === 'win32',
});

console.log('\n=== build: all complete ===');
