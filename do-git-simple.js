const { execSync } = require('child_process');

const result = execSync('C:\\Program Files\\Git\\bin\\git.exe status --porcelain', {
    cwd: 'C:\\Users\\SYLVIA\\Desktop\\LBnew-main',
    encoding: 'utf8'
});

console.log('Staged files:');
console.log(result || 'None');