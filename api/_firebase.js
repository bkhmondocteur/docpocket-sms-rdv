const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PROJECT_ID = 'docpocket-rdv';
const SECRET_SALT = 'MEDWIN_SEC_2026';

function formatPrivateKey(rawKey) {
    if (!rawKey) return '';
    let k = rawKey.replace(/\\n/g, '\n').replace(/\\r/g, '');
    if (k.includes('+NSg1') && !k.includes('+NSg1p')) {
        k = k.replace('+NSg1', '+NSg1p');
    }
    const match = k.match(/-----BEGIN [A-Z ]+-----(.*?)-----END [A-Z ]+-----/s);
    if (match) {
        const base64Body = match[1].replace(/[^A-Za-z0-9+/=]/g, '');
        const chunked = base64Body.match(/.{1,64}/g).join('\n');
        return `-----BEGIN PRIVATE KEY-----\n${chunked}\n-----END PRIVATE KEY-----\n`;
    }
    return k;
}

function getServiceAccount() {
    let key;
    if (process.env.FIREBASE_SERVICE_ACCOUNT) {
        const raw = process.env.FIREBASE_SERVICE_ACCOUNT.trim();
        try {
            key = JSON.parse(raw);
        } catch (e) {
            try {
                key = JSON.parse(Buffer.from(raw, 'base64').toString('utf8'));
            } catch (e2) {
                throw new Error('Impossible de parser FIREBASE_SERVICE_ACCOUNT: ' + e.message);
            }
        }
    } else {
        const keyPath = path.join(__dirname, 'service-account.json');
        if (fs.existsSync(keyPath)) {
            key = JSON.parse(fs.readFileSync(keyPath, 'utf8'));
        }
    }
    if (!key) throw new Error('Clé de service Firebase introuvable dans FIREBASE_SERVICE_ACCOUNT.');

    if (key.private_key) {
        key.private_key = formatPrivateKey(key.private_key);
    }
    return key;
}

function getAccessToken(key) {
    return new Promise((resolve, reject) => {
        const now = Math.floor(Date.now() / 1000);
        const header = { alg: 'RS256', typ: 'JWT' };
        const claimSet = {
            iss: key.client_email,
            scope: 'https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/datastore',
            aud: 'https://oauth2.googleapis.com/token',
            exp: now + 3600,
            iat: now
        };
        const encode = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url');
        const signatureInput = `${encode(header)}.${encode(claimSet)}`;
        const signer = crypto.createSign('RSA-SHA256');
        signer.update(signatureInput);
        const jwt = `${signatureInput}.${signer.sign(key.private_key, 'base64url')}`;
        const postData = `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt}`;

        const req = https.request('https://oauth2.googleapis.com/token', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'Content-Length': Buffer.byteLength(postData)
            }
        }, res => {
            let d = '';
            res.on('data', c => d += c);
            res.on('end', () => {
                try {
                    const parsed = JSON.parse(d);
                    if (parsed.access_token) resolve(parsed.access_token);
                    else reject(new Error('Erreur auth Google: ' + d));
                } catch (err) {
                    reject(new Error('Réponse auth Google invalide: ' + d));
                }
            });
        });
        req.on('error', reject);
        req.write(postData);
        req.end();
    });
}

function firestoreRequest(token, method, apiPath, bodyObj) {
    return new Promise((resolve, reject) => {
        const body = bodyObj ? JSON.stringify(bodyObj) : null;
        const headers = { 'Authorization': `Bearer ${token}` };
        if (body) {
            headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(body);
        }
        const req = https.request({
            hostname: 'firestore.googleapis.com',
            path: apiPath,
            method,
            headers
        }, res => {
            let d = '';
            res.on('data', c => d += c);
            res.on('end', () => {
                let parsed = {};
                if (d) {
                    try { parsed = JSON.parse(d); } catch (_) { parsed = { raw: d }; }
                }
                resolve({ statusCode: res.statusCode, data: parsed });
            });
        });
        req.on('error', reject);
        if (body) req.write(body);
        req.end();
    });
}

async function listAllDocs(token, collection) {
    const docs = [];
    let pageToken = '';
    do {
        const qs = pageToken
            ? `?pageSize=300&pageToken=${encodeURIComponent(pageToken)}`
            : '?pageSize=300';
        const res = await firestoreRequest(
            token,
            'GET',
            `/v1/projects/${PROJECT_ID}/databases/(default)/documents/${collection}${qs}`
        );
        if (res.statusCode >= 400) {
            throw new Error(`Firestore GET ${collection} (${res.statusCode}): ${JSON.stringify(res.data)}`);
        }
        if (Array.isArray(res.data.documents)) {
            docs.push(...res.data.documents);
        }
        pageToken = res.data.nextPageToken || '';
    } while (pageToken);
    return docs;
}

function computeLicenseKey(doctorName) {
    const nameClean = String(doctorName || '').trim().toUpperCase();
    if (!nameClean) return '';
    const hash = crypto.createHash('md5').update(nameClean + SECRET_SALT).digest('hex').toUpperCase();
    const base = hash.slice(0, 16);
    return `${base.slice(0, 4)}-${base.slice(4, 8)}-${base.slice(8, 12)}-${base.slice(12, 16)}`;
}

module.exports = {
    PROJECT_ID,
    getServiceAccount,
    getAccessToken,
    firestoreRequest,
    listAllDocs,
    computeLicenseKey
};
