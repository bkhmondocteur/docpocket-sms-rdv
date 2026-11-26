const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

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
        let raw = process.env.FIREBASE_SERVICE_ACCOUNT.trim();
        try {
            key = JSON.parse(raw);
        } catch (e) {
            try {
                key = JSON.parse(Buffer.from(raw, 'base64').toString('utf8'));
            } catch (e2) {
                throw new Error("Impossible de parser FIREBASE_SERVICE_ACCOUNT: " + e.message);
            }
        }
    } else {
        const keyPath = path.join(__dirname, 'service-account.json');
        if (fs.existsSync(keyPath)) {
            key = JSON.parse(fs.readFileSync(keyPath, 'utf8'));
        }
    }
    if (!key) throw new Error("Clé de service Firebase introuvable dans FIREBASE_SERVICE_ACCOUNT.");

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
            let d = ''; res.on('data', c => d += c);
            res.on('end', () => {
                const parsed = JSON.parse(d);
                if (parsed.access_token) resolve(parsed.access_token);
                else reject(new Error('Erreur auth Google: ' + d));
            });
        });
        req.on('error', reject);
        req.write(postData); req.end();
    });
}

function sendFcmPush(token, appt, docId) {
    return new Promise((resolve, reject) => {
        const payload = {
            message: {
                token: appt.targetFcmToken,
                android: {
                    priority: "HIGH"
                },
                data: {
                    action: "SEND_SMS",
                    appointmentId: String(appt.localId || 0),
                    telephone: String(appt.telephone || ""),
                    nomPatient: String(appt.nomPatient || ""),
                    texteSms: String(appt.texteSms || ""),
                    dateRdv: String(appt.dateRdv || ""),
                    heureRdv: String(appt.heureRdv || ""),
                    language: String(appt.language || "fr"),
                    doctorId: String(appt.doctorId || ""),
                    doctorName: String(appt.doctorName || ""),
                    firebaseKey: docId
                }
            }
        };

        const postData = JSON.stringify(payload);
        const req = https.request({
            hostname: 'fcm.googleapis.com',
            path: '/v1/projects/docpocket-rdv/messages:send',
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(postData)
            }
        }, res => {
            let body = '';
            res.on('data', d => body += d);
            res.on('end', () => {
                if (res.statusCode === 200) resolve(JSON.parse(body));
                else reject(new Error(`FCM ${res.statusCode}: ${body}`));
            });
        });
        req.on('error', reject);
        req.write(postData); req.end();
    });
}

function parseAppointmentTimestamp(dateStr, timeStr) {
    if (!dateStr) return null;
    const cleanDate = dateStr.trim();
    const cleanTime = (timeStr || '09:00').trim();

    // Format jj/mm/aaaa ou jj-mm-aaaa
    let m = cleanDate.match(/^(\d{1,2})[\/\-](\d{1,2})[\/\-](\d{4})$/);
    if (m) {
        const day = parseInt(m[1], 10);
        const month = parseInt(m[2], 10) - 1;
        const year = parseInt(m[3], 10);
        let [hours, minutes] = cleanTime.split(':').map(n => parseInt(n, 10) || 0);
        return new Date(year, month, day, hours, minutes, 0).getTime();
    }

    // Format aaaa-mm-jj
    m = cleanDate.match(/^(\d{4})[\/\-](\d{1,2})[\/\-](\d{1,2})$/);
    if (m) {
        const year = parseInt(m[1], 10);
        const month = parseInt(m[2], 10) - 1;
        const day = parseInt(m[3], 10);
        let [hours, minutes] = cleanTime.split(':').map(n => parseInt(n, 10) || 0);
        return new Date(year, month, day, hours, minutes, 0).getTime();
    }

    return null;
}

