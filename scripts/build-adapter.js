// scripts/build-adapter.js
// Spawn the Gradle wrapper via Java directly with cwd set via Node,
// avoiding shell `cd` / `&&` parsing that breaks on Windows + Samba.
const { spawnSync } = require('child_process');
const path = require('path');

const adapterDir = path.join(__dirname, '..', 'adapter');

console.log(`>>> build:adapter (cwd=${adapterDir})`);

const result = spawnSync(
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
  { stdio: 'inherit', cwd: adapterDir }
);

process.exit(result.status === null ? 1 : result.status);
