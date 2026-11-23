const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const repo = 'C:\\Users\\SYLVIA\\Desktop\\LBnew-main';
const git = 'C:\\Program Files\\Git\\bin\\git.exe';

console.log('Fixing git repository...');

// First, let's remove the broken .git and reinitialize
const gitDir = path.join(repo, '.git');
const backupDir = gitDir + '.backup';

// Backup the config
let configContent = '';
const configPath = path.join(gitDir, 'config');
if (fs.existsSync(configPath)) {
    configContent = fs.readFileSync(configPath, 'utf8');
    console.log('Backed up config');
}

// Remove the broken .git
fs.rmSync(gitDir, { recursive: true, force: true });
console.log('Removed broken .git');

// Re-initialize
execSync(`${git} init`, { cwd: repo, stdio: 'inherit' });
console.log('Re-initialized git');

// Restore config
if (configContent) {
    fs.writeFileSync(configPath, configContent);
    console.log('Restored config');
}

// Configure user
execSync(`${git} config user.email "cavestoru-jpg@example.com"`, { cwd: repo, stdio: 'inherit' });
execSync(`${git} config user.name "cavestoru-jpg"`, { cwd: repo, stdio: 'inherit' });

// Add remote
execSync(`${git} remote add origin https://github.com/gooseelectra7-code/Liquidbounce-by-ai.git`, { cwd: repo, stdio: 'inherit' });
console.log('Added remote');

// Add all files
execSync(`${git} add -A`, { cwd: repo, stdio: 'inherit' });
console.log('Added all files');

// Check what's staged
const staged = execSync(`${git} diff --cached --name-only`, { cwd: repo, encoding: 'utf8' });
console.log('Staged files count:', staged.trim().split('\n').filter(f => f).length);

// Commit
execSync(`${git} commit -m "feat: add full project files for CI build"`, { cwd: repo, stdio: 'inherit' });
console.log('Committed');

// Push
try {
    execSync(`${git} push -u origin main`, { cwd: repo, stdio: 'inherit' });
    console.log('Pushed successfully!');
} catch (e) {
    console.log('Push result:', e.message);
}