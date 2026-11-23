const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const git = 'C:\\Program Files\\Git\\bin\\git.exe';
const repo = 'C:\\Users\\SYLVIA\\Desktop\\LBnew-main';
const logFile = path.join(repo, 'git-attempt.log');

function runGitLog(args) {
    const cmd = [git, ...args].join(' ');
    fs.appendFileSync(logFile, `\n=== ${cmd} ===\n`);
    
    const result = spawnSync(git, args, {
        cwd: repo,
        encoding: 'utf8',
        maxBuffer: 1024 * 1024 * 10
    });
    
    fs.appendFileSync(logFile, `Status: ${result.status}\n`);
    if (result.stdout) {
        fs.appendFileSync(logFile, `Stdout:\n${result.stdout}\n`);
    }
    if (result.stderr) {
        fs.appendFileSync(logFile, `Stderr:\n${result.stderr}\n`);
    }
    
    return result;
}

// Start log
fs.writeFileSync(logFile, 'Git log started\n');

// Check if git works
const version = runGitLog(['--version']);
console.log('Git version result:', version.status);

// Check status
const status = runGitLog(['status', '--porcelain']);
console.log('Status result:', status.status);

// Check what's in refs
try {
    const refsExist = fs.existsSync(path.join(repo, 'refs'));
    fs.appendFileSync(logFile, `refs dir exists: ${refsExist}\n`);
    
    const objectsExist = fs.existsSync(path.join(repo, '.git', 'objects'));
    fs.appendFileSync(logFile, `objects dir exists: ${objectsExist}\n`);
} catch (e) {
    fs.appendFileSync(logFile, `Error: ${e.message}\n`);
}

// Try to add remote
runGitLog(['remote', 'add', 'origin', 'https://github.com/gooseelectra7-code/Liquidbounce-by-ai.git']);

// Add files
runGitLog(['add', '-A']);

// Try commit
const commit = runGitLog(['commit', '-m', 'fix: initial commit with project files']);
console.log('Commit status:', commit.status);

// Try push
const push = runGitLog(['push', '-u', 'origin', 'main']);
console.log('Push status:', push.status);