function markSentInFirestore(token, docName, now) {
    return new Promise((resolve) => {
        const body = JSON.stringify({
            fields: {
                smsEnvoye: { booleanValue: true },
                sentAt: { integerValue: String(now) }
            }
        });
        const req = https.request({
            hostname: 'firestore.googleapis.com',
            path: `/v1/${docName}?updateMask.fieldPaths=smsEnvoye&updateMask.fieldPaths=sentAt`,
            method: 'PATCH',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(body)
            }
        }, res => {
            let d = ''; res.on('data', c => d += c);
            res.on('end', () => {
                if (res.statusCode >= 200 && res.statusCode < 300) {
                    resolve(true);
                } else {
                    console.error(`Erreur PATCH Firestore ${docName} (${res.statusCode}): ${d}`);
                    resolve(false);
                }
            });
        });
        req.on('error', err => {
            console.error(`Erreur réseau PATCH Firestore: ${err.message}`);
            resolve(false);
        });
        req.write(body); req.end();
    });
}

module.exports = async function handler(req, res) {
    try {
        const key = getServiceAccount();
        const token = await getAccessToken(key);
        const now = Date.now();

        // Récupération des rendez-vous
        const getDocs = () => new Promise((resolve, reject) => {
            https.get({
                hostname: 'firestore.googleapis.com',
                path: '/v1/projects/docpocket-rdv/databases/(default)/documents/rendezvous',
                headers: { 'Authorization': `Bearer ${token}` }
            }, r => {
                let d = ''; r.on('data', c => d += c);
                r.on('end', () => resolve(JSON.parse(d)));
            }).on('error', reject);
        });

        const firestoreData = await getDocs();
        const docs = firestoreData.documents || [];
        const triggered = [];
        const expiredSkipped = [];

        for (const doc of docs) {
            const fields = doc.fields || {};
            const smsEnvoye = fields.smsEnvoye?.booleanValue === true;
            const reminderTimestamp = Number(fields.reminderTimestamp?.integerValue || 0);
            const dateRdv = fields.dateRdv?.stringValue || '';
            const heureRdv = fields.heureRdv?.stringValue || '';
            const apptTimestamp = parseAppointmentTimestamp(dateRdv, heureRdv);
            const docName = doc.name;

            // 1. Déjà envoyé : ignorer
            if (smsEnvoye) {
                continue;
            }

            // 2. SÉCURITÉ ABSOLUE ANTI-ENVOI TARDIF :
            // Si la date du rendez-vous est déjà passée (ex: RDV du 20 novembre alors qu'on est le 26 novembre),
            // IL EST STRICTEMENT INTERDIT d'envoyer un SMS ! On marque le document comme expiré dans Firestore.
            if (apptTimestamp && apptTimestamp < (now - 2 * 3600 * 1000)) {
                await markSentInFirestore(token, docName, now);
                expiredSkipped.push({ patient: fields.nomPatient?.stringValue || '', dateRdv, reason: 'Rendez-vous dans le passé' });
                continue;
            }

            // 3. Rappel dû : l'heure de rappel est atteinte ET le RDV est bien dans le futur
            const isDue = reminderTimestamp > 0 && reminderTimestamp <= (now + 60000);
            const isNotExpired = !apptTimestamp || apptTimestamp >= (now - 2 * 3600 * 1000);

            if (isDue && isNotExpired) {
                const appt = {
                    localId: fields.localId?.integerValue,
                    telephone: fields.telephone?.stringValue,
                    nomPatient: fields.nomPatient?.stringValue,
                    texteSms: fields.texteSms?.stringValue,
                    dateRdv: fields.dateRdv?.stringValue,
                    heureRdv: fields.heureRdv?.stringValue,
                    language: fields.language?.stringValue || 'fr',
                    doctorId: fields.doctorId?.stringValue || '',
                    doctorName: fields.doctorName?.stringValue || '',
                    targetFcmToken: fields.targetFcmToken?.stringValue
                };

                const docId = docName.split('/').pop();

                try {
                    const fcmResult = await sendFcmPush(token, appt, docId);
                    await markSentInFirestore(token, docName, now);
                    triggered.push({ patient: appt.nomPatient, phone: appt.telephone, fcmId: fcmResult.name });
                } catch (sendErr) {
                    triggered.push({ patient: appt.nomPatient, error: sendErr.message });
                }
            }
        }

        return res.status(200).json({
            success: true,
            timestamp: new Date().toISOString(),
            checkedDocs: docs.length,
            triggeredCount: triggered.length,
            expiredCleanedCount: expiredSkipped.length,
            details: triggered
        });
    } catch (error) {
        return res.status(500).json({
            success: false,
            error: error.message
        });
    }
};
