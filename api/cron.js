const https = require('https');
const {
    PROJECT_ID,
    getServiceAccount,
    getAccessToken,
    firestoreRequest,
    listAllDocs
} = require('./_firebase');

function sendFcmPush(token, appt, docId, targetFcmToken) {
    return new Promise((resolve, reject) => {
        const payload = {
            message: {
                token: targetFcmToken,
                android: {
                    priority: 'HIGH',
                    ttl: '3600s'
                },
                data: {
                    action: 'SEND_SMS',
                    appointmentId: String(appt.localId || 0),
                    telephone: String(appt.telephone || ''),
                    nomPatient: String(appt.nomPatient || ''),
                    texteSms: String(appt.texteSms || ''),
                    dateRdv: String(appt.dateRdv || ''),
                    heureRdv: String(appt.heureRdv || ''),
                    language: String(appt.language || 'fr'),
                    doctorId: String(appt.doctorId || ''),
                    doctorName: String(appt.doctorName || ''),
                    firebaseKey: docId
                }
            }
        };

        const postData = JSON.stringify(payload);
        const req = https.request({
            hostname: 'fcm.googleapis.com',
            path: `/v1/projects/${PROJECT_ID}/messages:send`,
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
        req.write(postData);
        req.end();
    });
}

// Convertit la date/heure locale du cabinet (UTC+1 Tunisie / Europe hiver) en timestamp epoch UTC
function parseAppointmentTimestamp(dateStr, timeStr) {
    if (!dateStr) return null;
    const cleanDate = dateStr.trim();
    const cleanTime = (timeStr || '09:00').trim();
    const [hours, minutes] = cleanTime.split(':').map(n => parseInt(n, 10) || 0);
    const localOffsetMs = 60 * 60 * 1000; // UTC+1 (Africa/Tunis)

    // Format jj/mm/aaaa ou jj-mm-aaaa
    let m = cleanDate.match(/^(\d{1,2})[\/\-](\d{1,2})[\/\-](\d{4})$/);
    if (m) {
        const day = parseInt(m[1], 10);
        const month = parseInt(m[2], 10) - 1;
        const year = parseInt(m[3], 10);
        return Date.UTC(year, month, day, hours, minutes, 0) - localOffsetMs;
    }

    // Format aaaa-mm-jj
    m = cleanDate.match(/^(\d{4})[\/\-](\d{1,2})[\/\-](\d{1,2})$/);
    if (m) {
        const year = parseInt(m[1], 10);
        const month = parseInt(m[2], 10) - 1;
        const day = parseInt(m[3], 10);
        return Date.UTC(year, month, day, hours, minutes, 0) - localOffsetMs;
    }

    return null;
}

async function markSentInFirestore(token, docName, now) {
    try {
        const res = await firestoreRequest(
            token,
            'PATCH',
            `/v1/${docName}?updateMask.fieldPaths=smsEnvoye&updateMask.fieldPaths=sentAt`,
            {
                fields: {
                    smsEnvoye: { booleanValue: true },
                    sentAt: { integerValue: String(now) }
                }
            }
        );
        return res.statusCode >= 200 && res.statusCode < 300;
    } catch (err) {
        console.error(`Erreur PATCH Firestore: ${err.message}`);
        return false;
    }
}

module.exports = async function handler(req, res) {
    try {
        const key = getServiceAccount();
        const token = await getAccessToken(key);
        const now = Date.now();

        // Charger les appareils/licences et tous les RDV (avec pagination)
        const [deviceDocs, apptDocs] = await Promise.all([
            listAllDocs(token, 'devices'),
            listAllDocs(token, 'rendezvous')
        ]);

        const devicesByDoctorId = new Map();
        for (const d of deviceDocs) {
            const docKey = d.name.split('/').pop();
            const f = d.fields || {};
            const doctorId = f.doctorId?.stringValue || docKey;
            const isLicensed = f.isLicensed?.booleanValue !== false && f.licenseActive?.booleanValue !== false;
            const fcmToken = f.fcmToken?.stringValue || '';
            const entry = { isLicensed, fcmToken };
            devicesByDoctorId.set(docKey, entry);
            devicesByDoctorId.set(doctorId, entry);
        }

        const triggered = [];
        const expiredSkipped = [];
        const revokedSkipped = [];

        for (const doc of apptDocs) {
            const fields = doc.fields || {};
            const smsEnvoye = fields.smsEnvoye?.booleanValue === true;
            if (smsEnvoye) continue;

            const reminderTimestamp = Number(fields.reminderTimestamp?.integerValue || 0);
            const dateRdv = fields.dateRdv?.stringValue || '';
            const heureRdv = fields.heureRdv?.stringValue || '';
            const apptTimestamp = parseAppointmentTimestamp(dateRdv, heureRdv);
            const docName = doc.name;
            const doctorId = fields.doctorId?.stringValue || '';

            // 1. SÉCURITÉ ANTI-ENVOI TARDIF : RDV déjà passé de plus de 2h -> ne jamais envoyer
            if (apptTimestamp && apptTimestamp < (now - 2 * 3600 * 1000)) {
                await markSentInFirestore(token, docName, now);
                expiredSkipped.push({ patient: fields.nomPatient?.stringValue || '', dateRdv });
                continue;
            }

            // 2. Vérifier la licence du médecin dans Firestore
            const device = doctorId ? devicesByDoctorId.get(doctorId) : null;
            if (device && !device.isLicensed) {
                revokedSkipped.push({ patient: fields.nomPatient?.stringValue || '', doctorId });
                continue;
            }

            // 3. Rappel dû et RDV non expiré
            const isDue = reminderTimestamp > 0 && reminderTimestamp <= (now + 60000);
            const isNotExpired = !apptTimestamp || apptTimestamp >= (now - 2 * 3600 * 1000);
            const targetFcmToken = (device && device.fcmToken) || fields.targetFcmToken?.stringValue || '';

            if (isDue && isNotExpired && targetFcmToken) {
                const appt = {
                    localId: fields.localId?.integerValue,
                    telephone: fields.telephone?.stringValue,
                    nomPatient: fields.nomPatient?.stringValue,
                    texteSms: fields.texteSms?.stringValue,
                    dateRdv,
                    heureRdv,
                    language: fields.language?.stringValue || 'fr',
                    doctorId,
                    doctorName: fields.doctorName?.stringValue || ''
                };

                const docId = docName.split('/').pop();

                try {
                    const fcmResult = await sendFcmPush(token, appt, docId, targetFcmToken);
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
            checkedDocs: apptDocs.length,
            triggeredCount: triggered.length,
            expiredCleanedCount: expiredSkipped.length,
            revokedSkippedCount: revokedSkipped.length,
            details: triggered
        });
    } catch (error) {
        return res.status(500).json({
            success: false,
            error: error.message
        });
    }
};
