// Manual git initialization script
const fs = require('fs');
const path = require('path');

const repo = 'C:\\Users\\SYLVIA\\Desktop\\LBnew-main';
const gitDir = path.join(repo, '.git');

// Create .git directory structure
const dirs = [
    gitDir,
    path.join(gitDir, 'objects'),
    path.join(gitDir, 'objects', 'pack'),
    path.join(gitDir, 'objects', 'info'),
    path.join(gitDir, 'refs'),
    path.join(gitDir, 'refs', 'heads'),
    path.join(gitDir, 'refs', 'tags'),
    path.join(gitDir, 'info'),
    path.join(gitDir, 'logs')
];

dirs.forEach(dir => {
    if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
        console.log('Created:', dir);
    }
});

// Create HEAD file pointing to main
fs.writeFileSync(path.join(gitDir, 'HEAD'), 'ref: refs/heads/main\n');
console.log('Created HEAD');

// Create config
const config = `[core]
	repositoryformatversion = 0
	filemode = false
	bare = false
	logallrefupdates = true
	symlinks = false
	ignorecase = true
[user]
	email = cavestoru-jpg@example.com
	name = cavestoru-jpg
[remote "origin"]
	url = https://github.com/gooseelectra7-code/Liquidbounce-by-ai.git
	fetch = +refs/heads/*:refs/remotes/origin/*
`;

fs.writeFileSync(path.join(gitDir, 'config'), config);
console.log('Created config');

// Create description
fs.writeFileSync(path.join(gitDir, 'description'), 'LiquidBounce by AI\n');
console.log('Created description');

// Create hooks directory placeholder
fs.mkdirSync(path.join(gitDir, 'hooks'), { recursive: true });
fs.writeFileSync(path.join(gitDir, 'hooks', 'pre-commit'), '');
fs.writeFileSync(path.join(gitDir, 'hooks', 'post-commit'), '');
console.log('Created hooks');

console.log('Git directory structure created');
console.log('Now we need to create the initial commit');

// List what we created
console.log('\n.git contents:');
fs.readdirSync(gitDir).forEach(item => {
    console.log(' ', item);
});