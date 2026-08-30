#!/usr/bin/env node
/**
 * Runs the two builds this repository holds, from the repository root.
 *
 * ADR-007 accepted, as known debt, that the "one command to set up, run, and test" baseline was
 * unmet once the Maven project moved into `backend/`, and named the arrival of `frontend/` as the
 * trigger to fix it properly. This is that fix.
 *
 * It exists because `make`, `just` and `task` are all absent on the development machine, while Node
 * is required by the frontend regardless — so this adds no tool that was not already needed. It
 * orchestrates and nothing more: `cd backend && ./mvnw verify` remains the whole backend gate, and
 * this file must never become a place where build logic accumulates.
 */
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const ON_WINDOWS = process.platform === 'win32';

function settled(child, description) {
  return new Promise((resolve, reject) => {
    child.on('error', reject);
    child.on('close', (code) =>
      code === 0 ? resolve() : reject(new Error(`${description} exited with ${code}`)),
    );
  });
}

/**
 * Spawns a build tool in one of the project directories.
 *
 * Windows needs a shell, because Node refuses to spawn a `.cmd` shim without one, and the Maven
 * wrapper is a `.cmd`. Passing an args array alongside `shell: true` is deprecated (DEP0190) since
 * the arguments are concatenated without escaping, so the command string is composed here instead.
 * Every argument in this file is a literal — none of it comes from a caller — which is what makes
 * that safe rather than merely quiet.
 */
function run(file, args, cwd) {
  const options = { cwd: join(ROOT, cwd), stdio: 'inherit' };
  const description = `${file} ${args.join(' ')} in ${cwd}`;
  if (ON_WINDOWS) {
    // A path is quoted because it may contain spaces; a bare command name must NOT be, because
    // quoting stops cmd resolving it through PATH and PATHEXT — `"npm.cmd"` then fails to find its
    // own installation while `npm.cmd` works.
    const command = /[\\/ ]/.test(file) ? `"${file}"` : file;
    return settled(spawn(`${command} ${args.join(' ')}`, { ...options, shell: true }), description);
  }
  return settled(spawn(file, args, options), description);
}

/** The wrapper is a .cmd on Windows and a shell script elsewhere; both live in backend/. */
const MVNW = ON_WINDOWS ? join(ROOT, 'backend', 'mvnw.cmd') : './mvnw';
const NPM = ON_WINDOWS ? 'npm.cmd' : 'npm';

const backend = () => run(MVNW, ['-B', 'verify'], 'backend');
const frontend = () => run(NPM, ['run', 'verify'], 'frontend');

/**
 * Starts both dev servers and keeps them running until one exits.
 *
 * Vite proxies /api to the backend on 8080, so the browser only ever sees one origin and no CORS
 * configuration exists anywhere in this repository.
 */
async function dev() {
  await Promise.race([
    run(MVNW, ['-B', 'spring-boot:run'], 'backend'),
    run(NPM, ['run', 'dev'], 'frontend'),
  ]);
}

const TASKS = {
  backend,
  frontend,
  dev,
  verify: async () => {
    await backend();
    await frontend();
  },
};

const task = TASKS[process.argv[2]];

if (task === undefined) {
  process.stderr.write(`Usage: node scripts/run.mjs <${Object.keys(TASKS).join('|')}>\n`);
  process.exit(2);
}

try {
  await task();
} catch (failure) {
  process.stderr.write(`${failure.message}\n`);
  process.exit(1);
}
