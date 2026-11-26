const {
    PROJECT_ID,
    getServiceAccount,
    getAccessToken,
    firestoreRequest,
    listAllDocs,
    computeLicenseKey
} = require('./_firebase');

function setCors(res) {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}

function serializeDoctor(doc) {
    const docId = doc.name.split('/').pop();
    const f = doc.fields || {};
    const doctorId = f.doctorId?.stringValue || docId;
    const doctorName = f.doctorName?.stringValue || doctorId;
    const storedKey = f.licenseKey?.stringValue || '';
    const calculatedKey = computeLicenseKey(doctorName);
    const isLicensed = f.isLicensed?.booleanValue !== false && f.licenseActive?.booleanValue !== false;

    return {
        docId,
        doctorId,
        doctorName,
        licenseKey: storedKey || calculatedKey,
        calculatedKey,
        isLicensed,
        deviceModel: f.deviceModel?.stringValue || '',
        androidVersion: f.androidVersion?.stringValue || '',
        hasFcmToken: Boolean(f.fcmToken?.stringValue),
        lastUpdated: Number(f.lastUpdated?.integerValue || 0),
        licenseActivatedAt: Number(f.licenseActivatedAt?.integerValue || 0),
        revokedAt: Number(f.revokedAt?.integerValue || 0)
    };
}

module.exports = async function handler(req, res) {
    setCors(res);
    if (req.method === 'OPTIONS') {
        return res.status(200).end();
    }

    try {
        const key = getServiceAccount();
        const token = await getAccessToken(key);
        const basePath = `/v1/projects/${PROJECT_ID}/databases/(default)/documents/devices`;

        if (req.method === 'GET') {
            const docs = await listAllDocs(token, 'devices');
            const doctors = docs
                .map(serializeDoctor)
                .sort((a, b) => (b.lastUpdated || 0) - (a.lastUpdated || 0));

            return res.status(200).json({
                success: true,
                count: doctors.length,
                doctors
            });
        }

        if (req.method === 'POST') {
            let body = req.body;
            if (typeof body === 'string') {
                try { body = JSON.parse(body); } catch (_) { body = {}; }
            }
            body = body || {};

            const action = String(body.action || '').toLowerCase();
            const docId = String(body.docId || body.doctorId || '').trim();
            if (!docId || !/^[a-zA-Z0-9_-]+$/.test(docId)) {
                return res.status(400).json({ success: false, error: 'doctorId invalide.' });
            }

            if (action === 'revoke') {
                const now = Date.now();
                const patch = await firestoreRequest(
                    token,
                    'PATCH',
                    `${basePath}/${encodeURIComponent(docId)}?updateMask.fieldPaths=isLicensed&updateMask.fieldPaths=licenseActive&updateMask.fieldPaths=revokedAt`,
                    {
                        fields: {
                            isLicensed: { booleanValue: false },
                            licenseActive: { booleanValue: false },
                            revokedAt: { integerValue: String(now) }
                        }
                    }
                );
                if (patch.statusCode >= 400) {
                    return res.status(patch.statusCode).json({ success: false, error: JSON.stringify(patch.data) });
                }
                return res.status(200).json({ success: true, action: 'revoke', docId, revokedAt: now });
            }

            if (action === 'activate') {
                const now = Date.now();
                const doctorName = String(body.doctorName || '').trim();
                const licenseKey = String(body.licenseKey || computeLicenseKey(doctorName)).trim();
                const fields = {
                    isLicensed: { booleanValue: true },
                    licenseActive: { booleanValue: true },
                    revokedAt: { integerValue: '0' },
                    lastUpdated: { integerValue: String(now) }
                };
                const masks = ['isLicensed', 'licenseActive', 'revokedAt', 'lastUpdated'];
                if (licenseKey) {
                    fields.licenseKey = { stringValue: licenseKey };
                    masks.push('licenseKey');
                }
                const maskQuery = masks.map(m => `updateMask.fieldPaths=${m}`).join('&');
                const patch = await firestoreRequest(
                    token,
                    'PATCH',
                    `${basePath}/${encodeURIComponent(docId)}?${maskQuery}`,
                    { fields }
                );
                if (patch.statusCode >= 400) {
                    return res.status(patch.statusCode).json({ success: false, error: JSON.stringify(patch.data) });
                }
                return res.status(200).json({ success: true, action: 'activate', docId });
            }

            if (action === 'delete') {
                const del = await firestoreRequest(
                    token,
                    'DELETE',
                    `${basePath}/${encodeURIComponent(docId)}`
                );
                if (del.statusCode >= 400) {
                    return res.status(del.statusCode).json({ success: false, error: JSON.stringify(del.data) });
                }
                return res.status(200).json({ success: true, action: 'delete', docId });
            }

            return res.status(400).json({ success: false, error: `Action inconnue: ${action}` });
        }

        return res.status(405).json({ success: false, error: 'Méthode non autorisée.' });
    } catch (error) {
        return res.status(500).json({ success: false, error: error.message });
    }
};
