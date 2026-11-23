const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const repo = "C:\\Users\\SYLVIA\\Desktop\\LBnew-main";
const git = "C:\\Program Files\\Git\\bin\\git.exe";
const logFile = path.join(repo, "git-output.log");

function runGit(args) {
    const cmd = `${git} ${args}`;
    console.log(`Running: ${cmd}`);
    const fd = fs.openSync(logFile, 'a');
    fs.writeSync(fd, `\n=== ${cmd} ===\n`);
    fs.closeSync(fd);
    try {
        const result = execSync(cmd, { cwd: repo, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
        console.log(result);
        fs.appendFileSync(logFile, result);
        return result;
    } catch (err) {
        if (err.stdout) {
            console.log(err.stdout);
            fs.appendFileSync(logFile, err.stdout);
        }
        if (err.stderr) {
            console.log(err.stderr);
            fs.appendFileSync(logFile, err.stderr);
        }
        throw err;
    }
}

// Clear log
fs.writeFileSync(logFile, "Git operations log\n");

try {
    // Configure
    console.log('Configuring git...');
    runGit('config user.email "cavestoru-jpg@example.com"');
    runGit('config user.name "cavestoru-jpg"');
    
    // Add all files
    console.log('Adding files...');
    runGit('add -A');
    
    // Check staged
    console.log('Checking staged files...');
    const staged = runGit('diff --cached --name-only').trim();
    if (staged && staged.length > 0) {
        console.log('Files to commit:');
        staged.split('\n').forEach(f => console.log('  ' + f));
        
        // Commit
        console.log('Committing...');
        runGit('commit -m "feat: add full project files for CI build"');
        
        // Push
        console.log('Pushing...');
        runGit('push -u origin main');
    } else {
        console.log('No staged changes to commit');
        
        // Try push anyway
        console.log('Attempting push...');
        runGit('push -u origin main');
    }
    
    console.log('Done!');
    fs.appendFileSync(logFile, "\n=== DONE ===\n");
} catch (err) {
    console.error('Error:', err.message);
    fs.appendFileSync(logFile, `\nERROR: ${err.message}\n`);
    process.exit(1);
}