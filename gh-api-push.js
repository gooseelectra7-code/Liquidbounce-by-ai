// Use GitHub API to push files
// We'll need to create a commit and push via the API

// First, let's read the files we need to push
const fs = require('fs');
const path = require('path');
const https = require('https');

const repo = 'gooseelectra7-code/Liquidbounce-by-ai';
const workDir = 'C:\\Users\\SYLVIA\\Desktop\\LBnew-main';

// Check what files we have
const allFiles = [];
function walk(dir) {
    const items = fs.readdirSync(dir, { withFileTypes: true });
    for (const item of items) {
        const fullPath = path.join(dir, item.name);
        if (item.isDirectory()) {
            if (item.name !== '.git' && item.name !== 'node_modules') {
                walk(fullPath);
            }
        } else if (item.name !== 'git-output.txt' && item.name !== 'py-test.txt' && item.name !== 'do-git-py.py') {
            // Skip some test files
            allFiles.push(fullPath);
        }
    }
}

try {
    walk(workDir);
    console.log('Total files:', allFiles.length);
    console.log('First 10 files:');
    allFiles.slice(0, 10).forEach(f => console.log(' ', f));
} catch (e) {
    console.error('Error:', e.message);
}