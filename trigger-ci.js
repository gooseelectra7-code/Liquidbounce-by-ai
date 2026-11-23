// Trigger GitHub Actions workflow manually
// This uses the workflow_dispatch event

const https = require('https');

const repo = 'gooseelectra7-code/Liquidbounce-by-ai';
const workflow = 'build.yml';

// Note: We need GITHUB_TOKEN with workflow_dispatch permission
// For public repos, we can use the default GITHUB_TOKEN

const options = {
    hostname: 'api.github.com',
    path: `/repos/${repo}/actions/workflows/${workflow}/dispatches`,
    method: 'POST',
    headers: {
        'User-Agent': 'Node.js script',
        'Accept': 'application/vnd.github.v3+json',
        'Content-Type': 'application/json'
    }
};

// We need a token - let's check if it's available
const token = process.env.GITHUB_TOKEN || 'YOUR_TOKEN_HERE';

if (!process.env.GITHUB_TOKEN) {
    console.log('No GITHUB_TOKEN found in environment');
    console.log('We need to trigger CI another way');
}

const data = JSON.stringify({
    ref: 'main',
    inputs: {}
});

options.headers['Authorization'] = `token ${token}`;

const req = https.request(options, (res) => {
    console.log(`Status: ${res.statusCode}`);
    console.log('Headers:', res.headers);
    
    let body = '';
    res.on('data', (chunk) => {
        body += chunk;
    });
    
    res.on('end', () => {
        console.log('Body:', body);
    });
});

req.on('error', (e) => {
    console.error('Error:', e.message);
});

req.write(data);
req.end();