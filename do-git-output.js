const { execSync, spawn } = require('child_process');

// Use spawn for better control
const proc = spawn('C:\\Program Files\\Git\\bin\\git.exe', ['status', '--porcelain'], {
    cwd: 'C:\\Users\\SYLVIA\\Desktop\\LBnew-main'
});

let stdout = '';
let stderr = '';

proc.stdout.on('data', (data) => {
    stdout += data.toString();
});

proc.stderr.on('data', (data) => {
    stderr += data.toString();
});

proc.on('close', (code) => {
    console.log('Exit code:', code);
    console.log('Stdout:', stdout || '(empty)');
    console.log('Stderr:', stderr || '(empty)');
});

proc.on('error', (err) => {
    console.log('Error:', err.message);
});