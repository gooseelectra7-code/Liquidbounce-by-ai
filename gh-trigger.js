// Trigger GitHub Actions workflow using gh CLI
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const gh = 'C:\\Program Files\\GitHub CLI\\gh.exe';
const repo = 'gooseelectra7-code/Liquidbounce-by-ai';
const logFile = path.join('C:\\Users\\SYLVIA\\Desktop\\LBnew-main', 'gh-trigger.log');

function log(msg) {
    fs.appendFileSync(logFile, msg + '\n');
}

// Clear log
fs.writeFileSync(logFile, 'GitHub Actions trigger log\n');

// Try to trigger the workflow
try {
    log('Attempting to trigger workflow...');
    
    const cmd = `"${gh}" workflow run build.yml --repo ${repo} --ref main`;
    log(`Command: ${cmd}`);
    
    const result = execSync(cmd, {
        cwd: 'C:\\Users\\SYLVIA\\Desktop\\LBnew-main',
        encoding: 'utf8',
        maxBuffer: 1024 * 1024
    });
    
    log(`Output: ${result}`);
    log('Workflow triggered successfully!');
} catch (err) {
    log(`Error: ${err.message}`);
    if (err.stdout) log(`Stdout: ${err.stdout}`);
    if (err.stderr) log(`Stderr: ${err.stderr}`);
    log('Workflow trigger failed - may need authentication');
}

// Also try to check auth status
try {
    log('\nChecking auth status...');
    const authCmd = `"${gh}" auth status`;
    const authResult = execSync(authCmd, {
        cwd: 'C:\\Users\\SYLVIA\\Desktop\\LBnew-main',
        encoding: 'utf8',
        maxBuffer: 1024 * 1024
    });
    log(`Auth status: ${authResult}`);
} catch (err) {
    log(`Auth check error: ${err.message}`);
}