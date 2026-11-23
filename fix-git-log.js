const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const repo = 'C:\\Users\\SYLVIA\\Desktop\\LBnew-main';
const git = 'C:\\Program Files\\Git\\bin\\git.exe';
const logFile = path.join(repo, 'git-fix-log.txt');

// Log function
function log(msg) {
    fs.appendFileSync(logFile, msg + '\n');
}

log('=== Starting git fix ===');

// Backup config
const gitDir = path.join(repo, '.git');
const configPath = path.join(gitDir, 'config');
let configContent = '';
if (fs.existsSync(configPath)) {
    configContent = fs.readFileSync(configPath, 'utf8');
    log('Backed up config');
}

// Remove broken .git
try {
    fs.rmSync(gitDir, { recursive: true, force: true });
    log('Removed broken .git');
} catch (e) {
    log('Error removing .git: ' + e.message);
}

// Re-init
let result = spawnSync(git, ['init'], { cwd: repo, encoding: 'utf8' });
log('git init: ' + result.status + ', stdout: ' + result.stdout + ', stderr: ' + result.stderr);

// Restore config
if (configContent) {
    fs.writeFileSync(configPath, configContent);
    log('Restored config');
}

// Configure user
result = spawnSync(git, ['config', 'user.email', 'cavestoru-jpg@example.com'], { cwd: repo, encoding: 'utf8' });
log('git config email: ' + result.status);

result = spawnSync(git, ['config', 'user.name', 'cavestoru-jpg'], { cwd: repo, encoding: 'utf8' });
log('git config name: ' + result.status);

// Add remote
result = spawnSync(git, ['remote', 'add', 'origin', 'https://github.com/gooseelectra7-code/Liquidbounce-by-ai.git'], { cwd: repo, encoding: 'utf8' });
log('git remote add: ' + result.status);

// Add files
result = spawnSync(git, ['add', '-A'], { cwd: repo, encoding: 'utf8' });
log('git add: ' + result.status);

// Check staged
result = spawnSync(git, ['diff', '--cached', '--name-only'], { cwd: repo, encoding: 'utf8' });
const staged = result.stdout.trim();
log('Staged files: ' + staged.split('\n').filter(f => f).length);

// Commit
result = spawnSync(git, ['commit', '-m', 'feat: add full project files for CI build'], { cwd: repo, encoding: 'utf8' });
log('git commit: ' + result.status);
if (result.stderr) log('commit stderr: ' + result.stderr);
if (result.stdout) log('commit stdout: ' + result.stdout);

// Push
result = spawnSync(git, ['push', '-u', 'origin', 'main'], { cwd: repo, encoding: 'utf8' });
log('git push: ' + result.status);
if (result.stderr) log('push stderr: ' + result.stderr);
if (result.stdout) log('push stdout: ' + result.stdout);

log('=== Done ===');