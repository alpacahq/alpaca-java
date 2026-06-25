import { cpSync, existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const docsDir = resolve(scriptDir, '..');
const repoRoot = resolve(docsDir, '..');
const sourceDir = join(repoRoot, 'build', 'docs', 'javadoc');
const targetDir = join(docsDir, 'static', 'javadocs');
const gradleCommand = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';

if (process.env.SKIP_JAVADOC_GENERATION !== 'true') {
  const result = spawnSync(gradleCommand, ['generateJavadocs'], {
    cwd: repoRoot,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });

  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

if (!existsSync(sourceDir)) {
  console.error(`Javadocs were not found at ${sourceDir}`);
  console.error('Run ../gradlew generateJavadocs from the docs directory, then try again.');
  process.exit(1);
}

rmSync(targetDir, { recursive: true, force: true });
mkdirSync(targetDir, { recursive: true });

for (const entry of readdirSync(sourceDir)) {
  cpSync(join(sourceDir, entry), join(targetDir, entry), { recursive: true });
}

writeFileSync(join(targetDir, '.gitkeep'), '');
console.log(`Copied Javadocs from ${sourceDir} to ${targetDir}`);
