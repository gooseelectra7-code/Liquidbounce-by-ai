// Trigger GitHub Actions workflow using workflow_dispatch
// This works even if git push doesn't work

const https = require('https');

// We need to authenticate with a GitHub token
// The default GITHUB_TOKEN from GitHub Actions has workflow_dispatch permission
// But we're running outside of Actions, so we need a PAT (Personal Access Token)

const GITHUB_TOKEN = process.env.GITHUB_TOKEN;

if (!GITHUB_TOKEN) {
    console.log('GITHUB_TOKEN not set');
    console.log('To trigger the workflow, we need a Personal Access Token with workflow: write permission');
    console.log('Or we can try to push via git if authentication is set up');
    process.exit(1);
}

const repo = 'gooseelectra7-code/Liquidbounce-by-ai';
const workflow_file = 'build.yml';

const data = JSON.stringify({ ref: 'main', inputs: {} });

const options = {
    hostname: 'api.github.com',
    path: `/repos/${repo}/actions/workflows/${workflow_file}/dispatches`,
    method: 'POST',
    headers: {
        'User-Agent': 'Node.js script',
        'Accept': 'application/vnd.github.v3+json',
        'Content-Type': 'application/json',
        'Authorization': `token ${GITHUB_TOKEN}`
    }
};

const req = https.request(options, (res) => {
    console.log(`Status: ${res.statusCode}`);
    
    let body = '';
    res.on('data', (chunk) => { body += chunk; });
    res.on('end', () => {
        console.log('Response:', body);
        if (res.statusCode === 204) {
            console.log('Workflow triggered successfully!');
        }
    });
});

req.on('error', (e) => {
    console.error('Error:', e.message);
});

req.write(data);
req.end();