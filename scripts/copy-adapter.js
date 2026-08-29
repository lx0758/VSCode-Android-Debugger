// scripts/copy-adapter.js
// Ensure extension/resources/ exists, then copy the freshly-built
// adapter jar from adapter/build/libs/ into it.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const src = path.join(root, 'adapter', 'build', 'libs', 'adapter.jar');
const destDir = path.join(root, 'extension', 'resources');
const dest = path.join(destDir, 'adapter.jar');

fs.mkdirSync(destDir, { recursive: true });
fs.copyFileSync(src, dest);
console.log(`>>> copy:adapter  ${src}\n             -> ${dest}`);
