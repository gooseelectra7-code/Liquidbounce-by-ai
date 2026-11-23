// Run git commands with explicit output handling
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const git = 'C:\\Program Files\\Git\\bin\\git.exe';
const repo = 'C:\\Users\\SYLVIA\\Desktop\\LBnew-main';
const outputFile = path.join(repo, 'git-output.txt');

function runGit(args) {
    return new Promise((resolve, reject) => {
        const proc = spawn(git, args, { cwd: repo });
        
        let stdout = '';
        let stderr = '';
        
        proc.stdout.on('data', (data) => {
            stdout += data.toString();
        });
        
        proc.stderr.on('data', (data) => {
            stderr += data.toString();
        });
        
        proc.on('close', (code) => {
            resolve({ code, stdout, stderr });
        });
        
        proc.on('error', (err) => {
            reject(err);
        });
    });
}

async function main() {
    // Write output to file
    const log = [];
    
    try {
        // Initialize repo (will fail if already exists, but that's ok)
        log.push('=== git init ===');
        let result = await runGit(['init']);
        log.push(`Status: ${result.code}`);
        log.push(result.stderr ? `Stderr: ${result.stderr}` : '');
        log.push(result.stdout ? `Stdout: ${result.stdout}` : '');
        
        // Configure user
        log.push('\n=== git config ===');
        result = await runGit(['config', 'user.email', 'cavestoru-jpg@example.com']);
        result = await runGit(['config', 'user.name', 'cavestoru-jpg']);
        log.push('Configured user');
        
        // Add all files
        log.push('\n=== git add -A ===');
        result = await runGit(['add', '-A']);
        log.push(`Status: ${result.code}`);
        
        // Check staged files
        log.push('\n=== git diff --cached --name-only ===');
        result = await runGit(['diff', '--cached', '--name-only']);
        const files = result.stdout.trim().split('\n').filter(f => f);
        log.push(`Staged files: ${files.length}`);
        
        // Commit
        log.push('\n=== git commit ===');
        result = await runGit(['commit', '-m', 'feat: add full project files for CI build']);
        log.push(`Status: ${result.code}`);
        log.push(result.stderr ? `Stderr: ${result.stderr}` : '');
        log.push(result.stdout ? `Stdout: ${result.stdout}` : '');
        
        // Push
        log.push('\n=== git push -u origin main ===');
        result = await runGit(['push', '-u', 'origin', 'main']);
        log.push(`Status: ${result.code}`);
        log.push(result.stderr ? `Stderr: ${result.stderr}` : '');
        log.push(result.stdout ? `Stdout: ${result.stdout}` : '');
        
    } catch (err) {
        log.push(`Error: ${err.message}`);
    }
    
    // Write log to file
    fs.writeFileSync(outputFile, log.join('\n'));
    console.log('Output written to:', outputFile);
}

main().catch(console.error);